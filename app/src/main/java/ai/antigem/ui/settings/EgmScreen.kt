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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.egm.EgmManager
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll

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
                Text("EGM · Elephant-Goldfish", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        state.version == null -> "Not installed  ·  Review a design before any code"
                        !settings.enabled -> "${state.label}  ·  Commands off"
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
        EgmCard("What EGM does", cardBg, cardBorder) {
            Hint("Google's Elephant-Goldfish Model (open source, Apache 2.0). The agent (the Elephant) works out a design with you and writes it down. Then fresh reviewers (the Goldfish), who never saw your conversation, read only the document and try to break it. The design is fixed until it passes and you approve it; only then is code written.")
            Hint("It uses more tokens than a plain chat: every reviewer reads the document, its persona and project files. On a phone, Quick or Standard reviewers are usually enough.")
            TextButton(onClick = { egmOpenUrl(context, EgmManager.HOMEPAGE) }, contentPadding = PaddingValues(0.dp)) {
                Text("github.com/google/elephant-goldfish-model", fontSize = 12.5.sp)
            }
        }

        state.message?.let { msg ->
            Surface(shape = RoundedCornerShape(12.dp), color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = EgmManager::clearMessage) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }

        StepCard(
            1, "Install",
            when {
                state.hasUpdate -> "Update"
                else -> state.label
            },
            cardBg, cardBorder,
            badgeColor = if (state.hasUpdate) Amber else QuotaGreen
        ) {
            Hint("Downloads EGM from GitHub (the skills, the reviewer personas and the review standards) into a folder every agent on this phone can read. Pick where it comes from:")
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
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Install EGM")
                    }
                    switching -> Button(enabled = !busy, onClick = EgmManager::install) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (settings.source == EgmManager.Source.MAIN) "Install the development version" else "Install the newest release")
                    }
                    state.hasUpdate -> Button(enabled = !busy, onClick = EgmManager::install) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (state.installedSource == EgmManager.Source.MAIN) "Update to main ${state.latestCommit}" else "Update to v${state.latest}")
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = EgmManager::install) { Text("Reinstall") }
                }
                if (state.version != null && !state.hasUpdate && !switching) {
                    TextButton(enabled = !busy, onClick = { EgmManager.checkForUpdate() }) { Text("Check for update") }
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(state.progress ?: "Working…", fontSize = 12.5.sp)
                }
            }
            if (state.log.isNotEmpty()) LogBox(state.log)
            if (state.version != null) Mono(EgmManager.rootDir.absolutePath)
        }

        if (state.version == null) return@Column

        EgmCard("Slash commands", cardBg, cardBorder, pill = if (settings.enabled) ("On" to QuotaGreen) else ("Off" to Amber)) {
            EgmSwitchRow("Use the EGM commands", "Type one at the start of a message in any chat; the rest of the message is the task.", settings.enabled) { on ->
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
            Hint("In a normal chat the agent runs the reviewers with its own subagent tool. In a Jarvis chat (Jarvis tab), Jarvis manages the run: it starts every reviewer as a separate chat, shows the phases as its mission steps and asks you to approve the design.")
        }

        EgmGuideCard(cardBg, cardBorder, settings.artifactDir, liteNote = if (state.hasLite) null else liteMissingNote(state.label))

        EgmCard("Goldfish reviewers", cardBg, cardBorder, pill = "${settings.reviewers.size} of ${EgmManager.Persona.entries.size}" to MaterialTheme.colorScheme.primary) {
            Hint("Which of EGM's reviewer personas look at a design (/egm and /egm-review). Each is a separate reviewer run that costs tokens. /egm-lite always uses one reviewer.")
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
                    }
                ) {
                    Checkbox(checked = checked, enabled = canChange, onCheckedChange = null)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(p.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(p.description, fontSize = 11.5.sp, color = muted)
                    }
                }
            }
        }

        EgmCard("Review", cardBg, cardBorder) {
            EgmSwitchRow(
                "Stop after each review stage",
                "On: you see every stage's report before the next stage runs. Off: all stages run, then one combined report.",
                settings.checkpoints
            ) { on -> EgmManager.update { it.copy(checkpoints = on) } }
            EgmStepper(
                "Re-reviews after fixes",
                "How often a review stage may run again once the document is fixed. 0 = never.",
                settings.rounds, 0, EgmManager.MAX_ROUNDS
            ) { v -> EgmManager.update { it.copy(rounds = v) } }
            EgmStepper(
                "Reviewers at the same time",
                "Each running reviewer is a full agent: more at once is faster but needs more memory.",
                settings.parallel, 1, EgmManager.MAX_PARALLEL
            ) { v -> EgmManager.update { it.copy(parallel = v) } }
            EgmArtifactDirField(settings.artifactDir)
        }

        EgmCard("Jarvis", cardBg, cardBorder) {
            Hint("Who is the Elephant (writes and fixes the design) when you type an EGM command in a Jarvis chat. The reviewers are always separate chats that Jarvis starts.")
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

        EgmCard("Instructions", cardBg, cardBorder) {
            Hint("What each command tells the agent after the facts from this page (where EGM is, the reviewers, the limits). Edit it to change how the agent or Jarvis runs EGM.")
            EgmTemplateRow("Normal chats", settings.chatTemplate != EgmManager.DEFAULT_CHAT_TEMPLATE) { editTemplate = false }
            EgmTemplateRow("Jarvis chats", settings.jarvisTemplate != EgmManager.DEFAULT_JARVIS_TEMPLATE) { editTemplate = true }
            Hint("Rules for the planner and the reviewers of Jarvis runs, written by the app from this page:")
            Mono(EgmManager.adapterDir.absolutePath)
        }

        EgmCard("Remove", cardBg, cardBorder) {
            Hint("Deletes EGM and its rules files from the phone. Design documents and reports in your projects stay.")
            OutlinedButton(
                enabled = !busy,
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Remove EGM")
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove EGM?", fontWeight = FontWeight.Bold) },
            text = { Text("The /egm commands stop working. Your design documents and reports stay in your projects.", fontSize = 13.5.sp) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; EgmManager.uninstall() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
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
                Text("How to use EGM", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Hide the guide" else "Show the guide")
            }
            if (!open) {
                Hint("Which command to use when, with examples, and what happens in a normal chat and in a Jarvis chat.")
                return@Column
            }

            EgmGuideHeading("Which command?")
            EgmGuideCommand(
                "/egm <task>",
                "For a new feature or a big change, where a wrong design costs a lot: several files, data formats, APIs. Uses the most tokens.",
                "1. The agent asks you questions until the idea is clear (brainstorm).\n" +
                    "2. It writes the design document, section by section, and asks for your OK on each.\n" +
                    "3. The reviewers attack the document; the agent fixes it or explains why a finding is wrong.\n" +
                    "4. You approve the design.\n" +
                    "5. Only then does it write the code.",
                "/egm add offline sync for notes, with conflict handling"
            )
            EgmGuideCommand(
                "/egm-lite <task>",
                "For small changes: a bug fix, a refactor, a small feature, a change to a spec. Much cheaper than /egm.",
                "1. The agent first points out risks and gaps in the task, then waits.\n" +
                    "2. Type checklist: it writes a short plan (egm_lite_checklist.md).\n" +
                    "3. Type goldfish: one fresh reviewer checks the plan (you can skip this).\n" +
                    "4. Type exec: it makes the change.\n" +
                    "5. Type review: one fresh reviewer checks the changes.",
                "/egm-lite fix the crash when the chat list is empty",
                note = liteNote
            )
            EgmGuideCommand(
                "/egm-review <doc path>",
                "When a plan or design document already exists (yours, or written in another chat) and you want it checked before anyone builds it.",
                "The reviewers check the document and the agent writes their findings into a report. Then you choose: the agent fixes the findings, you fix them yourself, or you stop there.",
                "/egm-review docs/sync-design.md"
            )

            EgmGuideHeading("Normal chat or Jarvis chat?")
            Hint("Normal chat (Claude Code, Codex, …): the agent does everything in that chat and runs the reviewers as its own hidden sub-agents. The simplest and lightest way. If the agent has no sub-agent tool, it stops and tells you to use a Jarvis chat.")
            Hint("Jarvis chat (Jarvis tab → a Jarvis chat, then type the command): Jarvis manages the run. It starts a planner chat (or plans itself, see Jarvis below) and one chat per reviewer, which you can open from the chat list. The phases show as Jarvis' mission steps, and Jarvis asks you to approve the design. Use it to watch the reviewers, or when the agent has no sub-agent tool. Every reviewer chat is a full agent, so it needs more memory.")

            EgmGuideHeading("During a run")
            Hint(
                "• Answer the agent's questions: it asks one at a time.\n" +
                    "• Everything is saved as files in your project, in $artifactDir/<topic>/: the design document and every review report. Open them in Files.\n" +
                    "• Nothing is coded before you approve. To approve, just say so; or ask for changes, or for one more review round.\n" +
                    "• You can stop at any time. The files stay, and later you can check the document again with /egm-review."
            )

            EgmGuideHeading("Tips")
            Hint(
                "• Start with Quick or Standard reviewers; Full runs eight reviewers in every round.\n" +
                    "• If the phone gets slow, set Reviewers at the same time to 1.\n" +
                    "• Write the task as you would explain it to a colleague: what, why, and any limits (\"no new libraries\").\n" +
                    "• For a change of a few lines, a plain chat is cheaper than EGM."
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
        Text("Example", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Mono(example)
    }
}

@Composable
private fun EgmSwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EgmStepper(title: String, text: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        IconButton(enabled = value > min, onClick = { onChange(value - 1) }) { Icon(Icons.Outlined.Remove, "Less", Modifier.size(18.dp)) }
        Text("$value", fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 20.dp))
        IconButton(enabled = value < max, onClick = { onChange(value + 1) }) { Icon(Icons.Outlined.Add, "More", Modifier.size(18.dp)) }
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
                label = { Text("Folder for designs and reports") },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = dir.trim().trimEnd('/') != saved && dir.isNotBlank(), onClick = { EgmManager.update { it.copy(artifactDir = dir) } }) {
                Text("Save")
            }
        }
        Hint("In the project folder; each topic gets its own folder inside. You may want to add it to .gitignore.")
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
            Pill(if (changed) "Changed" else "Default", if (changed) Amber else QuotaGreen)
        }
    }
}

@Composable
private fun EgmTemplateDialog(jarvis: Boolean, current: String, onDismiss: () -> Unit) {
    var text by remember(jarvis) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (jarvis) "Instructions for Jarvis chats" else "Instructions for normal chats", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    if (jarvis) "Sent after the run's facts (EGM's folder, the planner and reviewer goals, the limits). It tells Jarvis how to manage the planner and the reviewer chats."
                    else "Sent after the run's facts (EGM's folder, the reviewers, the limits). It tells the agent how to run the reviewers in its own chat."
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
                ) { Text("Reset to default") }
            }
        },
        confirmButton = {
            Button(onClick = {
                EgmManager.update { if (jarvis) it.copy(jarvisTemplate = text) else it.copy(chatTemplate = text) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Why /egm-lite is off: the installed EGM ([label], e.g. v0.1.1) has no egm-lite skill (releases up to 0.1.1). */
private fun liteMissingNote(label: String?): String =
    "Not available: EGM${label?.let { " $it" }.orEmpty()} has no egm-lite. Install the development version (step 1) to get it now; a later EGM release will include it too."

private fun egmOpenUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
