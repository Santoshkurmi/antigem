package ai.antigem.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.i18n.L

/**
 * A tool's whole output, for outputs too long to show inside the chat (the card shows the start, see
 * [toolOutputPreview]). Lines are drawn lazily, so only the ones on screen are laid out: even a huge build log or file
 * opens at once and scrolls smoothly.
 */
@Composable
fun ToolOutputDialog(
    title: String,
    output: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var wrapLines by remember { mutableStateOf(true) }
    val rows = remember(output) { toolOutputRows(output) }
    val lineCount = remember(output) { countLines(output) }
    val textColor = ai.antigem.theme.AppColors.editorText
    val style = remember(textColor) {
        TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = textColor)
    }
    // without wrapping every row is as wide as the longest one (a monospace font: characters × one character's width)
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(style) { measurer.measure("0", style).size.width }
    val longestRow = remember(rows) { rows.maxOfOrNull { it.length } ?: 0 }
    val noWrapWidth = with(LocalDensity.current) { (charWidthPx * longestRow).toDp() } + 8.dp
    val horizontalScroll = rememberScrollState()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = ai.antigem.theme.AppColors.editorBg,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier
                .fillMaxWidth(0.98f)
                .fillMaxHeight(0.9f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = ClaudeTerracotta,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = L.components.toolOutput.lineCount(lineCount),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = wrapLines,
                        onClick = { wrapLines = !wrapLines },
                        label = { Text(if (wrapLines) L.components.rawPayload.wrapOn else L.components.rawPayload.wrapOff, fontSize = 11.5.sp) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.WrapText,
                                contentDescription = L.components.rawPayload.toggleWrap,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ClaudeTerracotta.copy(alpha = 0.25f),
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = ClaudeTerracotta,
                            containerColor = ai.antigem.theme.AppColors.editorPanel,
                            labelColor = Color.White.copy(alpha = 0.7f)
                        ),
                        border = BorderStroke(1.dp, if (wrapLines) ClaudeTerracotta.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.1f))
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ai.antigem.theme.AppColors.terminalBg,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (wrapLines) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            items(rows.size) { i -> Text(text = rows[i], style = style) }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .horizontalScroll(horizontalScroll)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .width(noWrapWidth)
                                    .fillMaxHeight()
                            ) {
                                items(rows.size) { i -> Text(text = rows[i], style = style, softWrap = false, maxLines = 1) }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Tool Output", output))
                            Toast.makeText(context, L.components.agentToolCallCard.outputCopied, Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ai.antigem.theme.AppColors.editorPanel),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = L.common.copy,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(L.components.agentToolCallCard.copyOutput, fontSize = 12.5.sp, color = Color.White)
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(L.common.close, fontSize = 13.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

/** What a tool card shows of an output: its start, and whether (and how) it was shortened. */
class ToolOutputPreview(
    val text: String,
    /** Lines of the output shown (all of them when only long lines were cut). */
    val shownLines: Int,
    val totalLines: Int,
    /** Something is not shown: lines after [shownLines], or the end of very long lines. */
    val shortened: Boolean
)

/** Lines a tool card shows before "Open full output". */
private const val PREVIEW_MAX_LINES = 200
/** Characters a tool card shows at most (one very long line counts too). */
private const val PREVIEW_MAX_CHARS = 20_000
/** A line longer than this is cut in the card (the dialog shows all of it). */
private const val PREVIEW_MAX_LINE_CHARS = 2_000
/** The dialog cuts a longer line into rows of this size, so no single row is huge to lay out. */
private const val DIALOG_MAX_ROW_CHARS = 4_000

private fun countLines(text: String): Int {
    var n = 1
    for (c in text) if (c == '\n') n++
    return n
}

/**
 * The start of [output] for a tool card: at most [PREVIEW_MAX_LINES] lines and [PREVIEW_MAX_CHARS] characters, each
 * line cut at [PREVIEW_MAX_LINE_CHARS]. A short output comes back as it is (the very same string).
 */
fun toolOutputPreview(output: String): ToolOutputPreview {
    val total = countLines(output)
    if (output.length <= PREVIEW_MAX_CHARS && total <= PREVIEW_MAX_LINES) {
        var longLine = false
        var start = 0
        while (start <= output.length) {
            val end = output.indexOf('\n', start).let { if (it < 0) output.length else it }
            if (end - start > PREVIEW_MAX_LINE_CHARS) { longLine = true; break }
            start = end + 1
        }
        if (!longLine) return ToolOutputPreview(output, total, total, shortened = false)
    }
    val out = StringBuilder(minOf(output.length, PREVIEW_MAX_CHARS) + 64)
    var shown = 0
    var cut = false
    var start = 0
    while (start <= output.length && shown < PREVIEW_MAX_LINES && out.length < PREVIEW_MAX_CHARS) {
        val end = output.indexOf('\n', start).let { if (it < 0) output.length else it }
        if (shown > 0) out.append('\n')
        val room = minOf(PREVIEW_MAX_LINE_CHARS, PREVIEW_MAX_CHARS - out.length).coerceAtLeast(0)
        if (end - start > room) {
            out.append(output, start, start + room).append('…')
            cut = true
        } else {
            out.append(output, start, end)
        }
        shown++
        if (end >= output.length) break
        start = end + 1
    }
    return ToolOutputPreview(out.toString(), shown, total, shortened = cut || shown < total)
}

/** [output] as the dialog's rows: one per line, a very long line cut into several (a trailing \r is dropped). */
private fun toolOutputRows(output: String): List<String> {
    val rows = ArrayList<String>()
    var start = 0
    while (start <= output.length) {
        var end = output.indexOf('\n', start).let { if (it < 0) output.length else it }
        val next = end + 1
        if (end > start && output[end - 1] == '\r') end--
        var s = start
        if (end - s <= DIALOG_MAX_ROW_CHARS) {
            rows.add(output.substring(s, end))
        } else {
            while (s < end) {
                val e = minOf(end, s + DIALOG_MAX_ROW_CHARS)
                rows.add(output.substring(s, e))
                s = e
            }
        }
        if (next > output.length) break
        start = next
    }
    return rows
}
