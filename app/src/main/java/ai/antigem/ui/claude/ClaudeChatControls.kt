package ai.antigem.ui.claude

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.claude.ClaudeAccountManager
import ai.antigem.data.agent.claude.ClaudeChatBackend
import ai.antigem.data.agent.claude.contextLabel
import ai.antigem.data.agent.claude.thinkingLabel
import ai.antigem.data.agent.claude.ClaudePreferences
import ai.antigem.data.agent.claude.ClaudeQuotaGroups
import ai.antigem.theme.QuotaAmber
import ai.antigem.theme.QuotaGreen
import ai.antigem.theme.QuotaRed
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.ModelGroupPrefs
import ai.antigem.domain.model.QuotaAgent
import ai.antigem.ui.agents.AgentSheetHeader
import ai.antigem.ui.agents.SegmentedSelector
import ai.antigem.ui.agents.SheetSectionLabel
import ai.antigem.ui.components.ClaudeAccent
import kotlinx.coroutines.delay
import ai.antigem.i18n.L
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.toggleRowSemantics

/** Accent of the model sheet's n-th plan group: Claude's own for "All models", then one hue per model limit. */
private fun claudeGroupColor(index: Int): Color = when (index) {
    0 -> ClaudeAccent
    1 -> Color(0xFF8E6CEF)
    2 -> Color(0xFF14B8A6)
    else -> Color(0xFFE0A100)
}

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
    modifier: Modifier = Modifier,
    /** Subagents this chat started (and theirs), newest first: the agents chip, shown once there is one. */
    subagents: List<ai.antigem.domain.model.Conversation> = emptyList(),
    /** Opens a subagent's chat (Back returns here). */
    onOpenSubagent: (String) -> Unit = {}
) {
    val mode by backend.permissionMode.collectAsState()
    val effort by backend.effort.collectAsState()
    val thinking by backend.thinkingEnabled.collectAsState()
    val cache by backend.cacheInfo.collectAsState()
    val context by backend.contextUsage.collectAsState()
    val limits by backend.rateLimits.collectAsState()
    val modelInfos by backend.modelInfos.collectAsState()
    val selectedModel by backend.selectedModelId.collectAsState()
    val appliedEffort by backend.appliedEffort.collectAsState()
    val androidContext = androidx.compose.ui.platform.LocalContext.current
    // a control the CLI refused is reverted; say why
    LaunchedEffect(Unit) {
        backend.controlMessages.collect { android.widget.Toast.makeText(androidContext, it, android.widget.Toast.LENGTH_LONG).show() }
    }

    // a custom provider's model has none of Claude's model options
    val custom = ClaudeChatBackend.providerOf(selectedModel).isNotEmpty()
    val info = if (custom) null else modelInfos.find { it.value == selectedModel } ?: modelInfos.firstOrNull()
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
        ControlChip(
            text = permissionModeLabel(mode),
            icon = permissionModeIcon(mode),
            color = permissionModeColor(mode),
            highlighted = mode != "default",
            onClick = { modeMenu = true }
        )

        // effort
        val levels = info?.supportedEffortLevels.orEmpty()
        if (levels.isNotEmpty()) {
            ControlChip(
                // "Default" shows the level the process really runs when the CLI reported it
                text = "Effort · ${effortLabel(effort)}" + if (effort == null && appliedEffort != null) " (${effortLabel(appliedEffort)})" else "",
                icon = Icons.Outlined.Speed,
                color = if (effort != null) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                highlighted = effort != null,
                onClick = { effortMenu = true }
            )
        }

        // this chat's subagents: how many are working now; tap for the list
        if (subagents.isNotEmpty()) SubagentsChip(subagents, onOpenSubagent)

        // thinking
        ControlChip(
            text = if (thinking) L.common.thinking else L.claude.chatControls.noThinking,
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
                text = if (warm) L.claude.chatControls.cacheValueM(((left + 59_999) / 60_000)) else L.claude.chatControls.cacheCold,
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

        // plan usage (live), only for Claude's own models
        limits["five_hour"]?.takeIf { !custom }?.let { w ->
            ControlChip(text = "5h ${(w.utilization * 100).toInt()}%", icon = Icons.Outlined.Bolt, color = usageColor(w.utilization))
        }
    }

    if (modeMenu) {
        val autoSupported = !custom && info?.supportsAutoMode != false
        ClaudePermissionModeSheet(
            current = mode,
            modes = ClaudeChatBackend.PERMISSION_MODES.filter { (value, _) -> value != "auto" || autoSupported },
            note = if (autoSupported) {
                L.claude.chatControls.autoAsksOnlyForRiskyActions
            } else {
                L.claude.chatControls.thisModelHasNoAutoMode
            },
            onSelect = { backend.setPermissionMode(it) },
            onDismiss = { modeMenu = false }
        )
    }
    if (effortMenu) {
        ClaudeEffortSheet(
            levels = info?.supportedEffortLevels.orEmpty(),
            current = effort,
            onSelect = { backend.setEffort(it) },
            onDismiss = { effortMenu = false }
        )
    }

    if (showContext) ClaudeContextDialog(backend) { showContext = false }

    if (showCache) {
        val c = cache
        AlertDialog(
            onDismissRequest = { showCache = false },
            title = { Text(L.claude.chatControls.promptCache, fontWeight = FontWeight.Bold) },
            text = {
                val left = c?.let { it.anchorMs + it.ttlMs - now } ?: 0
                Text(
                    when {
                        c == null -> L.claude.chatControls.noCacheInformationYet
                        c.compacted -> L.claude.chatControls.theConversationWasCompactedSoYour
                        left > 0 -> L.claude.chatControls.cacheWarmAboutValueMinLeft((left + 59_999) / 60_000, if (c.ttlMs >= 3_600_000) "1-hour" else "5-minute")
                        else -> L.claude.chatControls.thePromptCacheHasLikelyExpired(compactTokens(c.recacheTokens))
                    },
                    fontSize = 13.5.sp
                )
            },
            confirmButton = { TextButton(onClick = { showCache = false }) { Text(L.common.ok) } }
        )
    }
}

/** Model picker for Claude chats: CLI models, effort per model, thinking, fast mode, plan usage. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    val refreshingModels by backend.isRefreshingModels.collectAsState()
    val refreshingUsage by account.isRefreshingUsage.collectAsState()
    val refreshing = refreshingModels
    val modelsError by backend.modelsError.collectAsState()
    val usage by account.usage.collectAsState()
    val liveLimits by backend.rateLimits.collectAsState()
    val providers by backend.providers.collectAsState()
    val accountState by account.state.collectAsState()
    val cardColor = claudeCardColor()
    val context = androidx.compose.ui.platform.LocalContext.current
    remember { ModelGroupPrefs.init(context) }
    val groupOverrides by ModelGroupPrefs.overrides(QuotaAgent.CLAUDE).collectAsState()
    val pillWindow by ModelGroupPrefs.pillWindow(QuotaAgent.CLAUDE).collectAsState()
    var editingGroups by remember { mutableStateOf(false) }
    // plan limits as groups: "All models" (5h + 7d) and one per model with its own weekly share (Fable)
    val quotaGroups = remember(usage, liveLimits) { ClaudeQuotaGroups.groups(usage, liveLimits) }

    LaunchedEffect(Unit) {
        // the bridge's cached usage is fine when it was loaded a moment ago; after that, ask for fresh numbers
        val stale = System.currentTimeMillis() - account.usageUpdatedAt.value > 5 * 60_000L
        account.refreshUsage(force = stale)
        backend.refreshProviders()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // full height while TalkBack is on (half height as before otherwise)
        sheetState = ai.antigem.ui.components.rememberSheetStateFullUnderTalkBack(),
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 22.dp).navigationBarsPadding()
        ) {
            // Claude's own models need the Claude login; the providers' models only their key
            val signedOut = accountState == ai.antigem.data.agent.claude.ClaudeStatus.SIGNED_OUT
            val rows = when {
                signedOut -> emptyList()
                models.isNotEmpty() -> models.map { Triple(it.value, it.displayName.ifBlank { it.value }, it.description) }
                else -> fallback.filter { ClaudeChatBackend.providerOf(it.id).isEmpty() }.map { Triple(it.id, it.displayName, it.description) }
            }
            val customSections = providers.filter { it.models.isNotEmpty() }
            val customCount = customSections.sumOf { it.models.size }

            AgentSheetHeader(
                AgentKind.CLAUDE,
                L.common.selectModel,
                when {
                    rows.size + customCount > 0 -> "${rows.size + customCount} models"
                    refreshing -> L.claude.chatControls.loadingModels
                    else -> L.claude.chatControls.noModelsLoaded
                },
                refreshing = refreshingModels || refreshingUsage,
                onRefresh = {
                    // models and the plan limits shown above them (5h / 7d), then the sign-in: Claude lists its models
                    // signed out too, so without it an expired login still showed them as usable
                    backend.refreshInfo(force = true)
                    account.refreshUsage(force = true, recheckSignIn = true)
                },
                actions = if (quotaGroups.size > 1 && rows.isNotEmpty()) {
                    { ai.antigem.ui.models.EditGroupsAction(editingGroups, ClaudeAccent) { editingGroups = !editingGroups } }
                } else null
            )
            active?.let {
                Text(
                    L.claude.chatControls.thisChatIsRunningValue(it),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 48.dp)
                )
            }

            // plan limits sit on each group's header below; here: which one the chat's model pill shows
            val planGroups = quotaGroups.takeIf { rows.isNotEmpty() }.orEmpty()
            when {
                editingGroups -> ai.antigem.ui.models.EditGroupsBanner(ClaudeAccent) { editingGroups = false }
                planGroups.any { it.fiveHour != null } ->
                    ai.antigem.ui.models.PillWindowRow(pillWindow, ClaudeAccent) { ModelGroupPrefs.setPillWindow(QuotaAgent.CLAUDE, it) }
            }

            @Composable
            fun ModelRow(
                value: String,
                name: String,
                desc: String,
                warnDesc: Boolean = false,
                accent: Color = ClaudeAccent,
                groupControl: (@Composable () -> Unit)? = null
            ) {
                val isSel = value == selected
                Surface(
                    onClick = {
                        backend.selectModel(value)
                        onDismiss()
                    },
                    enabled = groupControl == null,
                    shape = RoundedCornerShape(14.dp),
                    color = cardColor,
                    border = BorderStroke(if (isSel) 1.5.dp else 1.dp, if (isSel) accent.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                    // TalkBack: "Selected" / "Not selected"
                    modifier = Modifier.fillMaxWidth().semantics { this.selected = isSel }
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = if (isSel) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp)
                                if (desc.isNotBlank()) {
                                    Text(
                                        desc, fontSize = 12.sp, lineHeight = 16.sp,
                                        color = if (warnDesc) ai.antigem.theme.QuotaAmber else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                            if (isSel && groupControl == null) {
                                Spacer(Modifier.width(8.dp))
                                ai.antigem.ui.models.ActiveModelBadge(accent)
                            }
                        }
                        if (groupControl != null) {
                            Spacer(Modifier.height(10.dp))
                            groupControl()
                        }
                    }
                }
            }

            if (planGroups.isEmpty() || signedOut) {
                SheetSectionLabel(if (customSections.isEmpty()) L.claude.chatControls.model else L.common.claude, trailing = if (customSections.isEmpty()) null else L.claude.chatControls.yourClaudePlan)
            }
            if (signedOut) {
                Text(
                    L.claude.chatControls.signInToClaudeCodeSettings,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else if (rows.isEmpty()) {
                Surface(shape = RoundedCornerShape(14.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (refreshing) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                            Spacer(Modifier.height(10.dp))
                            Text(L.claude.chatControls.loadingClaudesModels, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(L.claude.chatControls.claudeCodeIsStartingToList, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        } else {
                            Text(L.claude.chatControls.noModelsLoaded2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                modelsError ?: L.claude.chatControls.claudeCodeHasNotListedIts,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { backend.refreshInfo(force = true) }) { Text(L.common.tryAgain, color = ClaudeAccent) }
                        }
                    }
                }
            }
            if (planGroups.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rows.forEach { (value, name, desc) -> ModelRow(value, name, desc) }
                }
            } else {
                // one section per plan limit: its 5h / 7d share left on top, then the models that count against it
                val byGroup = rows.groupBy { (value, name, _) -> ClaudeQuotaGroups.groupOf(value, name, planGroups, groupOverrides)?.id }
                planGroups.forEachIndexed { i, g ->
                    val groupRows = byGroup[g.id].orEmpty()
                    if (groupRows.isEmpty()) return@forEachIndexed
                    val accent = claudeGroupColor(i)
                    ai.antigem.ui.models.QuotaGroupHeader(g.title, accent, groupRows.size, g.headerWindows)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        groupRows.forEach { (value, name, desc) ->
                            ModelRow(
                                value, name, desc,
                                accent = accent,
                                groupControl = if (editingGroups) {
                                    {
                                        ai.antigem.ui.models.GroupMoveButton(
                                            current = g,
                                            automatic = ClaudeQuotaGroups.autoGroup(value, name, planGroups),
                                            isMoved = groupOverrides[value]?.let { id -> planGroups.any { it.id == id } } == true,
                                            groups = planGroups,
                                            accent = accent,
                                            onPick = { ModelGroupPrefs.setGroup(QuotaAgent.CLAUDE, value, it) }
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }
            }

            // one section per custom provider (Settings → Claude Code → Custom providers)
            customSections.forEach { p ->
                SheetSectionLabel(p.name, trailing = L.claude.chatControls.customProvider)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.models.forEach { m ->
                        val ctxLabel = m.contextLabel(p)
                        ModelRow(
                            "@${p.id}/${m.id}",
                            m.name?.takeIf { it.isNotBlank() } ?: m.id,
                            listOfNotNull(m.id.takeIf { !m.name.isNullOrBlank() }, ctxLabel ?: L.claude.chatControls.contextUnknown, m.thinkingLabel(p)).joinToString(" · "),
                            warnDesc = ctxLabel == null
                        )
                    }
                }
            }

            val info = models.find { it.value == selected }
            val levels = info?.supportedEffortLevels.orEmpty()
            if (levels.isNotEmpty()) {
                SheetSectionLabel(L.claude.chatControls.effort, trailing = L.claude.chatControls.howHardClaudeWorksOnEach)
                SegmentedSelector(
                    options = (listOf<String?>(null) + levels).map { it to effortLabel(it).replace("Extra high", "X-high") },
                    selected = effort,
                    accent = ClaudeAccent,
                    onSelect = { backend.setEffort(it) }
                )
            }

            SheetSectionLabel(L.common.reasoning)
            Surface(shape = RoundedCornerShape(14.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.toggleRowSemantics(thinking, { backend.setThinking(it) })) {
                        Icon(Icons.Outlined.Lightbulb, null, tint = if (thinking) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(L.common.thinking, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(L.claude.chatControls.reasonBeforeAnsweringShownAsA, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        Switch(checked = thinking, onCheckedChange = { backend.setThinking(it) }, colors = SwitchDefaults.colors(checkedTrackColor = ClaudeAccent), modifier = Modifier.mergedIntoRow())
                    }
                    if (info?.supportsFastMode == true) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Bolt, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(L.claude.chatControls.fastModeValue(fast.replaceFirstChar { it.uppercase() }), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(L.claude.chatControls.sendFastInTheChatTo, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Context window usage of the open chat (the `/context` command). */
@Composable
fun ClaudeContextDialog(backend: ClaudeChatBackend, onDismiss: () -> Unit) {
    val ctx by backend.contextUsage.collectAsState()
    LaunchedEffect(Unit) { backend.refreshContextUsage() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.claude.chatControls.contextWindow, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val c = ctx
                if (c == null) {
                    Text(L.claude.chatControls.contextUsageShowsOnceThisChat, fontSize = 13.sp)
                } else {
                    Text(L.claude.chatControls.compacttokensOfCompacttokens2TokensUsed(compactTokens(c.totalTokens), compactTokens(c.maxTokens), c.percentage.toInt()), fontSize = 13.sp)
                    UsageBar(L.claude.chatControls.used, c.percentage / 100.0, null)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    c.categories.forEach { cat ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(cat.name, fontSize = 12.5.sp)
                            Text(compactTokens(cat.tokens), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    Text(
                        L.claude.chatControls.sendCompactToSummarizeTheConversation,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { backend.refreshContextUsage() }) { Text(L.common.refresh) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.close) } }
    )
}

/** Plan limits and this chat's cost (the `/usage` command). */
@Composable
fun ClaudeUsageDialog(account: ClaudeAccountManager, backend: ClaudeChatBackend, onDismiss: () -> Unit) {
    val usage by account.usage.collectAsState()
    val status by account.status.collectAsState()
    val live by backend.rateLimits.collectAsState()
    val cost by backend.sessionCost.collectAsState()
    val selected by backend.selectedModelId.collectAsState()
    // usage and cost are shown for Claude's own models only (a provider's cost from the CLI is a guess)
    if (ClaudeChatBackend.providerOf(selected).isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(L.claude.chatControls.usage, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    L.claude.chatControls.thisChatRunsOnModellabelA(backend.modelLabel(selected)),
                    fontSize = 13.sp
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(L.common.close) } }
        )
        return
    }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        account.refreshUsage(force = true)
        delay(1_500)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(L.claude.chatControls.usage, fontWeight = FontWeight.Bold)
                val plan = (usage?.subscription_type ?: status?.auth?.subscriptionType)?.let { ai.antigem.ui.agents.claudePlanLabel(it) }
                Text(
                    listOfNotNull(L.common.claudeCode, plan?.let { "$it plan" }).joinToString(" · "),
                    fontSize = 12.sp,
                    color = ClaudeAccent
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val rl = usage?.rate_limits
                // live windows from the chat stream are fresher than the cached call
                val five = live["five_hour"]?.let { it.utilization to formatReset(null, it.resetsAt) }
                    ?: rl?.five_hour?.let { (it.utilization ?: 0.0) / 100.0 to formatReset(it.resets_at) }
                val week = live["seven_day"]?.let { it.utilization to formatReset(null, it.resetsAt) }
                    ?: rl?.seven_day?.let { (it.utilization ?: 0.0) / 100.0 to formatReset(it.resets_at) }
                when {
                    five != null || week != null -> {
                        five?.let { UsageBar(L.claude.chatControls.currentSession5Hour, it.first, it.second) }
                        week?.let { UsageBar(L.claude.chatControls.thisWeekAllModels, it.first, it.second) }
                        // a model's own weekly share (Fable on Max), on top of the all-models limit
                        ClaudeQuotaGroups.scopedLimits(rl).forEach { UsageBar("This week (${it.name})", it.used, formatReset(it.resetsAt)) }
                    }
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                        Spacer(Modifier.width(10.dp))
                        Text(L.claude.chatControls.loadingPlanUsage, fontSize = 13.sp)
                    }
                    usage != null && !usage!!.rate_limits_available ->
                        Text(L.claude.chatControls.planLimitsDoNotApplyTo, fontSize = 13.sp)
                    else -> Text(L.claude.chatControls.planUsageIsNotAvailableRight, fontSize = 13.sp)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(L.claude.chatControls.thisChat, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                    Text(if (cost > 0) String.format(java.util.Locale.US, "$%.2f", cost) else "–", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    L.claude.chatControls.costIsWhatThisChatWould,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        },
        confirmButton = { TextButton(onClick = { account.refreshUsage(force = true) }) { Text(L.common.refresh) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.close) } }
    )
}

/**
 * Asked before a chat with messages moves to another provider: the process restarts and the whole conversation is
 * sent again without cache. A new chat is the recommended way; switching anyway unlocks after a short delay.
 */
@Composable
fun ClaudeProviderSwitchDialog(backend: ClaudeChatBackend) {
    val sw by backend.pendingProviderSwitch.collectAsState()
    val pending = sw ?: return
    val running by backend.isCurrentChatActivelyRunning.collectAsState()
    var wait by remember(pending) { mutableStateOf(3) }
    LaunchedEffect(pending) {
        while (wait > 0) {
            delay(1_000)
            wait--
        }
    }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    AlertDialog(
        onDismissRequest = { backend.cancelProviderSwitch() },
        title = { Text(L.claude.chatControls.switchProviderInThisChat, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.claude.chatControls.thisChatRunsOnFromlabelMoving(pending.fromLabel, pending.toLabel), fontSize = 13.5.sp)
                Text(L.claude.chatControls.claudeCodeIsStoppedAndStarted, fontSize = 13.sp, color = muted)
                Text(
                    L.claude.chatControls.theWholeConversationValueIsSent((pending.resendTokens?.let { L.claude.chatControls.aboutCompacttokensTokens(compactTokens(it)) } ?: "")),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = QuotaRed
                )
                Text(L.claude.chatControls.thinkingEffortAndAutoModeMay, fontSize = 13.sp, color = muted)
                Text(L.claude.chatControls.startingANewChatIsUsually, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Button(
                    onClick = { backend.startNewChatWithPendingModel() },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                ) { Text(L.claude.chatControls.startANewChatWithIt) }
                TextButton(onClick = { backend.cancelProviderSwitch() }) { Text(L.common.cancel) }
                TextButton(onClick = { backend.confirmProviderSwitch() }, enabled = wait == 0) {
                    Text(
                        (if (running) L.claude.chatControls.switchAfterThisReply else L.claude.chatControls.switchAnyway) + if (wait > 0) " ($wait)" else "",
                        color = if (wait == 0) QuotaRed else QuotaRed.copy(alpha = 0.4f),
                        fontSize = 12.5.sp
                    )
                }
            }
        }
    )
}


/** In place of the input in a subagent's chat: it can be read, not continued. */
@Composable
fun ReadOnlySubagentNote(agentType: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountTree, null, tint = ClaudeAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = L.claude.chatControls.valueReadOnlyItWorkedFor((agentType?.takeIf { it.isNotBlank() }?.let { "$it subagent" } ?: L.claude.chatControls.subagent)),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
    }
}


/**
 * "2 agents" with a green dot while some of this chat's subagents work, "0 agents" with a white dot when none does.
 * Tapping lists them all; picking one opens its (read-only) chat.
 */
@Composable
private fun SubagentsChip(subagents: List<ai.antigem.domain.model.Conversation>, onOpen: (String) -> Unit) {
    val running = subagents.count { it.isRunning }
    val green = ai.antigem.theme.AppColors.success
    val color = if (running > 0) green else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { open = true },
            shape = RoundedCornerShape(14.dp),
            color = if (running > 0) green.copy(alpha = 0.14f) else MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, if (running > 0) green.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        ) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (running > 0) green else Color.White)
                        .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape)
                )
                Spacer(Modifier.width(5.dp))
                Text(if (running == 1) "1 agent" else L.claude.chatControls.runningAgents(running), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.widthIn(min = 270.dp, max = 340.dp)
        ) {
            SubagentsMenuHeader(subagents.size, running)
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(4.dp))
            subagentTree(subagents).forEach { row ->
                SubagentTreeRow(row) { open = false; onOpen(row.conv.id) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** One row of the subagents tree: the subagent, how deep it is, and how many it started itself. */
private data class SubagentTreeItem(val conv: ai.antigem.domain.model.Conversation, val depth: Int, val children: Int)

/**
 * The chat's subagents as the side bar shows them: each under the chat or subagent that started it, newest first at
 * every level.
 */
private fun subagentTree(agents: List<ai.antigem.domain.model.Conversation>): List<SubagentTreeItem> {
    val ids = agents.mapTo(HashSet()) { it.id }
    val byParent = agents.groupBy { a -> a.parentConversationId?.takeIf { it in ids } }
    val out = ArrayList<SubagentTreeItem>()
    fun walk(parent: String?, depth: Int) {
        byParent[parent].orEmpty().sortedByDescending { it.createdAt }.forEach { c ->
            out += SubagentTreeItem(c, depth, byParent[c.id]?.size ?: 0)
            walk(c.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}

@Composable
private fun SubagentsMenuHeader(total: Int, running: Int) {
    val green = ai.antigem.theme.AppColors.success
    val muted = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = ClaudeAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(L.claude.chatControls.subagents, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(6.dp))
        Text(
            "$total", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(muted.copy(alpha = 0.08f)).padding(horizontal = 7.dp, vertical = 1.dp)
        )
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (running > 0) green.copy(alpha = 0.14f) else muted.copy(alpha = 0.06f))
                .padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (running > 0) {
                Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(green))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                if (running > 0) L.claude.chatControls.runningRunning(running) else L.claude.chatControls.allDone,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = if (running > 0) green else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SubagentTreeRow(row: SubagentTreeItem, onClick: () -> Unit) {
    val a = row.conv
    val green = ai.antigem.theme.AppColors.success
    val nested = row.depth > 0
    val guide = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val name = a.subagentRole?.takeIf { it.isNotBlank() } ?: a.title.ifBlank { L.claude.chatControls.subagent }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            // the tree's guide lines: one per level above this row
            .drawBehind {
                for (i in 0 until row.depth) {
                    val x = (19 + i * 18).dp.toPx()
                    drawLine(guide, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(start = (10 + row.depth * 18).dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (nested) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else ClaudeAccent.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (nested) Icons.AutoMirrored.Filled.CallSplit else Icons.Outlined.SmartToy, null,
                    tint = if (nested) MaterialTheme.colorScheme.onSurfaceVariant else ClaudeAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (a.isRunning) Box(
                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(9.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(1.5.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(green)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                a.subagentTypeName?.takeIf { it.isNotBlank() }?.let { type ->
                    Text(
                        type, fontSize = 10.5.sp, color = ClaudeAccent, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(6.dp))
                            .background(ClaudeAccent.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (a.isRunning) L.claude.chatControls.running else L.claude.chatControls.done, fontSize = 11.sp,
                    fontWeight = if (a.isRunning) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (a.isRunning) green else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (row.children > 0) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                    Text("${row.children}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(subagentAge(a.updatedAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}

/** "now", "5m", "3h", "2d" (like the side bar). */
private fun subagentAge(ts: Long): String {
    if (ts <= 0) return ""
    val mins = (System.currentTimeMillis() - ts).coerceAtLeast(0) / 60_000
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h"
        else -> "${mins / (60 * 24)}d"
    }
}
