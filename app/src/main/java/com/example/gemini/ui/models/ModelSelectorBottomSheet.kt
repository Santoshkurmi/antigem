package com.example.gemini.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AgyQuotaGroups
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelGroupPrefs
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.domain.model.QuotaAgent
import com.example.gemini.domain.model.QuotaGroup
import com.example.gemini.domain.model.QuotaSummaryResponse
import com.example.gemini.theme.QuotaAmber
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import com.example.gemini.ui.agents.AgentSheetHeader
import com.example.gemini.ui.components.AgyAccent
import com.example.gemini.ui.components.ClaudeAccent
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Antigravity's model picker, laid out like Claude Code's and OpenCode's: one section per quota group with its
 * 5h / 7d share left on top, then its models, each with a Low / Medium / High selector. The pencil turns on group
 * editing, where any model can be moved to another group (kept on this device, wins over the automatic match).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorBottomSheet(
    selectedModelId: String,
    availableModels: List<AiModel>,
    @Suppress("UNUSED_PARAMETER") quotas: List<ModelQuota>,
    quotaSummary: QuotaSummaryResponse? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    remember { ModelGroupPrefs.init(context) }
    val overrides by ModelGroupPrefs.overrides(QuotaAgent.AGY).collectAsState()
    val pillWindow by ModelGroupPrefs.pillWindow(QuotaAgent.AGY).collectAsState()
    var editing by remember { mutableStateOf(false) }

    val groups = remember(quotaSummary) { AgyQuotaGroups.groups(quotaSummary) }
    val families = remember(availableModels) { groupModelsByBaseName(availableModels) }
    val sections = remember(groups, families, overrides) {
        if (groups.isEmpty()) {
            listOf(AgySection(null, families))
        } else {
            val byGroup = families.groupBy { AgyQuotaGroups.groupOf(it.variants.first().model, groups, overrides)?.id }
            groups.mapNotNull { g -> byGroup[g.id]?.let { AgySection(g, it) } } +
                listOfNotNull(byGroup[null]?.let { AgySection(null, it) })
        }
    }
    // the status bar's height, read once: it does not change while the sheet moves
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // opens at half height like the Claude Code picker; swipe up (or scroll the list) for the full list
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // The default top inset grows as the sheet's edge enters the status bar, which makes the sheet taller and so
        // moves its resting point: a fast fling then bounced it up and down without end. Instead the sheet's height
        // never depends on its position: no top inset, and at most the height that ends right below the status bar.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
      // Back closes the picker in one press. Pulled up to full height, Material3 would first drop it to half height,
      // so it took two presses; at half height its own handler already closes it (with the back-gesture animation).
      BackHandler(enabled = sheetState.currentValue == SheetValue.Expanded) {
          scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
      }
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (maxHeight - statusBarTop).coerceAtLeast(0.dp))
                .padding(horizontal = 18.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
        ) {
            AgentSheetHeader(
                AgentKind.AGY,
                title = "Select model",
                detail = if (availableModels.isNotEmpty()) "${availableModels.size} models" else "no models loaded",
                refreshing = isRefreshing,
                onRefresh = onRefresh,
                actions = if (groups.size > 1) ({ EditGroupsAction(editing, AgyAccent) { editing = !editing } }) else null
            )
            when {
                editing -> EditGroupsBanner(AgyAccent) { editing = false }
                groups.any { it.fiveHour != null } ->
                    PillWindowRow(pillWindow, AgyAccent) { ModelGroupPrefs.setPillWindow(QuotaAgent.AGY, it) }
            }

            if (availableModels.isEmpty()) {
                NoModelsCard()
            } else {
                // the list takes the room left under the header, so the sheet only grows to its cap and then scrolls
                LazyColumn(
                    Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    sections.forEach { section ->
                        val accent = section.group?.let(::agyGroupColor) ?: AgyAccent
                        item(key = "h_${section.group?.id ?: "_none"}") {
                            QuotaGroupHeader(
                                title = section.group?.title ?: "Models",
                                accent = accent,
                                count = section.families.size,
                                windows = section.group?.headerWindows.orEmpty(),
                                modifier = Modifier.animateItem()
                            )
                        }
                        items(section.families, key = { "m_${it.baseId}" }) { family ->
                            val key = AgyQuotaGroups.modelKey(family.variants.first().model)
                            AgyModelCard(
                                family = family,
                                selectedModelId = selectedModelId,
                                accent = accent,
                                editing = editing,
                                groupControl = if (editing) {
                                    {
                                        GroupMoveButton(
                                            current = section.group,
                                            automatic = AgyQuotaGroups.autoGroup(family.variants.first().model, groups),
                                            isMoved = overrides[key] != null && groups.any { it.id == overrides[key] },
                                            groups = groups,
                                            accent = accent,
                                            onPick = { ModelGroupPrefs.setGroup(QuotaAgent.AGY, key, it) }
                                        )
                                    }
                                } else null,
                                onSelect = { id ->
                                    onSelectModel(id)
                                    onDismiss()
                                },
                                modifier = Modifier.padding(vertical = 3.dp).animateItem()
                            )
                        }
                    }
                }
            }
        }
      }
    }
}

private data class AgySection(val group: QuotaGroup?, val families: List<SubGroupedModel>)

private fun agyGroupColor(g: QuotaGroup): Color = when {
    g.id == "argon" || g.title.contains("argon", ignoreCase = true) -> Color(0xFF9B6BDF)
    g.id == "claude_gpt" || g.title.contains("claude", ignoreCase = true) || g.title.contains("gpt", ignoreCase = true) -> ClaudeAccent
    g.id == "gemini" || g.title.contains("gemini", ignoreCase = true) -> AgyAccent
    else -> Color(0xFF14B8A6)
}

@Composable
private fun NoModelsCard() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = com.example.gemini.theme.AppColors.card,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
    ) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No models loaded", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "Connect your Google account in Settings or tap refresh to load your models.",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/** One model (all its thinking tiers) as a card, styled like the Claude Code picker's rows. */
@Composable
private fun AgyModelCard(
    family: SubGroupedModel,
    selectedModelId: String,
    accent: Color,
    editing: Boolean,
    groupControl: (@Composable () -> Unit)?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val variants = remember(family) { family.variants.sortedBy { tierRank(it.tierLabel) } }
    val active = variants.firstOrNull { it.model.id == selectedModelId }
    val isActive = active != null
    val borderColor by animateColorAsState(
        if (isActive) accent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
        label = "model_card_border"
    )
    Surface(
        onClick = { onSelect((active ?: defaultVariant(variants)).model.id) },
        enabled = !editing,
        shape = RoundedCornerShape(14.dp),
        color = com.example.gemini.theme.AppColors.card,
        border = BorderStroke(if (isActive) 1.5.dp else 1.dp, borderColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        family.baseName,
                        fontSize = 14.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (family.description.isNotBlank()) {
                        Text(
                            family.description,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (isActive && !editing) {
                    Spacer(Modifier.width(8.dp))
                    ActiveModelBadge(accent)
                }
            }
            if (variants.size > 1) {
                Spacer(Modifier.height(10.dp))
                TierSelector(variants, selectedModelId, accent, enabled = !editing, onSelect = onSelect)
            }
            if (groupControl != null) {
                Spacer(Modifier.height(10.dp))
                groupControl()
            }
        }
    }
}

/** Low / Medium / High as one track with a highlight that slides to the chosen tier. */
@Composable
private fun TierSelector(
    variants: List<ModelVariant>,
    selectedModelId: String,
    accent: Color,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    val selIdx = variants.indexOfFirst { it.model.id == selectedModelId }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(track).padding(3.dp)
    ) {
        val segment = maxWidth / variants.size
        val offset by animateDpAsState(
            segment * selIdx.coerceAtLeast(0),
            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
            label = "tier_offset"
        )
        val highlight by animateFloatAsState(if (selIdx >= 0) 1f else 0f, label = "tier_highlight")
        Box(
            Modifier.offset(x = offset).width(segment).fillMaxHeight()
                .graphicsLayer { alpha = highlight }
                .clip(RoundedCornerShape(8.dp)).background(accent)
        )
        Row(Modifier.fillMaxSize()) {
            variants.forEachIndexed { i, v ->
                val on = i == selIdx
                val content by animateColorAsState(
                    if (on) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    label = "tier_text"
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { onSelect(v.model.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val level = tierRank(v.tierLabel).takeIf { it in 1..3 }
                        if (level != null) {
                            TierBars(level, content)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            v.tierLabel.ifBlank { v.model.displayName },
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Three small bars, [level] of them lit: how much the tier thinks. */
@Composable
private fun TierBars(level: Int, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..3) {
            Box(
                Modifier.width(3.dp).height((3 + i * 3).dp).clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) color else color.copy(alpha = 0.28f))
            )
        }
    }
}

private fun tierRank(label: String): Int = when (label.lowercase()) {
    "low" -> 1
    "medium", "med" -> 2
    "high" -> 3
    else -> 4
}

/** Tapping a card (not a tier) picks the server's default tier, else Medium, else the first. */
private fun defaultVariant(variants: List<ModelVariant>): ModelVariant =
    variants.firstOrNull { it.model.isDefault } ?: variants.firstOrNull { tierRank(it.tierLabel) == 2 } ?: variants.first()

/** One model's tier (High, Medium, Low) as a selectable variant. */
data class ModelVariant(
    val model: AiModel,
    val tierLabel: String
)

/** A model with all its thinking tiers. */
data class SubGroupedModel(
    val baseId: String,
    val baseName: String,
    val description: String,
    val family: ModelFamily,
    val variants: List<ModelVariant>
)

private fun groupModelsByBaseName(models: List<AiModel>): List<SubGroupedModel> {
    val map = LinkedHashMap<String, MutableList<AiModel>>()
    for (model in models) map.getOrPut(AgyQuotaGroups.modelKey(model)) { mutableListOf() }.add(model)

    return map.map { (baseId, modelList) ->
        val first = modelList.first()
        val cleanBaseName = first.baseName.ifBlank {
            first.displayName.replace(Regex("\\s*\\((High|Medium|Low|Med|Thinking)\\)", RegexOption.IGNORE_CASE), "").trim()
        }
        val variants = modelList.map { m ->
            val label = m.tier?.ifBlank { null } ?: when {
                m.key.endsWith("-high", ignoreCase = true) || m.id.endsWith("-high", ignoreCase = true) || m.displayName.contains("High", ignoreCase = true) -> "High"
                m.key.endsWith("-medium", ignoreCase = true) || m.key.endsWith("-med", ignoreCase = true) || m.id.endsWith("-medium", ignoreCase = true) || m.displayName.contains("Medium", ignoreCase = true) -> "Medium"
                m.key.endsWith("-low", ignoreCase = true) || m.id.endsWith("-low", ignoreCase = true) || m.displayName.contains("Low", ignoreCase = true) -> "Low"
                else -> ""
            }
            ModelVariant(m, if (label.equals("Med", ignoreCase = true)) "Medium" else label)
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
