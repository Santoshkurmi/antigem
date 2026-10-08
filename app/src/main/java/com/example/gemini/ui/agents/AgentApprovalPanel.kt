package com.example.gemini.ui.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.MarkdownContent
import com.example.gemini.ui.components.accent
import com.example.gemini.ui.components.label

private val AllowGreen = Color(0xFF16A34A)
private val DenyRed = Color(0xFFDC2626)

class ApprovalChoice(val label: String, val description: String? = null, val onClick: () -> Unit)

/** One permission request in the approval panel, whichever agent asked. */
class ApprovalItem(
    val key: String,
    val agent: AgentKind,
    val icon: ImageVector,
    val title: String,
    /** Tool name or short context under the title. */
    val subtitle: String? = null,
    /** File path, URL or host the request is about. */
    val target: String? = null,
    /** Monospace body; lines starting with "+ " / "- " are colored as a diff. */
    val preview: String? = null,
    /** Markdown body (a plan to review). */
    val markdown: String? = null,
    /** Why the agent is asking (shown under the preview). */
    val reason: String? = null,
    val primary: ApprovalChoice,
    /** Extra full-width choices (plan approval). */
    val secondary: List<ApprovalChoice> = emptyList(),
    /** "Always allow…" menu. */
    val always: List<ApprovalChoice> = emptyList(),
    val denyLabel: String = "Deny",
    val denyPlaceholder: String = "Tell the agent what to do instead (optional)",
    val onDeny: (feedback: String?) -> Unit
)

/**
 * Approval panel docked above the input. Shows one request at a time (the oldest), with allow / always allow /
 * deny-with-feedback; with several waiting, a counter and allow-all / deny-all.
 */
@Composable
fun AgentApprovalPanel(
    items: List<ApprovalItem>,
    modifier: Modifier = Modifier,
    onAllowAll: (() -> Unit)? = null,
    onDenyAll: (() -> Unit)? = null
) {
    val item = items.firstOrNull() ?: return
    val accent = item.agent.accent()
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
        shadowElevation = 6.dp
    ) {
        AnimatedContent(targetState = item, contentKey = { it.key }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "approval") { current ->
            key(current.key) {
                ApprovalBody(current, count = items.size, onAllowAll = onAllowAll, onDenyAll = onDenyAll)
            }
        }
    }
}

@Composable
private fun ApprovalBody(item: ApprovalItem, count: Int, onAllowAll: (() -> Unit)?, onDenyAll: (() -> Unit)?) {
    val accent = item.agent.accent()
    var denying by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var alwaysMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Icon(item.icon, null, tint = accent, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    listOfNotNull(item.agent.label(), item.subtitle).joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (count > 1) {
                Text(
                    "1 of $count",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            if (count > 1 && (onAllowAll != null || onDenyAll != null)) {
                Box {
                    IconButton(onClick = { overflow = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        onAllowAll?.let {
                            DropdownMenuItem(text = { Text("Allow all $count") }, onClick = {
                                overflow = false
                                it()
                            })
                        }
                        onDenyAll?.let {
                            DropdownMenuItem(text = { Text("Deny all $count", color = DenyRed) }, onClick = {
                                overflow = false
                                it()
                            })
                        }
                    }
                }
            } else {
                Spacer(Modifier.width(6.dp))
            }
        }

        Column(Modifier.padding(end = 6.dp)) {
            item.target?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!item.preview.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        previewText(item.preview),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.heightIn(max = 170.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
            if (!item.markdown.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                        MarkdownContent(content = item.markdown)
                    }
                }
            }
            item.reason?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }

            Spacer(Modifier.height(10.dp))
            if (denying) {
                OutlinedTextField(
                    value = feedback,
                    onValueChange = { feedback = it },
                    placeholder = { Text(item.denyPlaceholder, fontSize = 12.5.sp) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { denying = false }) { Text("Back") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { item.onDeny(feedback.trim().ifBlank { null }) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (item.secondary.isEmpty()) DenyRed else accent),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(item.denyLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
                return@Column
            }

            if (item.secondary.isNotEmpty()) {
                // plan review: stacked choices
                Button(
                    onClick = item.primary.onClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AllowGreen)
                ) { Text(item.primary.label, fontWeight = FontWeight.SemiBold) }
                item.secondary.forEach { choice ->
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = choice.onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(choice.label, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                TextButton(onClick = { denying = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${item.denyLabel}…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                return@Column
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { denying = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text(item.denyLabel, color = DenyRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                if (item.always.isNotEmpty()) {
                    Box {
                        OutlinedButton(
                            onClick = { alwaysMenu = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(start = 12.dp, end = 6.dp)
                        ) {
                            Text("Always…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        DropdownMenu(expanded = alwaysMenu, onDismissRequest = { alwaysMenu = false }) {
                            item.always.forEach { choice ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(choice.label, fontSize = 13.5.sp)
                                            choice.description?.let { Text(it, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)) }
                                        }
                                    },
                                    onClick = {
                                        alwaysMenu = false
                                        choice.onClick()
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = item.primary.onClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AllowGreen),
                    contentPadding = PaddingValues(horizontal = 18.dp)
                ) { Text(item.primary.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
            }
        }
    }
}

private fun previewText(raw: String): AnnotatedString = buildAnnotatedString {
    raw.lines().forEachIndexed { i, line ->
        if (i > 0) append('\n')
        when {
            line.startsWith("+ ") -> withStyle(SpanStyle(color = AllowGreen)) { append(line) }
            line.startsWith("- ") -> withStyle(SpanStyle(color = DenyRed)) { append(line) }
            line.startsWith("$ ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line) }
            line.startsWith("# ") -> withStyle(SpanStyle(color = Color(0xFF9CA3AF))) { append(line) }
            else -> append(line)
        }
    }
}
