package com.example.gemini.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.context.GlobalContext
import com.example.gemini.ui.components.bouncyVerticalScroll

/** The row on Settings → Agents that opens [GlobalContextScreen]. */
@Composable
fun GlobalContextEntryRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val builtInOn by GlobalContext.builtInEnabled.collectAsState()
    val entries by GlobalContext.entries.collectAsState()
    val own = entries.count { it.enabled }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Global context", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Built-in ${if (builtInOn) "on" else "off"}  ·  $own of yours on",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Settings → Agents → Global context: the app's built-in context (read-only, can be turned off) and the user's own
 * entries, each sent in the first message of a chat or with every message, and to Jarvis only when allowed.
 */
@Composable
fun GlobalContextScreen() {
    val builtInOn by GlobalContext.builtInEnabled.collectAsState()
    val entries by GlobalContext.entries.collectAsState()
    var showBuiltIn by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GlobalContext.Entry?>(null) }
    val builtIn = remember { GlobalContext.builtInText() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Added hidden in front of your messages, for every agent (the chat does not show it). First-message context goes " +
                "once per chat, and again only when it changes; every-message entries go with each message.",
            fontSize = 12.5.sp, lineHeight = 17.sp, color = muted
        )
        Spacer(Modifier.height(16.dp))

        // ----- built-in -----
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Built-in context", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null, tint = muted, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("By AntiGem · read-only · first message", fontSize = 12.sp, color = muted)
                        }
                    }
                    Switch(checked = builtInOn, onCheckedChange = { GlobalContext.setBuiltInEnabled(it) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tells the agent " + (if (GlobalContext.runsInTermux) "that it runs in Termux on Android (use \$TMPDIR, not /tmp; pkg, not sudo) and " else "") +
                        "how to show images, videos and files in this chat. Jarvis and the chats it starts get it too.",
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                if (!GlobalContext.runsInTermux) {
                    Spacer(Modifier.height(4.dp))
                    Text("The Termux part is added only in the Termux build while the bridge runs on this phone.", fontSize = 11.5.sp, color = muted)
                }
                TextButton(onClick = { showBuiltIn = !showBuiltIn }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (showBuiltIn) "Hide the text" else "Show exactly what is sent")
                }
                if (showBuiltIn) {
                    SelectionContainer {
                        Text(
                            builtIn,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        // ----- the user's own -----
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your context", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Your own instructions for every chat", fontSize = 12.sp, color = muted)
            }
            FilledTonalButton(onClick = { editing = GlobalContext.Entry(title = "", text = "") }) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
        Spacer(Modifier.height(10.dp))
        if (entries.isEmpty()) {
            Text(
                "Nothing yet. Examples: \"Reply in Hindi\", \"Never run git push\", \"My projects are in ~/work\".",
                fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        entries.forEach { e ->
            EntryCard(e, onToggle = { GlobalContext.upsert(e.copy(enabled = it)) }, onClick = { editing = e })
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { e ->
        EntryEditor(
            entry = e,
            isNew = entries.none { it.id == e.id },
            onSave = { GlobalContext.upsert(it); editing = null },
            onDelete = { GlobalContext.delete(e.id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun EntryCard(e: GlobalContext.Entry, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title.ifBlank { "Untitled" }, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    e.text.trim(), fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (e.everyMessage) "Every message" else "First message", highlight = e.everyMessage)
                    if (e.forJarvis) Chip("Jarvis too", highlight = false)
                }
            }
            Switch(checked = e.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun Chip(label: String, highlight: Boolean) {
    val c = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Text(
        label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = c,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun EntryEditor(
    entry: GlobalContext.Entry,
    isNew: Boolean,
    onSave: (GlobalContext.Entry) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(entry.title) }
    var text by remember { mutableStateOf(entry.text) }
    var everyMessage by remember { mutableStateOf(entry.everyMessage) }
    var forJarvis by remember { mutableStateOf(entry.forJarvis) }
    var confirmDelete by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        title = { Text(if (isNew) "New context" else "Edit context", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text("Title") }, placeholder = { Text("e.g. Language") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("What the agent should know or do") },
                    minLines = 5, maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                Text("When to send", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                WhenOption("First message of each chat", "Enough for most things. Sent again when you change it.", !everyMessage) { everyMessage = false }
                WhenOption("Every message", "For rules the agent must never forget, even in long chats. Costs a few tokens each time.", everyMessage) { everyMessage = true }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { forJarvis = !forJarvis }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Also for Jarvis", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Jarvis has strict rules of its own (speaking, not waiting for tasks), so a note meant for normal chats can clash. " +
                                "When on, Jarvis gets it marked \"Jarvis rules win on conflict\". It also reaches the chats Jarvis starts for tasks.",
                            fontSize = 11.5.sp, lineHeight = 15.sp, color = muted
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = forJarvis, onCheckedChange = { forJarvis = it })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onSave(entry.copy(title = title.trim(), text = text.trim(), everyMessage = everyMessage, forJarvis = forJarvis)) }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${entry.title.ifBlank { "Untitled" }}\"?") },
            text = { Text("New messages will no longer carry it. Chats that already got it keep what they were told.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WhenOption(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.5.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
