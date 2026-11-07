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

data class ModelCategoryUi(
    val id: String,
    val title: String,
    val models: List<AiModel>,
    val brandColor: Color,
    val line1: String? = null,
    val fraction1: Float? = null,
    val line2: String? = null,
    val fraction2: Float? = null
)

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
    val activeModel = availableModels.find { it.id == selectedModelId }
    val fallbackColor = MaterialTheme.colorScheme.primary

    val categories = remember(availableModels, quotaSummary, quotas, fallbackColor) {
        val claudeGptModels = availableModels.filter {
            it.family == ModelFamily.CLAUDE ||
                    it.id.contains("claude", ignoreCase = true) ||
                    it.displayName.contains("claude", ignoreCase = true) ||
                    it.id.contains("gpt", ignoreCase = true) ||
                    it.displayName.contains("gpt", ignoreCase = true)
        }
        val argonModels = availableModels.filter {
            it.id.contains("argon", ignoreCase = true) ||
                    it.key.contains("argon", ignoreCase = true) ||
                    it.displayName.contains("argon", ignoreCase = true)
        }
        val standardGeminiModels = availableModels.filter { it !in claudeGptModels && it !in argonModels }

        val groups = quotaSummary?.groups.orEmpty()
        if (groups.isNotEmpty()) {
            val list = mutableListOf<ModelCategoryUi>()
            for (grp in groups) {
                val gName = grp.groupName.ifBlank { grp.groupId }
                val isArgon = grp.groupId == "argon" || gName.contains("argon", ignoreCase = true)
                val isClaudeGpt = grp.groupId == "claude_gpt" ||
                        gName.contains("claude", ignoreCase = true) ||
                        gName.contains("gpt", ignoreCase = true)

                val (catModels, color, defaultId) = when {
                    isArgon -> Triple(argonModels, Color(0xFF8E44AD), "argon")
                    isClaudeGpt -> Triple(claudeGptModels, ClaudeTerracotta, "claude_gpt")
                    else -> Triple(standardGeminiModels, GeminiBlue, "gemini")
                }

                val fiveHour = grp.fiveHour
                val weekly = grp.weekly

                val fiveHourFraction = fiveHour?.remainingFraction
                    ?: quotas.find { q -> catModels.any { it.id == q.modelId } }?.remainingFraction
                val weeklyFraction = weekly?.remainingFraction
                    ?: quotas.find { q -> catModels.any { it.id == q.modelId } }?.weeklyRemainingFraction

                val l1 = if (fiveHour != null || fiveHourFraction != null) {
                    buildQuotaSummaryLine("5h", fiveHourFraction, fiveHour?.resetTime, fiveHour?.countdown)
                } else null

                val l2 = if (weekly != null || weeklyFraction != null) {
                    buildQuotaSummaryLine("7d", weeklyFraction, weekly?.resetTime, weekly?.countdown)
                } else null

                val line1: String?
                val frac1: Float?
                val line2: String?
                val frac2: Float?
                if (l1 == null && l2 != null) {
                    line1 = l2
                    frac1 = weeklyFraction
                    line2 = null
                    frac2 = null
                } else {
                    line1 = l1
                    frac1 = fiveHourFraction
                    line2 = l2
                    frac2 = weeklyFraction
                }

                list.add(
                    ModelCategoryUi(
                        id = grp.groupId.ifBlank { defaultId },
                        title = gName,
                        models = catModels,
                        brandColor = color,
                        line1 = line1,
                        fraction1 = frac1,
                        line2 = line2,
                        fraction2 = frac2
                    )
                )
            }

            // Ensure any models not covered by the server's groups are still accessible
            val coveredModels = list.flatMap { it.models }.toSet()
            val uncovered = availableModels.filter { it !in coveredModels }
            if (uncovered.isNotEmpty()) {
                list.add(
                    ModelCategoryUi(
                        id = "other",
                        title = "Other Models",
                        models = uncovered,
                        brandColor = fallbackColor
                    )
                )
            }
            list
        } else {
            // Fallback when quota summary is not loaded yet
            val list = mutableListOf<ModelCategoryUi>()
            if (standardGeminiModels.isNotEmpty() || argonModels.isNotEmpty()) {
                val geminiAll = standardGeminiModels + argonModels
                val qMatch = quotas.find { q -> geminiAll.any { it.id == q.modelId } }
                list.add(
                    ModelCategoryUi(
                        id = "gemini",
                        title = "Google Gemini",
                        models = geminiAll,
                        brandColor = GeminiBlue,
                        line1 = buildQuotaSummaryLine("5h", qMatch?.remainingFraction, qMatch?.resetTime, qMatch?.resetCountdown),
                        fraction1 = qMatch?.remainingFraction,
                        line2 = buildQuotaSummaryLine("7d", qMatch?.weeklyRemainingFraction, null, qMatch?.weeklyResetCountdown),
                        fraction2 = qMatch?.weeklyRemainingFraction
                    )
                )
            }
            if (claudeGptModels.isNotEmpty()) {
                val qMatch = quotas.find { q -> claudeGptModels.any { it.id == q.modelId } }
                list.add(
                    ModelCategoryUi(
                        id = "claude_gpt",
                        title = "Claude and GPT models",
                        models = claudeGptModels,
                        brandColor = ClaudeTerracotta,
                        line1 = buildQuotaSummaryLine("5h", qMatch?.remainingFraction, qMatch?.resetTime, qMatch?.resetCountdown),
                        fraction1 = qMatch?.remainingFraction,
                        line2 = buildQuotaSummaryLine("7d", qMatch?.weeklyRemainingFraction, null, qMatch?.weeklyResetCountdown),
                        fraction2 = qMatch?.weeklyRemainingFraction
                    )
                )
            }
            list
        }
    }

    // Navigation state inside the sheet: null = category list, ModelCategoryUi = subcategory sheet
    var activeCategory by remember { mutableStateOf<ModelCategoryUi?>(null) }

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
                    // Main Level: Active Model + Dynamic Category Selection Tiles
                    MainCategoryListView(
                        activeModel = activeModel,
                        categories = categories,
                        availableModelsCount = availableModels.size,
                        isRefreshing = isRefreshing,
                        onRefresh = onRefresh,
                        onSelectCategory = { category -> activeCategory = category },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Sub-Sheet: Models inside the chosen category
                    CategoryModelsSubView(
                        title = currentCategory.title,
                        brandColor = currentCategory.brandColor,
                        models = currentCategory.models,
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
    categories: List<ModelCategoryUi>,
    availableModelsCount: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectCategory: (ModelCategoryUi) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 20.dp)
    ) {
        // Agent header (same as the Claude Code picker)
        com.example.gemini.ui.agents.AgentSheetHeader(
            agent = com.example.gemini.domain.model.AgentKind.AGY,
            title = "Select model",
            detail = if (availableModelsCount > 0) "$availableModelsCount models" else "no models loaded",
            refreshing = isRefreshing,
            onRefresh = onRefresh
        )

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
            val cleanActiveModelName = activeModel?.displayName
                ?.replace(Regex("\\s*\\(?Thinking\\)?", RegexOption.IGNORE_CASE), "")
                ?.trim()

            for (cat in categories) {
                val isCatActive = activeModel != null && cat.models.any { it.id == activeModel.id || it.key == activeModel.key }

                CategoryNavigationTile(
                    title = cat.title,
                    activeModelName = if (isCatActive) cleanActiveModelName else null,
                    line1 = cat.line1,
                    fraction1 = cat.fraction1,
                    line2 = cat.line2,
                    fraction2 = cat.fraction2,
                    count = cat.models.size,
                    brandColor = cat.brandColor,
                    onClick = { onSelectCategory(cat) }
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
        com.example.gemini.theme.AppColors.card
    } else {
        com.example.gemini.theme.AppColors.card.copy(alpha = if (isDark) 0.65f else 0.85f)
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
        color = com.example.gemini.theme.AppColors.card,
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
        com.example.gemini.theme.AppColors.card
    } else {
        com.example.gemini.theme.AppColors.card.copy(alpha = if (isDark) 0.65f else 0.85f)
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
