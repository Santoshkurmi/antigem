package com.example.gemini.ui.jarvis

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.jarvis.JarvisController
import com.example.gemini.data.jarvis.JarvisController.Phase
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.data.jarvis.JarvisTasks
import kotlinx.coroutines.launch

/**
 * The Jarvis panel (long-press the orb): quick switches (hands-free, voice or quiet, send mode, screen on, wake), the
 * tasks Jarvis manages with open / stop / dismiss, the conversation, a box to type to Jarvis, and shortcuts to its
 * settings, a fresh session and stop.
 */
@Composable
fun JarvisPanel(onTalk: () -> Unit, onNoticeAction: (String) -> Unit) {
    val close = { JarvisController.panelOpen.value = false }
    BackHandler(onBack = close)
    val scope = rememberCoroutineScope()
    val phase by JarvisController.phase.collectAsState()
    val log by JarvisController.log.collectAsState()
    val tasks by JarvisTasks.tasks.collectAsState()
    val notice by JarvisController.notice.collectAsState()
    val steps by JarvisController.steps.collectAsState()
    val handsFree by JarvisPreferences.handsFree.state.collectAsState()
    val quiet by JarvisPreferences.quietMode.state.collectAsState()
    val sendMode by JarvisPreferences.sendMode.state.collectAsState()
    val screenOn by JarvisPreferences.keepScreenOn.state.collectAsState()
    val hotword by JarvisPreferences.hotword.state.collectAsState()
    val agent by JarvisPreferences.agent.state.collectAsState()
    var input by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFA111A2C), Color(0xFC070B14))))
                .border(1.dp, Brush.verticalGradient(listOf(Cyan.copy(alpha = 0.5f), Color.Transparent)), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                // taps inside the sheet stay inside
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
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
                    if (phase == Phase.IDLE || phase == Phase.OFF) IdleOrb(42.dp, tasks.count { it.active }, false) else JarvisOrb(phase, 0.3f, 42.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Jarvis", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    val sub = when (phase) {
                        Phase.LISTENING -> "Listening"
                        Phase.THINKING -> steps.lastOrNull()?.text ?: "Working"
                        Phase.SPEAKING -> "Speaking"
                        else -> "Ready · runs on ${if (agent == "AGY") "Antigravity" else "Claude Code"}"
                    }
                    Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(Icons.Filled.Close, "Close", Color.White.copy(alpha = 0.7f)) { close() }
            }
            Spacer(Modifier.height(12.dp))

            // ---- quick switches
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("Hands-free", if (handsFree) "On" else "Off", handsFree) { JarvisController.setHandsFree(!handsFree) }
                QuickChip("Voice", if (quiet) "Quiet" else "Speaking", !quiet) { JarvisPreferences.quietMode.set(!quiet) }
                QuickChip("Send", sendLabel(sendMode), true) {
                    val order = listOf("smart", "pause", "word", "manual")
                    JarvisPreferences.sendMode.set(order[(order.indexOf(sendMode) + 1).mod(order.size)])
                }
                QuickChip("Wake", if (hotword == "phrase") "Phrase" else "Button", hotword == "phrase") {
                    JarvisPreferences.hotword.set(if (hotword == "phrase") "push" else "phrase")
                }
                QuickChip("Screen", when (screenOn) { "off" -> "Can sleep"; "active" -> "On while active"; else -> "Always on" }, screenOn != "off") {
                    val order = listOf("always", "active", "off")
                    JarvisPreferences.keepScreenOn.set(order[(order.indexOf(screenOn) + 1).mod(order.size)])
                }
            }
            notice?.let {
                Spacer(Modifier.height(10.dp))
                NoticeChip(it, onNoticeAction)
            }
            Spacer(Modifier.height(12.dp))

            // ---- tasks and conversation
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (tasks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Tasks", Modifier.weight(1f))
                        if (tasks.any { !it.active }) {
                            Text("Clear finished", color = Cyan, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { JarvisTasks.clearFinished() }.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    tasks.take(12).forEach { t ->
                        TaskRow(
                            t,
                            onOpen = { close(); JarvisController.vm.selectConversation(t.id); JarvisController.vm.requestViewMode("CHAT") },
                            onStop = { scope.launch { JarvisTasks.stop(t.id) } },
                            onDismiss = { JarvisTasks.close(t.id) }
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                SectionTitle("Conversation")
                if (log.isEmpty()) {
                    Text(
                        "Tap the orb and talk, or type below. Try “what's running?”, “start a task to fix the failing tests”, " +
                            "“switch to dark mode”, “how do I add an MCP server?”.",
                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.5.sp, lineHeight = 18.sp
                    )
                }
                log.takeLast(14).forEach { (who, text) -> LogBubble(who == "You", text) }
            }
            Spacer(Modifier.height(10.dp))

            // ---- type to Jarvis
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text("Type to Jarvis…", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp) },
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
                    RoundIcon(Icons.Filled.Send, "Send", Cyan, filled = true) { JarvisController.submitText(input); input = "" }
                } else {
                    RoundIcon(Icons.Filled.Mic, "Talk", Cyan, filled = true) { close(); onTalk() }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ---- shortcuts
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FooterButton("Jarvis settings") { JarvisController.requestSettings("JARVIS") }
                FooterButton("New session") { JarvisController.newSession(carry = true) }
                FooterButton("Stop all", Danger) { JarvisController.stopEverything() }
            }
        }
    }
}

private fun sendLabel(mode: String) = when (mode) {
    "pause" -> "After pause"
    "word" -> "Send word"
    "manual" -> "Manual"
    else -> "Smart"
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Cyan.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = modifier.padding(bottom = 6.dp))
}

@Composable
private fun QuickChip(title: String, value: String, on: Boolean, onClick: () -> Unit) {
    val c = if (on) Cyan else Color.White.copy(alpha = 0.5f)
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Cyan.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, c.copy(alpha = if (on) 0.5f else 0.2f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp)
        Text(value, color = if (on) Color.White else Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
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
            .clickable { onOpen() }
            .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val age = ((System.currentTimeMillis() - t.createdAt) / 60_000).let { if (it < 1) "just now" else if (it < 60) "${it}m" else "${it / 60}h ${it % 60}m" }
            Text(
                listOf(if (t.agent.name == "AGY") "Antigravity" else "Claude", t.state.label, age, t.note.takeIf { it.isNotBlank() && it != "Working" }).filterNotNull().joinToString(" · "),
                color = c.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        RoundIcon(Icons.Filled.OpenInNew, "Open", Color.White.copy(alpha = 0.7f), small = true) { onOpen() }
        if (t.active) RoundIcon(Icons.Filled.Stop, "Stop", Danger, small = true) { onStop() }
        else RoundIcon(Icons.Filled.Close, "Dismiss", Color.White.copy(alpha = 0.5f), small = true) { onDismiss() }
    }
}

@Composable
private fun LogBubble(mine: Boolean, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
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

@Composable
private fun RoundIcon(icon: ImageVector, label: String, tint: Color, filled: Boolean = false, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (small) 34.dp else 44.dp)
            .clip(CircleShape)
            .background(if (filled) tint.copy(alpha = 0.2f) else Color.Transparent)
            .clickable { onClick() },
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
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
