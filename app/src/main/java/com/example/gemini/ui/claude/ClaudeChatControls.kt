package com.example.gemini.ui.claude

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.claude.ClaudeAccountManager
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.agent.claude.ClaudePreferences
import com.example.gemini.theme.QuotaAmber
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.AgentSheetHeader
import com.example.gemini.ui.agents.SegmentedSelector
import com.example.gemini.ui.agents.SheetSectionLabel
import com.example.gemini.ui.components.ClaudeAccent
import kotlinx.coroutines.delay

private fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> "${n / 1000}k"
    else -> n.toString()
}

/**
 * Claude chat controls shown above the input: permission mode, effort, thinking, and the live indicators
 * (prompt-cache timer, context window, plan usage).
 */
@Composable
fun ClaudeControlsStrip(
    backend: ClaudeChatBackend,
    prefs: ClaudePreferences,
    modifier: Modifier = Modifier
) {
    val mode by backend.permissionMode.collectAsState()
    val effort by backend.effort.collectAsState()
    val thinking by backend.thinkingEnabled.collectAsState()
    val cache by backend.cacheInfo.collectAsState()
    val context by backend.contextUsage.collectAsState()
    val limits by backend.rateLimits.collectAsState()
    val modelInfos by backend.modelInfos.collectAsState()
    val selectedModel by backend.selectedModelId.collectAsState()
    val allowBypass by prefs.allowBypass.collectAsState()

    val info = modelInfos.find { it.value == selectedModel } ?: modelInfos.firstOrNull()
    var modeMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var showCache by remember { mutableStateOf(false) }

    // re-evaluate the cache timer every 30 s
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // permission mode
        Box {
            val modeColor = when (mode) {
                "plan" -> Color(0xFF0EA5E9)
                "acceptEdits" -> QuotaGreen
                "auto" -> Color(0xFF8B5CF6)
                "bypassPermissions" -> QuotaRed
                "dontAsk" -> QuotaAmber
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            }
            ControlChip(
                text = permissionModeLabel(mode),
                icon = Icons.Outlined.Shield,
                color = modeColor,
                highlighted = mode != "default",
                onClick = { modeMenu = true }
            )
            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                ClaudeChatBackend.PERMISSION_MODES.forEach { (value, label) ->
                    val available = when (value) {
                        "bypassPermissions" -> allowBypass
                        "auto" -> info?.supportsAutoMode != false
                        else -> true
                    }
                    if (!available) return@forEach
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(label, fontWeight = if (value == mode) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                                Text(permissionModeDescription(value), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        },
                        trailingIcon = { if (value == mode) Icon(Icons.Default.Check, null, tint = ClaudeAccent) },
                        onClick = {
                            modeMenu = false
                            backend.setPermissionMode(value)
                        }
                    )
                }
            }
        }

        // effort
        val levels = info?.supportedEffortLevels.orEmpty()
        if (levels.isNotEmpty()) {
            Box {
                ControlChip(text = "Effort: ${effortLabel(effort)}", icon = Icons.Outlined.Speed, onClick = { effortMenu = true })
                DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                    (listOf<String?>(null) + levels).forEach { level ->
                        DropdownMenuItem(
                            text = { Text(effortLabel(level), fontWeight = if (level == effort) FontWeight.Bold else FontWeight.Normal) },
                            trailingIcon = { if (level == effort) Icon(Icons.Default.Check, null, tint = ClaudeAccent) },
                            onClick = {
                                effortMenu = false
                                backend.setEffort(level)
                            }
                        )
                    }
                }
            }
        }

        // thinking
        ControlChip(
            text = if (thinking) "Thinking" else "No thinking",
            icon = Icons.Outlined.Lightbulb,
            color = if (thinking) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            highlighted = thinking,
            onClick = { backend.setThinking(!thinking) }
        )

        // prompt cache
        cache?.let { c ->
            val left = c.anchorMs + c.ttlMs - now
            val warm = !c.compacted && c.ttlMs > 0 && left > 0
            ControlChip(
                text = if (warm) "Cache ${((left + 59_999) / 60_000)}m" else "Cache cold",
                icon = Icons.Outlined.Timer,
                color = if (warm) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                onClick = { showCache = true }
            )
        }

        // context window
        context?.let { ctx ->
            val pct = ctx.percentage
            ControlChip(
                text = "Context ${pct.toInt()}%",
                icon = Icons.Outlined.DataUsage,
                color = usageColor(pct / 100.0),
                onClick = { showContext = true }
            )
        }

        // plan usage (live)
        limits["five_hour"]?.let { w ->
            ControlChip(text = "5h ${(w.utilization * 100).toInt()}%", icon = Icons.Outlined.Bolt, color = usageColor(w.utilization))
        }
    }

    if (showContext) {
        val ctx = context
        AlertDialog(
            onDismissRequest = { showContext = false },
            title = { Text("Context window", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ctx != null) {
                        Text("${compactTokens(ctx.totalTokens)} of ${compactTokens(ctx.maxTokens)} tokens used (${ctx.percentage.toInt()}%)", fontSize = 13.sp)
                        UsageBar("Used", ctx.percentage / 100.0, null)
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        ctx.categories.forEach { cat ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(cat.name, fontSize = 12.5.sp)
                                Text(compactTokens(cat.tokens), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                        Text(
                            "Send /compact to summarize the conversation and free space.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    backend.refreshContextUsage()
                }) { Text("Refresh") }
            },
            dismissButton = { TextButton(onClick = { showContext = false }) { Text("Close") } }
        )
    }

    if (showCache) {
        val c = cache
        AlertDialog(
            onDismissRequest = { showCache = false },
            title = { Text("Prompt cache", fontWeight = FontWeight.Bold) },
            text = {
                val left = c?.let { it.anchorMs + it.ttlMs - now } ?: 0
                Text(
                    when {
                        c == null -> "No cache information yet."
                        c.compacted -> "The conversation was compacted, so your next message will re-cache it."
                        left > 0 -> "Cache warm, about ${(left + 59_999) / 60_000} min left (${if (c.ttlMs >= 3_600_000) "1-hour" else "5-minute"} cache). " +
                            "Messages sent before it expires reuse it and cost less."
                        else -> "The prompt cache has likely expired, so your next message will re-cache about ${compactTokens(c.recacheTokens)} tokens."
                    },
                    fontSize = 13.5.sp
                )
            },
            confirmButton = { TextButton(onClick = { showCache = false }) { Text("OK") } }
        )
    }
}

/** Model picker for Claude chats: CLI models, effort per model, thinking, fast mode, plan usage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeModelSheet(
    backend: ClaudeChatBackend,
    account: ClaudeAccountManager,
    onDismiss: () -> Unit
) {
    val models by backend.modelInfos.collectAsState()
    val fallback by backend.availableModels.collectAsState()
    val selected by backend.selectedModelId.collectAsState()
    val active by backend.activeModel.collectAsState()
    val effort by backend.effort.collectAsState()
    val thinking by backend.thinkingEnabled.collectAsState()
    val fast by backend.fastModeState.collectAsState()
    val refreshing by backend.isRefreshingModels.collectAsState()
    val usage by account.usage.collectAsState()
    val liveLimits by backend.rateLimits.collectAsState()
    val cardColor = claudeCardColor()

    LaunchedEffect(Unit) { account.refreshUsage(force = false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 22.dp).navigationBarsPadding()
        ) {
            val rows = if (models.isNotEmpty()) models.map { Triple(it.value, it.displayName.ifBlank { it.value }, it.description) }
            else fallback.map { Triple(it.id, it.displayName, it.description) }

            AgentSheetHeader(
                AgentKind.CLAUDE,
                "Select model",
                if (rows.isEmpty()) "no models loaded" else "${rows.size} models",
                refreshing = refreshing,
                onRefresh = { backend.refreshInfo(force = true) }
            )
            active?.let {
                Text(
                    "This chat is running $it",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 48.dp)
                )
            }

            SheetSectionLabel("Model")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (value, name, desc) ->
                    val isSel = value == selected
                    Surface(
                        onClick = { backend.selectModel(value) },
                        shape = RoundedCornerShape(14.dp),
                        color = cardColor,
                        border = BorderStroke(if (isSel) 1.5.dp else 1.dp, if (isSel) ClaudeAccent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp)
                                if (desc.isNotBlank()) {
                                    Text(desc, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            }
                            if (isSel) {
                                Spacer(Modifier.width(8.dp))
                                Row(
                                    Modifier.clip(RoundedCornerShape(6.dp)).background(ClaudeAccent.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Check, null, tint = ClaudeAccent, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Active", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = ClaudeAccent)
                                }
                            }
                        }
                    }
                }
            }

            val info = models.find { it.value == selected }
            val levels = info?.supportedEffortLevels.orEmpty()
            if (levels.isNotEmpty()) {
                SheetSectionLabel("Effort", trailing = "how hard Claude works on each reply")
                SegmentedSelector(
                    options = (listOf<String?>(null) + levels).map { it to effortLabel(it).replace("Extra high", "X-high") },
                    selected = effort,
                    accent = ClaudeAccent,
                    onSelect = { backend.setEffort(it) }
                )
            }

            SheetSectionLabel("Reasoning")
            Surface(shape = RoundedCornerShape(14.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lightbulb, null, tint = if (thinking) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text("Thinking", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Reason before answering (shown as a summary)", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Switch(checked = thinking, onCheckedChange = { backend.setThinking(it) }, colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent))
                    }
                    if (info?.supportsFastMode == true) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Bolt, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Fast mode · ${fast.replaceFirstChar { it.uppercase() }}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Send /fast in the chat to toggle", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                        }
                    }
                }
            }

            // live windows from the chat stream take precedence over the cached usage call
            val five = liveLimits["five_hour"]?.let { it.utilization to formatReset(null, it.resetsAt) }
                ?: usage?.rate_limits?.five_hour?.let { (it.utilization ?: 0.0) / 100.0 to formatReset(it.resets_at) }
            val week = liveLimits["seven_day"]?.let { it.utilization to formatReset(null, it.resetsAt) }
                ?: usage?.rate_limits?.seven_day?.let { (it.utilization ?: 0.0) / 100.0 to formatReset(it.resets_at) }
            if (five != null || week != null) {
                SheetSectionLabel("Plan usage")
                Surface(shape = RoundedCornerShape(14.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        five?.let { UsageBar("5-hour limit", it.first, it.second) }
                        week?.let { UsageBar("Weekly limit", it.first, it.second) }
                    }
                }
            }
        }
    }
}
