package ai.antigem.ui.jarvis

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.jarvis.JarvisEvents
import ai.antigem.data.jarvis.JarvisManagers
import ai.antigem.data.jarvis.JarvisTasks
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

private fun agentLabel(name: String) = when (name.uppercase()) {
    "AGY" -> L.common.antigravity
    "OPENCODE" -> "OpenCode"
    "CODEX" -> L.common.codex
    "CLAUDE" -> L.common.claude
    else -> name
}

private fun age(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return if (m < 1) "now" else if (m < 60) "${m}m" else L.jarvis.commandCenter.valueHValue2M(m / 60, m % 60)
}

/**
 * The command strip above the input of a Jarvis chat: the mission and its progress, what Jarvis is doing, its teams'
 * running tasks (tap one to open its chat), and the switches the user controls (voice replies, autopilot). Tap the head
 * for the full board: every step, team and task.
 */
@Composable
fun JarvisCommandStrip(chatId: String, onOpenChat: (String) -> Unit, toolsMissing: Boolean = false) {
    val managers by JarvisManagers.managers.collectAsState()
    val statuses by JarvisManagers.status.collectAsState()
    val tasks by JarvisTasks.tasks.collectAsState()
    val voiceSide by ai.antigem.data.jarvis.JarvisPreferences.voiceAssistant.state.collectAsState()
    val key = remember(chatId, managers) { JarvisManagers.keyForChat(chatId) } ?: return
    val m = managers[key] ?: JarvisManagers.Manager(key)
    val voice = key == JarvisManagers.VOICE
    val mine = tasks.filter { JarvisManagers.ownerOrVoice(it.owner) == key }
    val active = mine.filter { it.active }
    val mission = m.mission
    val status = statuses[key]
    var expanded by remember(chatId) { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = Cyan

    val title = when {
        !mission.isEmpty -> mission.goal
        voice -> L.jarvis.commandCenter.jarvisVoiceChat
        else -> L.jarvis.commandCenter.jarvisCommandCenter
    }
    val subtitle = when {
        status != null -> status
        active.any { it.state.needsUser } -> L.jarvis.commandCenter.aTaskNeedsADecision
        active.isNotEmpty() -> L.jarvis.commandCenter.activecountTaskValueWorkingNewsComes(active.size, if (active.size == 1) "" else "s")
        mission.status == "done" -> L.jarvis.commandCenter.missionComplete
        mission.status == "paused" -> L.jarvis.commandCenter.missionPaused
        mission.isEmpty -> L.jarvis.commandCenter.giveMeABigJobI
        mission.autopilot -> L.jarvis.commandCenter.autopilotOnKeepsGoingUntilDone
        else -> L.jarvis.commandCenter.waitingForYou
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(16.dp))
            .animateContentSize()
    ) {
        Row(
            Modifier.fillMaxWidth()
                .semantics { stateDescription = if (expanded) L.components.a11y.expanded else L.components.a11y.collapsed }
                .clickable(role = Role.Button) { expanded = !expanded }.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            JarvisBotIcon(28.dp, accent, animated = active.isNotEmpty() || status != null)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (mission.steps.isNotEmpty()) {
                Text("${mission.doneCount}/${mission.steps.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
                Spacer(Modifier.width(2.dp))
            }
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (expanded) L.jarvis.commandCenter.hideTheBoard else L.jarvis.commandCenter.showTheBoard, tint = onSurface.copy(alpha = 0.6f))
        }
        if (mission.steps.isNotEmpty()) {
            val p = mission.doneCount.toFloat() / mission.steps.size
            Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(onSurface.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (mission.status == "done") Green else accent))
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 8.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "chat only" (Settings → Jarvis): nothing is ever said, so no Voice switch
            if (!voice && voiceSide) {
                ToggleChip(if (m.speak) L.jarvis.commandCenter.voiceOn else L.jarvis.commandCenter.voiceOff, if (m.speak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, m.speak) {
                    JarvisManagers.setSpeak(key, !m.speak)
                }
            }
            if (!mission.isEmpty) {
                ToggleChip(if (mission.autopilot) L.jarvis.commandCenter.autopilotOn else L.jarvis.commandCenter.autopilot, Icons.Filled.Autorenew, mission.autopilot) {
                    JarvisManagers.setAutopilot(key, !mission.autopilot)
                }
            }
            active.forEach { t -> TaskPill(t) { onOpenChat(t.id) } }
            if (active.isEmpty() && mine.isNotEmpty()) {
                Text(L.jarvis.commandCenter.minecountFinishedTaskValue(mine.size, if (mine.size == 1) "" else "s"), fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f))
            }
        }
        if (toolsMissing) {
            // the chat's agent answered that it has no jarvis_mcp tools
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Amber.copy(alpha = 0.12f))
                    .clickable(role = Role.Button) { ai.antigem.data.jarvis.JarvisController.requestSettings("JARVIS_SETUP") }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    L.jarvis.commandCenter.thisChatsAgentHasNoJarvis,
                    fontSize = 11.5.sp, color = onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f)
                )
            }
        }
        if (expanded) Board(key, m, mine, onOpenChat)
    }
}

@Composable
private fun ToggleChip(label: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    val c = if (on) Cyan else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Cyan.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, c.copy(alpha = if (on) 0.6f else 0.3f), RoundedCornerShape(50))
            .toggleable(value = on, role = Role.Switch) { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = c, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (on) MaterialTheme.colorScheme.onSurface else c)
    }
}

@Composable
private fun TaskPill(t: JarvisTasks.Task, onClick: () -> Unit) {
    val c = taskColor(t.state)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.4f), RoundedCornerShape(50))
            // the dot's colour is the task's state
            .semantics { stateDescription = taskStateSpoken(t.state) }
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulseDot(c, working = t.state == JarvisTasks.State.RUNNING || t.state == JarvisTasks.State.STARTING)
        Spacer(Modifier.width(3.dp))
        Text(
            (if (t.team.isNotBlank()) "${t.team} · " else "") + t.title.take(22),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), maxLines = 1
        )
    }
}

/** Everything of one manager: mission steps, teams, tasks. */
@Composable
private fun Board(key: String, m: JarvisManagers.Manager, mine: List<JarvisTasks.Task>, onOpenChat: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
    ) {
        val mi = m.mission
        BoardTitle(L.jarvis.commandCenter.mission)
        if (mi.isEmpty) {
            Text(
                L.jarvis.commandCenter.noMissionYetAskForSomething,
                fontSize = 12.sp, color = onSurface.copy(alpha = 0.6f), lineHeight = 17.sp
            )
        } else {
            if (mi.doneWhen.isNotBlank()) Text(L.jarvis.commandCenter.doneWhenDonewhen(mi.doneWhen), fontSize = 12.sp, color = onSurface.copy(alpha = 0.7f), lineHeight = 16.sp)
            mi.steps.forEach { s ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    val (mark, c) = when (s.status) {
                        "done" -> "✓" to Green
                        "doing" -> "▶" to Cyan
                        "blocked" -> "!" to Danger
                        else -> "○" to onSurface.copy(alpha = 0.4f)
                    }
                    Text(
                        mark, fontSize = 12.sp, color = c, fontWeight = FontWeight.Bold,
                        // a word, not the symbol's name
                        modifier = Modifier.width(16.dp).semantics {
                            contentDescription = when (s.status) {
                                "done" -> L.common.done
                                "doing" -> L.jarvis.commandCenter.stepInProgress
                                "blocked" -> L.jarvis.commandCenter.stepBlocked
                                else -> L.jarvis.commandCenter.stepToDo
                            }
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${s.n}. ${s.text}", fontSize = 12.5.sp, color = onSurface.copy(alpha = if (s.status == "done") 0.55f else 0.9f), lineHeight = 17.sp)
                        if (s.note.isNotBlank()) Text(s.note, fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f), lineHeight = 15.sp)
                    }
                }
            }
            if (mi.notes.isNotBlank()) Text(L.jarvis.commandCenter.notesNotes(mi.notes), fontSize = 11.5.sp, color = onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(L.jarvis.commandCenter.clearMission, Danger) { JarvisManagers.clearMission(key) }
                if (mi.status != "active") SmallAction(L.jarvis.commandCenter.resume, Cyan) { JarvisManagers.updateMission(key, null, null, null, emptyList(), "active", null, null) }
                else SmallAction(L.common.pause, Amber) { JarvisManagers.updateMission(key, null, null, null, emptyList(), "paused", null, null) }
            }
        }
        if (m.teams.isNotEmpty()) {
            BoardTitle(L.jarvis.commandCenter.teams)
            m.teams.forEach { t ->
                val running = mine.count { it.active && it.team.equals(t.name, true) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${t.name} · ${agentLabel(t.agent.name)}${if (t.model.isNotBlank()) " · ${t.model}" else ""}",
                            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = onSurface
                        )
                        if (t.role.isNotBlank()) Text(t.role, fontSize = 11.sp, color = onSurface.copy(alpha = 0.55f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (running > 0) Text(L.jarvis.commandCenter.runningWorking(running), fontSize = 11.sp, color = Violet)
                    IconMini(Icons.Filled.Close, L.jarvis.commandCenter.removeTeam, onSurface.copy(alpha = 0.45f)) { JarvisManagers.removeTeam(key, t.name) }
                }
            }
        }
        BoardTitle(L.jarvis.commandCenter.tasks)
        if (mine.isEmpty()) Text(L.jarvis.commandCenter.noneYet, fontSize = 12.sp, color = onSurface.copy(alpha = 0.5f))
        mine.take(20).forEach { t ->
            val c = taskColor(t.state)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onOpenChat(t.id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, fontSize = 12.5.sp, color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(t.team.takeIf { it.isNotBlank() }, agentLabel(t.agent.name), t.state.label, age(t.createdAt), t.note.takeIf { it.isNotBlank() && it != "Working" }).joinToString(" · "),
                        fontSize = 10.5.sp, color = c.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconMini(Icons.AutoMirrored.Filled.OpenInNew, L.common.open, onSurface.copy(alpha = 0.6f)) { onOpenChat(t.id) }
                if (t.active) IconMini(Icons.Filled.Stop, L.common.stop, Danger) { scope.launch { JarvisTasks.stop(t.id) } }
                else IconMini(Icons.Filled.Close, L.common.dismiss, onSurface.copy(alpha = 0.45f)) { JarvisTasks.close(t.id) }
            }
        }
    }
}

@Composable
private fun BoardTitle(text: String) {
    Text(
        text.uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, color = Cyan.copy(alpha = 0.9f),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() }
    )
}

@Composable
private fun SmallAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50)).clickable(role = Role.Button) { onClick() }.padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun IconMini(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(CircleShape).clickable(role = Role.Button) { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(16.dp))
    }
}

// ------------------------------------------------------------------------------------------------ the event card

private fun eventColor(kind: String): Color = when (kind) {
    "task_done" -> Green
    "task_failed" -> Danger
    "task_stalled" -> Color(0xFFF97316)
    "task_approval", "task_question", "autopilot_paused" -> Amber
    "nudge" -> Violet
    else -> Cyan
}

private fun eventWord(kind: String): String = when (kind) {
    "task_done" -> L.jarvis.commandCenter.finished
    "task_failed" -> L.common.failed
    "task_stalled" -> L.jarvis.commandCenter.noProgress
    "task_approval" -> L.jarvis.commandCenter.needsApproval
    "task_question" -> L.jarvis.commandCenter.asksAQuestion
    "nudge" -> L.jarvis.commandCenter.autopilotKeepGoing
    "autopilot_paused" -> L.jarvis.commandCenter.autopilotPaused
    else -> kind
}

/**
 * The news Jarvis got from its teams, as it shows in the chat: one row per event (who, what happened), and on tap what
 * each sub-agent reported. The instructions that went to the model with it are never shown.
 */
@Composable
fun JarvisEventCard(raw: String, onOpenChat: (String) -> Unit) {
    val events = remember(raw) { JarvisEvents.parse(raw).orEmpty() }
    if (events.isEmpty()) return
    var open by remember(raw) { mutableStateOf(false) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val hasDetails = events.any { it.text.isNotBlank() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Cyan.copy(alpha = 0.05f))
            .border(1.dp, Cyan.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .semantics { if (hasDetails) stateDescription = if (open) L.components.a11y.expanded else L.components.a11y.collapsed }
            .clickable(enabled = hasDetails, role = if (hasDetails) Role.Button else null) { open = !open }
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JarvisBotIcon(18.dp, Cyan)
            Spacer(Modifier.width(7.dp))
            Text(
                if (events.size == 1) L.jarvis.commandCenter.teamUpdate else L.jarvis.commandCenter.eventscountTeamUpdates(events.size),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Cyan, modifier = Modifier.weight(1f)
            )
            if (hasDetails) Text(if (open) L.jarvis.commandCenter.hideReports else L.jarvis.commandCenter.showReports, fontSize = 11.sp, color = onSurface.copy(alpha = 0.5f))
        }
        events.forEach { e ->
            val c = eventColor(e.kind)
            Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        e.title.ifBlank { L.jarvis.commandCenter.mission } + listOfNotNull(e.team.takeIf { it.isNotBlank() }, e.agent.takeIf { it.isNotBlank() }?.let { agentLabel(it) })
                            .joinToString(" · ").let { if (it.isBlank()) "" else "  ·  $it" },
                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(eventWord(e.kind), fontSize = 11.sp, color = c)
                    if (open && e.text.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                e.text.take(4000), fontSize = 12.sp, lineHeight = 17.sp, color = onSurface.copy(alpha = 0.78f),
                                modifier = Modifier.padding(top = 4.dp).widthIn(max = 640.dp)
                            )
                        }
                    }
                }
                if (e.taskId.isNotBlank() && JarvisTasks.find(e.taskId) != null) {
                    IconMini(Icons.AutoMirrored.Filled.OpenInNew, L.jarvis.commandCenter.openTheTasksChat, onSurface.copy(alpha = 0.55f)) { onOpenChat(e.taskId) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ a fresh Jarvis chat

/** What a new Jarvis text chat shows before the first message: what it does, and a few starters (tap one to edit it). */
@Composable
fun JarvisChatWelcome(onExample: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
        JarvisBotBadge(72.dp, Cyan, animated = true)
        Spacer(Modifier.height(14.dp))
        Text(L.jarvis.commandCenter.jarvisCommandCenter, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = onSurface, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(
            L.jarvis.commandCenter.giveMeABigJobI2,
            fontSize = 13.5.sp, lineHeight = 19.sp, color = onSurface.copy(alpha = 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            L.jarvis.commandCenter.buildADarkModeToggleIn,
            L.jarvis.commandCenter.createTwoTeamsBuildersClaudeTo,
            L.jarvis.commandCenter.investigateWhyTheAppIsSlow
        ).forEach { ex ->
            Text(
                ex, fontSize = 12.5.sp, lineHeight = 17.sp, color = onSurface.copy(alpha = 0.85f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Cyan.copy(alpha = 0.06f))
                    .border(1.dp, Cyan.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { onExample(ex) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
