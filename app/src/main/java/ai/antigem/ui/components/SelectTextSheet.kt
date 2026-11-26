package ai.antigem.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.i18n.L

/**
 * A whole reply in one selection area, so text can be selected across paragraphs, lists and code in one drag.
 * The chat feed draws a reply as many list items (one per block) and only lets each block be selected on its own;
 * this sheet is built only when opened, so the feed keeps its speed. "Formatted" looks like the chat (media,
 * diagrams and tool cards are left out so nothing interferes with selecting); "Markdown" is the exact text.
 * [plainText]: text that isn't Markdown (a terminal's output): only the exact text, no Formatted / Markdown chips.
 */
@Composable
fun SelectTextSheet(
    content: String,
    title: String = L.components.selectText.selectText,
    plainText: Boolean = false,
    // last, so a trailing lambda (MessageBubble) still lands here
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var markdown by rememberSaveable { mutableStateOf(false) }
    val blocks = remember(content, plainText) { if (plainText) emptyList<MarkdownBlock>() else parseMarkdownBlocks(content) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L.common.close) }
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(if (plainText) title else "Copied Response", content))
                        Toast.makeText(context, if (plainText) L.common.copied else L.components.selectText.copiedResponse, Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Outlined.ContentCopy, L.components.selectText.copyAll) }
                }
                if (!plainText) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip(L.components.selectText.formatted, selected = !markdown) { markdown = false }
                        ModeChip(L.components.selectText.markdown, selected = markdown) { markdown = true }
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SelectionContainer(Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (markdown || plainText) {
                            Text(content, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
                        } else {
                            blocks.forEach { block -> SelectableBlock(block) }
                        }
                    }
                }
            }
        }
    }
}

/** Text blocks render as in the chat; anything else becomes a short placeholder line. */
@Composable
private fun SelectableBlock(block: MarkdownBlock) {
    val placeholder = when (block) {
        is MarkdownBlock.Image -> "[Image${block.alt.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}]"
        is MarkdownBlock.Video -> "[Video${block.alt.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}]"
        is MarkdownBlock.YouTubeVideo -> "[YouTube: ${block.originalUrl}]"
        is MarkdownBlock.Mermaid -> "[Diagram]"
        is MarkdownBlock.Stem -> "[Diagram]"
        is MarkdownBlock.InteractiveUi -> "[${block.title}]"
        is MarkdownBlock.AgentTool, is MarkdownBlock.AgentThought, is MarkdownBlock.AgentError, is MarkdownBlock.MemoryChip -> return
        is MarkdownBlock.Math -> {
            Text(block.latex, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
            return
        }
        else -> null
    }
    if (placeholder != null) {
        Text(
            placeholder,
            fontSize = 12.5.sp,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    } else {
        MarkdownBlockView(block = block)
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = if (selected) ClaudeTerracotta.copy(alpha = 0.14f) else MaterialTheme.colorScheme.background,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) ClaudeTerracotta.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
