package com.example.gemini.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.example.gemini.theme.isAppInDarkTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.theme.*
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class CategoryType {
    GEMINI,
    CLAUDE
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
    // Group models into two groups: Claude and Gemini (with GPT-OSS and others under Gemini)
    val claudeModels = availableModels.filter {
        (it.family == ModelFamily.CLAUDE || it.id.contains("claude", ignoreCase = true) || it.displayName.contains("claude", ignoreCase = true)) &&
                !it.id.contains("gpt", ignoreCase = true) && !it.displayName.contains("gpt", ignoreCase = true)
    }
    val geminiModels = availableModels.filter { it !in claudeModels }

    val activeModel = availableModels.find { it.id == selectedModelId }

    // Navigation state inside the sheet: null = category list, CategoryType = subcategory sheet
    var activeCategory by remember { mutableStateOf<CategoryType?>(null) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val expandedHeight = (configuration.screenHeightDp * 0.78f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        // Intercept back press when inside a subcategory inside the sheet window
        BackHandler(enabled = activeCategory != null) {
            activeCategory = null
            coroutineScope.launch {
                sheetState.partialExpand()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(expandedHeight)
                .padding(horizontal = 18.dp)
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
                    val (categoryTitle, brandColor, categoryModels) = when (currentCategory) {
                        CategoryType.GEMINI -> Triple("Google Gemini", GeminiBlue, geminiModels)
                        CategoryType.CLAUDE -> Triple("Anthropic Claude", ClaudeTerracotta, claudeModels)
                    }

                    CategoryModelsSubView(
                        title = categoryTitle,
                        brandColor = brandColor,
                        models = categoryModels,
                        selectedModelId = selectedModelId,
                        onBack = {
                            activeCategory = null
                            coroutineScope.launch {
                                sheetState.partialExpand()
                            }
                        },
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

/**
 * Main Level Sheet View
 */
/**
 * Helpers to format clean, modern quota lines like "5h · 15% · 2h 4m" and "Weekly · 85% · 3d 21h".
 */
fun formatCleanCountdown(isoString: String?, rawCountdown: String? = null): String {
    if (!isoString.isNullOrBlank()) {
        try {
            val clean = isoString.substringBefore('.').substringBefore('Z')
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val target = sdf.parse(clean)?.time
            if (target != null) {
                val diffMs = target - System.currentTimeMillis()
                if (diffMs <= 0) return "Ready"
                val totalMins = diffMs / (1000 * 60)
                val days = totalMins / (60 * 24)
                val hours = (totalMins % (60 * 24)) / 60
                val mins = totalMins % 60
                return when {
                    days > 0 && hours > 0 -> "${days}d ${hours}h"
                    days > 0 -> "${days}d"
                    hours > 0 && mins > 0 -> "${hours}h ${mins}m"
                    hours > 0 -> "${hours}h"
                    else -> "${mins}m"
                }
            }
        } catch (_: Exception) {}
    }
    if (!rawCountdown.isNullOrBlank()) {
        return rawCountdown.removePrefix("Resets in ").removePrefix("Reset in ").removePrefix("• ").trim()
    }
    return ""
}

fun buildQuotaSummaryLine(
    windowLabel: String,
    fraction: Float?,
    resetTime: String?,
    rawCountdown: String?
): String? {
    if (fraction == null) return null
    val pct = (fraction * 100f).roundToInt().coerceIn(0, 100)
    val time = formatCleanCountdown(resetTime, rawCountdown)
    return if (time.isNotBlank()) {
        "$windowLabel · $pct% · $time"
    } else {
        "$windowLabel · $pct%"
    }
}

@Composable
fun QuotaBadgeChip(
    text: String,
    fraction: Float?,
    modifier: Modifier = Modifier
) {
    val pct = fraction?.let { (it * 100f).roundToInt().coerceIn(0, 100) } ?: 100
    val dotColor = when {
        pct > 50 -> QuotaGreen
        pct > 20 -> QuotaAmber
        else -> QuotaRed
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                letterSpacing = 0.1.sp
            )
        }
    }
}

/**
 * Main Level Sheet View
 */
@Composable
private fun MainCategoryListView(
    activeModel: AiModel?,
    availableModels: List<AiModel>,
    claudeModelsCount: Int,
    geminiModelsCount: Int,
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
            .padding(bottom = 20.dp)
    ) {
        // Compact Centered Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Select Model",
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (availableModelsCount > 0) "$availableModelsCount models available" else "No models loaded",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
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
                modifier = Modifier
                    .size(36.dp)
                    .align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh models & quotas",
                    tint = ClaudeTerracotta,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (availableModelsCount == 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No Models Loaded",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Connect your Google account in Settings or tap refresh to load your active models.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Category Navigation Tiles: Gemini FIRST, Claude SECOND
            val isClaudeActive = activeModel != null && (
                activeModel.family == ModelFamily.CLAUDE ||
                activeModel.id.contains("claude", ignoreCase = true) ||
                activeModel.displayName.contains("claude", ignoreCase = true)
            ) && !activeModel.id.contains("gpt", ignoreCase = true) && !activeModel.displayName.contains("gpt", ignoreCase = true)
            val isGeminiActive = activeModel != null && !isClaudeActive

            val cleanActiveModelName = activeModel?.displayName
                ?.replace(Regex("\\s*\\(?Thinking\\)?", RegexOption.IGNORE_CASE), "")
                ?.trim()

            val geminiGroup = quotaSummary?.groups?.find {
                it.groupId == "gemini" || it.groupName.contains("gemini", ignoreCase = true)
            }
            val claudeGroup = quotaSummary?.groups?.find {
                it.groupId == "claude_gpt" || it.groupName.contains("claude", ignoreCase = true) || it.groupName.contains("gpt", ignoreCase = true)
            }

            // 1. Google Gemini (First!)
            if (geminiModelsCount > 0) {
                val fiveHour = geminiGroup?.fiveHour
                val weekly = geminiGroup?.weekly
                val fiveHourFraction = fiveHour?.remainingFraction ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }?.remainingFraction
                val weeklyFraction = weekly?.remainingFraction ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }?.weeklyRemainingFraction

                val line1 = buildQuotaSummaryLine(
                    "5h",
                    fiveHourFraction,
                    fiveHour?.resetTime,
                    fiveHour?.countdown ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }?.resetCountdown
                )
                val line2 = buildQuotaSummaryLine(
                    "7d",
                    weeklyFraction,
                    weekly?.resetTime,
                    weekly?.countdown ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.GEMINI } }?.weeklyResetCountdown
                )

                CategoryNavigationTile(
                    title = "Google Gemini",
                    activeModelName = if (isGeminiActive) cleanActiveModelName else null,
                    line1 = line1,
                    fraction1 = fiveHourFraction,
                    line2 = line2,
                    fraction2 = weeklyFraction,
                    count = geminiModelsCount,
                    brandColor = GeminiBlue,
                    onClick = { onSelectCategory(CategoryType.GEMINI) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 2. Anthropic Claude (Second, no GPT in title!)
            if (claudeModelsCount > 0) {
                val fiveHour = claudeGroup?.fiveHour
                val weekly = claudeGroup?.weekly
                val fiveHourFraction = fiveHour?.remainingFraction ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }?.remainingFraction
                val weeklyFraction = weekly?.remainingFraction ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }?.weeklyRemainingFraction

                val line1 = buildQuotaSummaryLine(
                    "5h",
                    fiveHourFraction,
                    fiveHour?.resetTime,
                    fiveHour?.countdown ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }?.resetCountdown
                )
                val line2 = buildQuotaSummaryLine(
                    "7d",
                    weeklyFraction,
                    weekly?.resetTime,
                    weekly?.countdown ?: quotas.find { q -> availableModels.any { it.id == q.modelId && it.family == ModelFamily.CLAUDE } }?.weeklyResetCountdown
                )

                CategoryNavigationTile(
                    title = "Anthropic Claude",
                    activeModelName = if (isClaudeActive) cleanActiveModelName else null,
                    line1 = line1,
                    fraction1 = fiveHourFraction,
                    line2 = line2,
                    fraction2 = weeklyFraction,
                    count = claudeModelsCount,
                    brandColor = ClaudeTerracotta,
                    onClick = { onSelectCategory(CategoryType.CLAUDE) }
                )
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
    brandColor: Color,
    models: List<AiModel>,
    selectedModelId: String,
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
            .padding(bottom = 16.dp)
    ) {
        // Compact Back Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to categories",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${groupedModels.size} base models • ${models.size} variants",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(groupedModels, key = { it.baseId }) { group ->
                if (group.variants.size > 1) {
                    TieredModelGroupCard(
                        group = group,
                        selectedModelId = selectedModelId,
                        onSelectModel = onSelectModel
                    )
                } else {
                    val model = group.variants.first().model
                    ModelRowItem(
                        model = model,
                        isSelected = model.id == selectedModelId,
                        onSelect = { onSelectModel(model.id) }
                    )
                }
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
    onSelectModel: (String) -> Unit
) {
    val isAnyVariantSelected = group.variants.any { it.model.id == selectedModelId }
    val brandColor = when (group.family) {
        ModelFamily.CLAUDE -> ClaudeTerracotta
        ModelFamily.GEMINI -> GeminiBlue
        ModelFamily.OTHER -> MaterialTheme.colorScheme.primary
    }

    val isDark = isAppInDarkTheme()
    val cardBg = if (isAnyVariantSelected) {
        if (isDark) ClaudeDarkSurface else Color.White
    } else {
        if (isDark) ClaudeDarkSurface.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.85f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .clip(RoundedCornerShape(12.dp)),
        color = cardBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.baseName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (isAnyVariantSelected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(brandColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Active",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Thinking Effort Selector Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.variants.forEach { variant ->
                    val isSelected = variant.model.id == selectedModelId
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectModel(variant.model.id) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) brandColor else brandColor.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(11.dp)
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

/**
 * Clickable Category Tile with forward arrow navigating to the sub-sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryNavigationTile(
    title: String,
    activeModelName: String? = null,
    line1: String?,
    fraction1: Float?,
    line2: String? = null,
    fraction2: Float? = null,
    count: Int,
    brandColor: Color,
    onClick: () -> Unit
) {
    val isDark = isAppInDarkTheme()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 88.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        color = if (isDark) ClaudeDarkSurface else Color.White,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
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

                Spacer(modifier = Modifier.height(3.dp))

                if (activeModelName != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Active",
                            tint = brandColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = activeModelName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = brandColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = "Tap to choose model",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }

                if (!line1.isNullOrBlank() || !line2.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (!line1.isNullOrBlank()) {
                            QuotaBadgeChip(text = line1, fraction = fraction1)
                        }
                        if (!line2.isNullOrBlank()) {
                            QuotaBadgeChip(text = line2, fraction = fraction2)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Open $title",
                tint = if (activeModelName != null) brandColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(14.dp)
            )
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
    onSelect: () -> Unit
) {
    val brandColor = if (model.family == ModelFamily.CLAUDE) ClaudeTerracotta else GeminiBlue

    val isDark = isAppInDarkTheme()
    val cardBg = if (isSelected) {
        if (isDark) ClaudeDarkSurface else Color.White
    } else {
        if (isDark) ClaudeDarkSurface.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.85f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() },
        color = cardBg,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = model.displayName.replace(Regex("\\s*\\(?Thinking\\)?", RegexOption.IGNORE_CASE), "").trim(),
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(brandColor.copy(alpha = 0.15f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = brandColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Active",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = brandColor
                        )
                    }
                }
            }
        }
    }
}
