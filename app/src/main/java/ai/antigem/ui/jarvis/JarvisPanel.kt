package ai.antigem.ui.jarvis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.jarvis.JarvisController
import ai.antigem.data.jarvis.JarvisController.Phase
import ai.antigem.data.jarvis.JarvisManagers
import ai.antigem.data.jarvis.JarvisPreferences
import ai.antigem.data.jarvis.JarvisTasks
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * The Jarvis panel (long-press the orb): the quick switches the user pinned (the tune icon opens all quick settings),
 * the voice chat's tasks with open / stop / dismiss, the whole voice conversation (kept across sessions and restarts,
 * with a divider where each new Jarvis chat began, and a way to open the real chat), a box to type to Jarvis, and
 * shortcuts. [floating]: it fills its own window over other apps (the floating orb's panel): no dimmed backdrop.
 */
@Composable
fun JarvisPanel(onTalk: () -> Unit, onNoticeAction: (String) -> Unit, floating: Boolean = false) {
    val close = { JarvisController.panelOpen.value = false }
    BackHandler(onBack = close)
    val scope = rememberCoroutineScope()
    val phase by JarvisController.phase.collectAsState()
    val log by JarvisController.log.collectAsState()
    val tasks by JarvisTasks.tasks.collectAsState()
    val notice by JarvisController.notice.collectAsState()
    val steps by JarvisController.steps.collectAsState()
    val agent by JarvisPreferences.agent.state.collectAsState()
    val pinsRaw by JarvisPreferences.quickPins.state.collectAsState()
    val voiceChat by JarvisPreferences.currentChatId.state.collectAsState()
    val pins = remember(pinsRaw) { QuickSwitch.pinned(pinsRaw) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // the voice chat's tasks (the Jarvis text chats show theirs on their own strip)
    val shownTasks = tasks.filter { JarvisManagers.ownerOrVoice(it.owner) == JarvisManagers.VOICE }.take(12)
    // the list ends with the newest line: open there, and follow new lines
    val itemCount = (if (shownTasks.isNotEmpty()) 1 + shownTasks.size else 0) + 1 + log.size + 1
    LaunchedEffect(log.size) { listState.scrollToItem(itemCount - 1) }

    Box(
        if (floating) Modifier.fillMaxSize()
        else Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            // tap outside closes (not a clickable: that would merge the sheet into one TalkBack item; Close is in the header)
            .pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .fillMaxHeight(if (floating) 1f else 0.88f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFA111A2C), Color(0xFC070B14))))
                .border(1.dp, Brush.verticalGradient(listOf(Cyan.copy(alpha = 0.5f), Color.Transparent)), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                // taps inside the sheet stay inside (not a clickable: that would make the whole sheet one TalkBack item)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // handle
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)))
            Spacer(Modifier.height(12.dp))

            // ---- header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    if (phase == Phase.IDLE || phase == Phase.OFF) IdleOrb(42.dp, shownTasks.count { it.active }, false) else JarvisOrb(phase, 0.3f, 42.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.common.jarvis, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    val sub = when (phase) {
                        Phase.LISTENING -> L.common.listening
                        Phase.THINKING -> steps.lastOrNull()?.text ?: L.jarvis.panel.working
                        Phase.SPEAKING -> L.jarvis.panel.speaking
                        else -> when (agent) {
                            "AGY" -> L.jarvis.panel.readyRunsOnAntigravity
                            "CLAUDE" -> L.jarvis.panel.readyRunsOnClaudeCode
                            "OPENCODE" -> L.jarvis.panel.readyRunsOnOpencode
                            "CODEX" -> L.jarvis.panel.readyRunsOnCodex
                            else -> L.jarvis.panel.noAgentPickedSettingsJarvisAgent
                        }
                    }
                    Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(Icons.Filled.Tune, L.common.quickSettings, Cyan) { JarvisController.quickSettingsOpen.value = true }
                RoundIcon(Icons.Filled.Close, L.common.close, Color.White.copy(alpha = 0.7f)) { close() }
            }
            Spacer(Modifier.height(12.dp))

            // ---- the switches the user pinned (edit them in quick settings)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                pins.forEach { QuickSwitchChip(it) }
                JarvisQuickChip(L.common.more, L.jarvis.panel.settings, false) { JarvisController.quickSettingsOpen.value = true }
            }
            notice?.let {
                Spacer(Modifier.height(10.dp))
                NoticeChip(it, onNoticeAction)
            }
            Spacer(Modifier.height(12.dp))

            // ---- tasks and the whole conversation
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                if (shownTasks.isNotEmpty()) {
                    item(key = "tasks_head") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(L.jarvis.panel.tasks, Modifier.weight(1f))
                            if (shownTasks.any { !it.active }) {
                                Text(L.jarvis.panel.clearFinished, color = Cyan, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { JarvisTasks.clearFinished() }.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                    items(shownTasks, key = { "task_${it.id}" }) { t ->
                        TaskRow(
                            t,
                            onOpen = { close(); JarvisController.vm.selectConversation(t.id); JarvisController.vm.requestViewMode("CHAT") },
                            onStop = { scope.launch { JarvisTasks.stop(t.id) } },
                            onDismiss = { JarvisTasks.close(t.id) }
                        )
                    }
                }
                item(key = "conv_head") {
                    Column(Modifier.padding(top = if (shownTasks.isNotEmpty()) 10.dp else 0.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(L.jarvis.panel.conversation, Modifier.weight(1f))
                            if (voiceChat.isNotBlank()) {
                                Text(L.jarvis.panel.openChat, color = Cyan, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) {
                                    close()
                                    JarvisController.vm.selectConversation(voiceChat)
                                    JarvisController.vm.requestViewMode("CHAT")
                                }.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            if (log.isNotEmpty()) {
                                Text(L.common.clear, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) {
                                    JarvisController.clearHistory()
                                }.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        if (log.isEmpty()) {
                            Text(
                                L.jarvis.panel.tapTheOrbAndTalkOr,
                                color = Color.White.copy(alpha = 0.5f), fontSize = 12.5.sp, lineHeight = 18.sp
                            )
                        }
                    }
                }
                itemsIndexed(log, key = { i, _ -> "log_$i" }) { _, line ->
                    if (line.first == JarvisController.LOG_DIVIDER) LogDivider(line.second) else LogBubble(line.first == "You", line.second)
                }
                item(key = "end") { Spacer(Modifier.height(4.dp)) }
            }
            Spacer(Modifier.height(10.dp))

            // ---- type to Jarvis
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text(L.jarvis.panel.typeToJarvis, color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { JarvisController.submitText(input); input = "" } }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Cyan,
                        focusedBorderColor = Cyan.copy(alpha = 0.7f), unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                        focusedContainerColor = Color.White.copy(alpha = 0.04f), unfocusedContainerColor = Color.White.copy(alpha = 0.03f)
                    )
                )
                Spacer(Modifier.width(8.dp))
                if (input.isNotBlank()) {
                    RoundIcon(Icons.Filled.Send, L.common.send, Cyan, filled = true) { JarvisController.submitText(input); input = "" }
                } else {
                    RoundIcon(Icons.Filled.Mic, L.jarvis.panel.talk, Cyan, filled = true) { close(); onTalk() }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ---- shortcuts
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FooterButton(L.jarvis.panel.newJarvisChat) {
                    close()
                    JarvisController.startTextChat()?.let { JarvisController.showProblem(it) }
                }
                FooterButton(L.jarvis.panel.jarvisSettings) { JarvisController.requestSettings("JARVIS") }
                FooterButton(L.jarvis.panel.newSession) { JarvisController.newSession(carry = true) }
                FooterButton(L.jarvis.panel.stopAll, Danger) { JarvisController.stopEverything() }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Cyan.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = modifier.padding(bottom = 6.dp).semantics { heading() })
}

@Composable
private fun TaskRow(t: JarvisTasks.Task, onOpen: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    val c = taskColor(t.state)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, c.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { onOpen() }
            .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val age = ((System.currentTimeMillis() - t.createdAt) / 60_000).let { if (it < 1) L.common.justNow else if (it < 60) "${it}m" else L.jarvis.panel.valueHValue2M(it / 60, it % 60) }
            Text(
                listOf(t.team.takeIf { it.isNotBlank() }, when (t.agent.name) { "AGY" -> L.common.antigravity; "OPENCODE" -> "OpenCode"; "CODEX" -> L.common.codex; else -> L.common.claude }, t.state.label, age, t.note.takeIf { it.isNotBlank() && it != "Working" }).filterNotNull().joinToString(" · "),
                color = c.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        RoundIcon(Icons.Filled.OpenInNew, L.common.open, Color.White.copy(alpha = 0.7f), small = true) { onOpen() }
        if (t.active) RoundIcon(Icons.Filled.Stop, L.common.stop, Danger, small = true) { onStop() }
        else RoundIcon(Icons.Filled.Close, L.common.dismiss, Color.White.copy(alpha = 0.5f), small = true) { onDismiss() }
    }
}

@Composable
private fun LogBubble(mine: Boolean, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        SelectionContainer {
            Text(
                text, color = Color.White.copy(alpha = if (mine) 0.92f else 0.85f), fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp))
                    .background(if (mine) Cyan.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

/** Where a new Jarvis chat began. */
@Composable
private fun LogDivider(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        Text(text, color = Color.White.copy(alpha = 0.4f), fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, tint: Color, filled: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (small) 34.dp else 44.dp)
            .clip(CircleShape)
            .background(if (filled) tint.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(if (small) 18.dp else 22.dp)) }
}

@Composable
private fun FooterButton(label: String, color: Color = Cyan, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
