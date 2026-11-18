package ai.antigem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.memory.AgentMemory
import ai.antigem.data.memory.MemoryBridgeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Agent memory: the ```antigem-memory blocks agents write in their replies (the bridge saves them, the chat shows a chip). */
object MemoryBlocks {
    const val LANG = "antigem-memory"

    private val block = Regex("(?m)^[ \\t]*```$LANG[^\\n]*\\n[\\s\\S]*?^[ \\t]*```[ \\t]*$\\n?")

    /** A reply without its memory blocks (what "Copy" puts on the clipboard). */
    fun strip(text: String): String = if (!text.contains(LANG)) text else block.replace(text, "").replace(Regex("\\n{3,}"), "\n\n")

    data class Info(
        val file: String,
        val description: String,
        val delete: Boolean,
        /** always | passive | "" (not given) */
        val mode: String = "",
        /** global | project | "" (not given) */
        val scope: String = "",
        /** The memory's text (after "---"). */
        val text: String = ""
    )

    private val knownKeys = setOf("file", "name", "mode", "scope", "description", "delete")

    /** What the block asks for, read the way the bridge reads it ("key: value" lines, then "---", then the text). */
    fun parse(body: String): Info {
        var file = ""
        var description = ""
        var delete = false
        var mode = ""
        var scope = ""
        val lines = body.replace("\r", "").trim().lines()
        var textFrom = lines.size
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line == "---") { textFrom = i + 1; break }
            if (line.isEmpty()) continue
            val k = line.substringBefore(':', "").trim().lowercase()
            if (k !in knownKeys) { textFrom = i; break }
            val v = line.substringAfter(':', "").trim()
            when (k) {
                "file", "name" -> file = v
                "description" -> description = v
                "delete" -> delete = v.equals("true", true) || v.equals("yes", true)
                "mode" -> mode = when (v.lowercase()) {
                    "always", "pinned", "default", "always-on" -> "always"
                    "passive", "on-demand", "on demand", "relevant" -> "passive"
                    else -> ""
                }
                "scope" -> scope = when (v.lowercase()) {
                    "global", "all", "all projects", "user", "everywhere" -> "global"
                    "project", "this project", "local" -> "project"
                    else -> ""
                }
            }
        }
        val text = if (textFrom < lines.size) lines.drop(textFrom).joinToString("\n").trim() else ""
        return Info(file.removeSuffix(".md"), description, delete, mode, scope, text)
    }
}

/**
 * An ```antigem-memory block in a reply: a full-width chip instead of the raw block, telling what the bridge did
 * with it (it gets the chat a moment after the turn). Tap to see what the agent wrote; Undo asks first (it cannot be
 * redone). [complete]: the block's closing fence arrived.
 */
@Composable
fun MemoryChipView(body: String, complete: Boolean, modifier: Modifier = Modifier) {
    val info = remember(body) { MemoryBlocks.parse(body) }
    val key = remember(body) { MemoryBridgeClient.blockKey(body) }
    val enabled by AgentMemory.enabled.collectAsState()
    val changes by AgentMemory.changes.collectAsState()
    var status by remember(key) { mutableStateOf<MemoryBridgeClient.Applied?>(null) }
    var asked by remember(key) { mutableStateOf(false) }
    var busy by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var expanded by remember(key) { mutableStateOf(false) }
    var confirmUndo by remember(key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    LaunchedEffect(key, complete, enabled, changes) {
        if (!complete || !enabled) return@LaunchedEffect
        // the app sends the chat to the bridge shortly after the turn: ask a few times until it has it
        for (attempt in 0 until 5) {
            val r = MemoryBridgeClient.applied(key).getOrNull()
            if (r != null) status = r
            if (r?.found == true) break
            delay(2_000L + attempt * 1_000L)
        }
        asked = true
    }

    val s = status
    val title = when {
        !complete -> "Saving to memory…"
        !enabled -> "Agent memory is off: not saved"
        error != null -> error!!
        s?.found == true && s.undone -> "Undone"
        s?.found == true && s.action == "error" -> "Not saved: ${s.error}"
        s?.found == true && s.action == "deleted" -> "Deleted from memory"
        s?.found == true && s.action == "updated" -> "Memory updated"
        s?.found == true -> "Saved to memory"
        asked -> "Not saved yet"
        info.delete -> "Deleting from memory…"
        else -> "Saving to memory…"
    }
    val description = s?.description?.takeIf { it.isNotBlank() } ?: info.description
    val summary = listOf(info.file, description).filter { it.isNotBlank() }.joinToString(" — ")
    val canUndo = complete && enabled && s?.found == true && !s.undone && s.action != "error"

    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (summary.isNotBlank()) {
                        Text(summary, fontSize = 12.sp, color = muted, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 12.dp).size(16.dp))
                } else if (canUndo) {
                    TextButton(onClick = { confirmUndo = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Undo", fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Hide what was written" else "Show what was written",
                    tint = muted,
                    modifier = Modifier.padding(end = 6.dp).size(20.dp)
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (info.delete) {
                        MemoryField("Asked to delete", info.file.ifBlank { "(no name)" })
                    } else {
                        MemoryField("Name", info.file)
                        MemoryField("Mode", when (info.mode) {
                            "always" -> "Always: every chat gets it in full"
                            "passive" -> "Passive: listed when relevant"
                            else -> ""
                        })
                        val savedScope = when {
                            s?.id?.startsWith("projects/") == true -> "project"
                            s?.id?.startsWith("global/") == true -> "global"
                            else -> info.scope
                        }
                        MemoryField("Scope", when (savedScope) {
                            "project" -> "This project"
                            "global" -> "All projects"
                            else -> ""
                        })
                        MemoryField("Description", info.description)
                    }
                    if (s?.found == true && s.id.isNotBlank()) MemoryField("File", s.id)
                    if (info.text.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Text(info.text, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmUndo) {
        val name = info.file.ifBlank { "this memory" }
        val what = when (s?.action) {
            "deleted" -> "The memory \"$name\" comes back as it was."
            "updated" -> "The memory \"$name\" goes back to its earlier text."
            else -> "The memory \"$name\" is removed."
        }
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo this memory change?") },
            text = {
                Text(
                    "$what This cannot be undone: to save it again, ask the agent or add it in " +
                        "Settings → Agents → Agent memory & history."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = false
                    busy = true
                    scope.launch {
                        MemoryBridgeClient.undo(key)
                            .onSuccess {
                                status = it
                                error = null
                                AgentMemory.changed()
                                // the chat's history file then notes it as undone
                                ai.antigem.data.memory.MemorySync.refreshOpenChat()
                            }
                            .onFailure { error = it.message ?: "Undo failed" }
                        busy = false
                    }
                }) { Text("Undo", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } }
        )
    }
}

/** One "label  value" line of an expanded memory chip (nothing when the value is blank). */
@Composable
private fun MemoryField(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(bottom = 4.dp)) {
        Text("$label  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Text(value, fontSize = 12.sp)
    }
}
