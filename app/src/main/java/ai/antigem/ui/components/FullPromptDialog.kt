package ai.antigem.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.domain.model.ChatMessage

/**
 * The prompt a user message actually sent: a voice request's full prompt, else the text before the chat collapsed its
 * hidden parts ([ChatMessage.sentContent]), else the message as stored.
 */
fun fullPromptOf(message: ChatMessage): String =
    message.rawContent?.takeIf { message.viaVoice && it.isNotBlank() } ?: message.sentContent ?: message.content

private val HIDDEN_TAG = Regex("</?(antigem-expansion|attached-files)\\b[^>]*>")

/**
 * A user message exactly as it went to the agent, nothing hidden: the app's hidden parts (global context, Agent memory,
 * slash-command and plugin expansions, the attached-files list) are shown with their tags highlighted. For debugging.
 */
@Composable
fun FullPromptDialog(prompt: String, attachmentCount: Int = 0, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val styled = remember(prompt, accent) {
        buildAnnotatedString {
            var last = 0
            HIDDEN_TAG.findAll(prompt).forEach { m ->
                append(prompt.substring(last, m.range.first))
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(m.value) }
                last = m.range.last + 1
            }
            append(prompt.substring(last))
        }
    }
    val hiddenBlocks = remember(prompt) { Regex("<antigem-expansion\\b").findAll(prompt).count() }
    val stats = buildString {
        append("%,d characters · ~%,d tokens".format(prompt.length, (prompt.length + 3) / 4))
        if (hiddenBlocks > 0) append(" · $hiddenBlocks hidden block${if (hiddenBlocks == 1) "" else "s"}")
        if (prompt.contains("<attached-files>")) append(" · file list")
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.95f).widthIn(max = 720.dp).fillMaxHeight(0.88f)
        ) {
            Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Full prompt", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(stats, fontSize = 12.sp, color = muted)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
                }
                Text(
                    "Exactly what was sent to the agent, including the parts the chat hides." +
                        if (attachmentCount > 0) " $attachmentCount attachment${if (attachmentCount == 1) " was" else "s were"} sent alongside (not shown)." else "",
                    fontSize = 12.sp, lineHeight = 16.sp, color = muted,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp)
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(end = 8.dp)
                ) {
                    SelectionContainer {
                        Text(
                            styled,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Full prompt", prompt))
                        Toast.makeText(context, "Copied the full prompt", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("Copy all", modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
