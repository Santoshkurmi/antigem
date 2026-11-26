package ai.antigem.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.egm.EgmManager
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.i18n.L

/** The EGM row of Settings → Agents, with what is installed. */
@Composable
fun EgmEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { EgmManager.init(context) }
    val state by EgmManager.state.collectAsState()
    val settings by EgmManager.settings.collectAsState()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.tertiary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(L.settings.egm.egmElephantGoldfish, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        state.version == null -> L.settings.egm.notInstalledReviewADesignBefore
                        !settings.enabled -> L.settings.egm.labelCommandsOff(state.label)
                        else -> "${state.label}  ·  " + EgmManager.Command.entries.filter { EgmManager.available(it, state) }.joinToString { "/${it.token}" }
                    },
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings → Agents → EGM: installs Google's Elephant-Goldfish Model ([EgmManager]) and sets how its slash commands
 * run: which reviewers, how many at once, re-reviews, where the documents go, who the Elephant is in a Jarvis chat,
 * and the instructions each command sends.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EgmScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        EgmManager.init(context)
        EgmManager.refresh()
        EgmManager.checkForUpdate()
    }
    val state by EgmManager.state.collectAsState()
    val settings by EgmManager.settings.collectAsState()
    val busy = state.busy
    var confirmRemove by remember { mutableStateOf(false) }
    // true: the Jarvis instructions, false: the normal-chat ones
    var editTemplate by remember { mutableStateOf<Boolean?>(null) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        EgmCard(L.settings.egm.whatEgmDoes, cardBg, cardBorder) {
            Hint(L.settings.egm.googlesElephantGoldfishModelOpenSource)
            Hint(L.settings.egm.itUsesMoreTokensThanA)
            TextButton(onClick = { egmOpenUrl(context, EgmManager.HOMEPAGE) }, contentPadding = PaddingValues(0.dp)) {
                Text("github.com/google/elephant-goldfish-model", fontSize = 12.5.sp)
            }
        }

        state.message?.let { msg ->
            Surface(shape = RoundedCornerShape(12.dp), color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = EgmManager::clearMessage) { Icon(Icons.Default.Close, L.common.dismiss, Modifier.size(18.dp)) }
                }
            }
        }

        StepCard(
            1, L.common.install,
            when {
                state.hasUpdate -> L.common.update
                else -> state.label
            },
            cardBg, cardBorder,
            badgeColor = if (state.hasUpdate) Amber else QuotaGreen
        ) {
            Hint(L.settings.egm.downloadsEgmFromGithubTheSkills)
            EgmManager.Source.entries.forEach { src ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        if (settings.source != src) {
                            EgmManager.update { it.copy(source = src) }
                            EgmManager.checkForUpdate()
                        }
                    }
                ) {
                    RadioButton(selected = settings.source == src, onClick = null, enabled = !busy, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(src.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(src.description, fontSize = 11.5.sp, color = muted)
                    }
                }
            }
            // installed from the other source: one tap installs from the chosen one instead
            val switching = state.version != null && state.installedSource != settings.source
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    state.version == null -> Button(enabled = !busy, onClick = EgmManager::install) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.egm.installEgm)
                    }
                    switching -> Button(enabled = !busy, onClick = EgmManager::install) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (settings.source == EgmManager.Source.MAIN) L.settings.egm.installTheDevelopmentVersion else L.settings.egm.installTheNewestRelease)
                    }
                    state.hasUpdate -> Button(enabled = !busy, onClick = EgmManager::install) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (state.installedSource == EgmManager.Source.MAIN) L.settings.egm.updateToMainLatestcommit(state.latestCommit) else L.settings.egm.updateToVLatest(state.latest))
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = EgmManager::install) { Text(L.common.reinstall) }
                }
                if (state.version != null && !state.hasUpdate && !switching) {
                    TextButton(enabled = !busy, onClick = { EgmManager.checkForUpdate() }) { Text(L.common.checkForUpdate) }
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(state.progress ?: L.common.working, fontSize = 12.5.sp)
                }
            }
            if (state.log.isNotEmpty()) LogBox(state.log)
            if (state.version != null) Mono(EgmManager.rootDir.absolutePath)
        }

        if (state.version == null) return@Column

        EgmCard(L.settings.egm.slashCommands, cardBg, cardBorder) {
            EgmSwitchRow(L.settings.egm.useTheEgmCommands, L.settings.egm.typeOneAtTheStartOf, settings.enabled) { on ->
                EgmManager.update { it.copy(enabled = on) }
            }
            EgmManager.Command.entries.forEach { c ->
                val available = EgmManager.available(c, state)
                Column {
                    Text(
                        "/${c.token} <${c.argumentHint}>", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                        color = if (available) Color.Unspecified else muted
                    )
                    Hint(c.description)
                    if (!available) Text(liteMissingNote(state.label), fontSize = 11.5.sp, color = Amber)
                }
            }
            Hint(L.settings.egm.inANormalChatTheAgent)
        }

        EgmGuideCard(cardBg, cardBorder, settings.artifactDir, liteNote = if (state.hasLite) null else liteMissingNote(state.label))

        EgmCard(L.settings.egm.goldfishReviewers, cardBg, cardBorder, pill = "${settings.reviewers.size} of ${EgmManager.Persona.entries.size}" to MaterialTheme.colorScheme.primary) {
            Hint(L.settings.egm.whichOfEgmsReviewerPersonasLook)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EgmManager.Preset.entries.forEach { p ->
                    FilterChip(
                        selected = settings.reviewers == p.reviewers,
                        onClick = { EgmManager.update { it.copy(reviewers = p.reviewers) } },
                        label = { Text(p.label) }
                    )
                }
            }
            EgmManager.Persona.entries.forEach { p ->
                val checked = p in settings.reviewers
                // the last reviewer stays: a review needs at least one
                val canChange = !(checked && settings.reviewers.size == 1)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = canChange) {
                        EgmManager.update { s -> s.copy(reviewers = if (checked) s.reviewers - p else s.reviewers + p) }
                    }.semantics { role = Role.Checkbox; toggleableState = ToggleableState(checked) }
                ) {
                    Checkbox(checked = checked, enabled = canChange, onCheckedChange = null, modifier = Modifier.mergedIntoRow())
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(p.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(p.description, fontSize = 11.5.sp, color = muted)
                    }
                }
            }
        }

        EgmCard(L.settings.egm.review, cardBg, cardBorder) {
            EgmSwitchRow(
                L.settings.egm.stopAfterEachReviewStage,
                L.settings.egm.onYouSeeEveryStagesReport,
                settings.checkpoints
            ) { on -> EgmManager.update { it.copy(checkpoints = on) } }
            EgmStepper(
                L.settings.egm.reReviewsAfterFixes,
                L.settings.egm.howOftenAReviewStageMay,
                settings.rounds, 0, EgmManager.MAX_ROUNDS
            ) { v -> EgmManager.update { it.copy(rounds = v) } }
            EgmStepper(
                L.settings.egm.reviewersAtTheSameTime,
                L.settings.egm.eachRunningReviewerIsAFull,
                settings.parallel, 1, EgmManager.MAX_PARALLEL
            ) { v -> EgmManager.update { it.copy(parallel = v) } }
            EgmArtifactDirField(settings.artifactDir)
        }

        EgmCard(L.common.jarvis, cardBg, cardBorder) {
            Hint(L.settings.egm.whoIsTheElephantWritesAnd)
            EgmManager.Elephant.entries.forEach { e ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth().clickable { EgmManager.update { it.copy(elephant = e) } }
                ) {
                    RadioButton(selected = settings.elephant == e, onClick = null, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(e.description, fontSize = 11.5.sp, color = muted)
                    }
                }
            }
        }

        EgmCard(L.settings.egm.instructions, cardBg, cardBorder) {
            Hint(L.settings.egm.whatEachCommandTellsTheAgent)
            EgmTemplateRow(L.settings.egm.normalChats, settings.chatTemplate != EgmManager.DEFAULT_CHAT_TEMPLATE) { editTemplate = false }
            EgmTemplateRow(L.settings.egm.jarvisChats, settings.jarvisTemplate != EgmManager.DEFAULT_JARVIS_TEMPLATE) { editTemplate = true }
            Hint(L.settings.egm.rulesForThePlannerAndThe)
            Mono(EgmManager.adapterDir.absolutePath)
        }

        EgmCard(L.common.remove, cardBg, cardBorder) {
            Hint(L.settings.egm.deletesEgmAndItsRulesFiles)
            OutlinedButton(
                enabled = !busy,
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.egm.removeEgm)
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(L.settings.egm.removeEgm2, fontWeight = FontWeight.Bold) },
            text = { Text(L.settings.egm.theEgmCommandsStopWorkingYour, fontSize = 13.5.sp) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; EgmManager.uninstall() }) {
                    Text(L.common.remove, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(L.common.cancel) } }
        )
    }

    editTemplate?.let { jarvis ->
        EgmTemplateDialog(jarvis, if (jarvis) settings.jarvisTemplate else settings.chatTemplate) { editTemplate = null }
    }
}

@Composable
private fun EgmCard(
    title: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    pill: Pair<String, Color>? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                pill?.let { Pill(it.first, it.second) }
            }
            content()
        }
    }
}

/** How to use the commands: which one when, an example of each, normal chat vs Jarvis chat, a run's course, tips. Folds up. */
@Composable
private fun EgmGuideCard(cardBg: Color, cardBorder: BorderStroke, artifactDir: String, liteNote: String?) {
    var open by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(L.settings.egm.howToUseEgm, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) L.settings.egm.hideTheGuide else L.settings.egm.showTheGuide)
            }
            if (!open) {
                Hint(L.settings.egm.whichCommandToUseWhenWith)
                return@Column
            }

            EgmGuideHeading(L.settings.egm.whichCommand)
            EgmGuideCommand(
                "/egm <task>",
                L.settings.egm.forANewFeatureOrA,
                L.settings.egm.n1TheAgentAsksYouQuestions,
                "/egm add offline sync for notes, with conflict handling"
            )
            EgmGuideCommand(
                "/egm-lite <task>",
                L.settings.egm.forSmallChangesABugFix,
                L.settings.egm.n1TheAgentFirstPointsOut,
                "/egm-lite fix the crash when the chat list is empty",
                note = liteNote
            )
            EgmGuideCommand(
                "/egm-review <doc path>",
                L.settings.egm.whenAPlanOrDesignDocument,
                L.settings.egm.theReviewersCheckTheDocumentAnd,
                "/egm-review docs/sync-design.md"
            )

            EgmGuideHeading(L.settings.egm.normalChatOrJarvisChat)
            Hint(L.settings.egm.normalChatClaudeCodeCodexThe)
            Hint(L.settings.egm.jarvisChatJarvisTabAJarvis)

            EgmGuideHeading(L.settings.egm.duringARun)
            Hint(
                L.settings.egm.answerTheAgentsQuestionsItAsks(artifactDir)
            )

            EgmGuideHeading(L.settings.egm.tips)
            Hint(
                L.settings.egm.startWithQuickOrStandardReviewers
            )
        }
    }
}

@Composable
private fun EgmGuideHeading(text: String) {
    Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EgmGuideCommand(command: String, whenToUse: String, steps: String, example: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(command, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        note?.let { Text(it, fontSize = 11.5.sp, color = Amber) }
        Hint(whenToUse)
        Hint(steps)
        Text(L.settings.egm.example, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Mono(example)
    }
}

@Composable
private fun EgmSwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }
            .semantics { role = Role.Switch; toggleableState = ToggleableState(checked) }
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.mergedIntoRow())
    }
}

@Composable
private fun EgmStepper(title: String, text: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        IconButton(enabled = value > min, onClick = { onChange(value - 1) }) { Icon(Icons.Outlined.Remove, L.settings.egm.less, Modifier.size(18.dp)) }
        Text("$value", fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 20.dp))
        IconButton(enabled = value < max, onClick = { onChange(value + 1) }) { Icon(Icons.Outlined.Add, L.common.more, Modifier.size(18.dp)) }
    }
}

@Composable
private fun EgmArtifactDirField(saved: String) {
    var dir by remember(saved) { mutableStateOf(saved) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = dir,
                onValueChange = { dir = it },
                label = { Text(L.settings.egm.folderForDesignsAndReports) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = dir.trim().trimEnd('/') != saved && dir.isNotBlank(), onClick = { EgmManager.update { it.copy(artifactDir = dir) } }) {
                Text(L.common.save)
            }
        }
        Hint(L.settings.egm.inTheProjectFolderEachTopic)
    }
}

@Composable
private fun EgmTemplateRow(title: String, changed: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Pill(if (changed) L.settings.egm.changed else L.common.default, if (changed) Amber else QuotaGreen)
        }
    }
}

@Composable
private fun EgmTemplateDialog(jarvis: Boolean, current: String, onDismiss: () -> Unit) {
    var text by remember(jarvis) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (jarvis) L.settings.egm.instructionsForJarvisChats else L.settings.egm.instructionsForNormalChats, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    if (jarvis) L.settings.egm.sentAfterTheRunsFactsEgms
                    else L.settings.egm.sentAfterTheRunsFactsEgms2
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp)
                )
                TextButton(
                    onClick = { text = if (jarvis) EgmManager.DEFAULT_JARVIS_TEMPLATE else EgmManager.DEFAULT_CHAT_TEMPLATE },
                    contentPadding = PaddingValues(0.dp)
                ) { Text(L.settings.egm.resetToDefault) }
            }
        },
        confirmButton = {
            Button(onClick = {
                EgmManager.update { if (jarvis) it.copy(jarvisTemplate = text) else it.copy(chatTemplate = text) }
                onDismiss()
            }) { Text(L.common.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/** Why /egm-lite is off: the installed EGM ([label], e.g. v0.1.1) has no egm-lite skill (releases up to 0.1.1). */
private fun liteMissingNote(label: String?): String =
    L.settings.egm.notAvailableEgmValueHasNo(label?.let { " $it" }.orEmpty())

private fun egmOpenUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
