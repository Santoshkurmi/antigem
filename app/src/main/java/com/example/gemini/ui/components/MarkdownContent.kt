package com.example.gemini.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue
import com.example.gemini.theme.QuotaGreen
import java.util.regex.Pattern

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.draw.rotate

enum class TableAlignment { LEFT, CENTER, RIGHT }

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class Bullet(val indent: Int, val text: String) : MarkdownBlock()
    data class Numbered(val number: String, val text: String) : MarkdownBlock()
    data class Task(val isChecked: Boolean, val text: String) : MarkdownBlock()
    data class Blockquote(val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data class Image(val alt: String, val url: String) : MarkdownBlock()
    data class Details(val summary: String, val body: String, val defaultOpen: Boolean = false) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>, val alignments: List<TableAlignment>) : MarkdownBlock()
    object HorizontalRule : MarkdownBlock()
}

@Composable
fun MarkdownContent(
    content: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> {
                    CodeBlock(code = block.code, language = block.language)
                }
                is MarkdownBlock.Table -> {
                    MarkdownTableView(table = block)
                }
                is MarkdownBlock.Image -> {
                    MarkdownImageView(image = block)
                }
                is MarkdownBlock.Details -> {
                    MarkdownDetailsView(details = block)
                }
                is MarkdownBlock.Header -> {
                    val (fontSize, topPad, bottomPad) = when (block.level) {
                        1 -> Triple(20.sp, 10.dp, 5.dp)
                        2 -> Triple(17.5.sp, 9.dp, 4.dp)
                        3 -> Triple(15.5.sp, 8.dp, 4.dp)
                        4 -> Triple(14.sp, 7.dp, 3.dp)
                        5 -> Triple(13.sp, 6.dp, 2.dp)
                        else -> Triple(12.5.sp, 5.dp, 2.dp)
                    }
                    Text(
                        text = block.text,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        color = if (block.level <= 3) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = topPad, bottom = bottomPad)
                    )
                }
                is MarkdownBlock.Bullet -> {
                    Row(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .padding(start = (block.indent * 14).dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = if (block.indent == 0) "• " else "◦ ",
                            fontWeight = FontWeight.Bold,
                            color = ClaudeTerracotta,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        FormattedInlineText(text = block.text)
                    }
                }
                is MarkdownBlock.Numbered -> {
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${block.number} ",
                            fontWeight = FontWeight.Bold,
                            color = ClaudeTerracotta,
                            fontSize = 13.5.sp,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        FormattedInlineText(text = block.text)
                    }
                }
                is MarkdownBlock.Task -> {
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (block.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = if (block.isChecked) "Completed" else "Incomplete",
                            tint = if (block.isChecked) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FormattedInlineText(
                            text = block.text,
                            isStrikethrough = block.isChecked
                        )
                    }
                }
                is MarkdownBlock.Blockquote -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(4.dp)
                                    .background(ClaudeTerracotta)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            FormattedInlineText(
                                text = block.text,
                                modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    FormattedInlineText(
                        text = block.text,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/**
 * Expandable <details><summary> component with animated rotation and embedded markdown content.
 */
@Composable
fun MarkdownDetailsView(
    details: MarkdownBlock.Details,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(details.defaultOpen) }
    val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "details_arrow")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = ClaudeTerracotta,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FormattedInlineText(
                    text = details.summary,
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
                ) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    MarkdownContent(content = details.body)
                }
            }
        }
    }
}

/**
 * Renders an Image in Markdown using Coil with rounded corners and viewer intent.
 */
@Composable
fun MarkdownImageView(
    image: MarkdownBlock.Image,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    RoundedCornerShape(12.dp)
                )
                .clickable {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(image.url))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(image.url)
                    .crossfade(true)
                    .build(),
                contentDescription = image.alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 340.dp)
            )
        }

        if (image.alt.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = image.alt,
                fontSize = 11.5.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Native Jetpack Compose Table renderer with horizontal scrolling and styled borders.
 */
@Composable
fun MarkdownTableView(
    table: MarkdownBlock.Table,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                RoundedCornerShape(10.dp)
            ),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            // Table Header Row
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(vertical = 2.dp)
            ) {
                table.headers.forEachIndexed { colIdx, header ->
                    val alignment = table.alignments.getOrElse(colIdx) { TableAlignment.LEFT }
                    Box(
                        modifier = Modifier
                            .widthIn(min = 100.dp, max = 280.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = when (alignment) {
                            TableAlignment.CENTER -> Alignment.Center
                            TableAlignment.RIGHT -> Alignment.CenterEnd
                            TableAlignment.LEFT -> Alignment.CenterStart
                        }
                    ) {
                        Text(
                            text = header,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = when (alignment) {
                                TableAlignment.CENTER -> TextAlign.Center
                                TableAlignment.RIGHT -> TextAlign.End
                                TableAlignment.LEFT -> TextAlign.Start
                            }
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))

            // Table Data Rows
            table.rows.forEachIndexed { rowIdx, rowCells ->
                val rowBg = if (rowIdx % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f) else Color.Transparent

                Row(
                    modifier = Modifier
                        .background(rowBg)
                        .padding(vertical = 2.dp)
                ) {
                    table.headers.forEachIndexed { colIdx, _ ->
                        val cellText = rowCells.getOrElse(colIdx) { "" }
                        val alignment = table.alignments.getOrElse(colIdx) { TableAlignment.LEFT }
                        Box(
                            modifier = Modifier
                                .widthIn(min = 100.dp, max = 280.dp)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = when (alignment) {
                                TableAlignment.CENTER -> Alignment.Center
                                TableAlignment.RIGHT -> Alignment.CenterEnd
                                TableAlignment.LEFT -> Alignment.CenterStart
                            }
                        ) {
                            FormattedInlineText(text = cellText)
                        }
                    }
                }

                if (rowIdx < table.rows.size - 1) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                }
            }
        }
    }
}

/**
 * Formats rich inline Markdown text with full support for:
 * Bold (`**` or `__`), Italic (`*` or `_`), Bold+Italic (`***`), Inline Code (`` ` ``), Links (`[title](url)`), Strikethrough (`~~`).
 */
@Composable
fun FormattedInlineText(
    text: String,
    modifier: Modifier = Modifier,
    isStrikethrough: Boolean = false
) {
    val uriHandler = LocalUriHandler.current

    val (annotatedString, _) = remember(text, isStrikethrough) {
        buildRichAnnotatedString(text, isStrikethrough)
    }

    ClickableText(
        text = annotatedString,
        style = TextStyle(
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier,
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        uriHandler.openUri(annotation.item)
                    } catch (_: Exception) {}
                }
        }
    )
}

private fun buildRichAnnotatedString(text: String, globalStrikethrough: Boolean = false): Pair<AnnotatedString, Map<Int, String>> {
    val builder = AnnotatedString.Builder()
    val urlActions = mutableMapOf<Int, String>()

    if (globalStrikethrough) {
        builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color.Gray))
    }

    // Pre-process <br> tags to newlines
    val cleanText = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")

    // Comprehensive regex for Markdown and HTML inline formatting tokens
    val pattern = Pattern.compile(
        "(\\[(.*?)\\]\\((https?://[^\\s)]+)\\))|" +                              // 1: Markdown Link [text](url)
        "(<a\\s+href=[\"'](https?://[^\"']+)[\"']\\s*>(.*?)</a>)|" +             // 4: HTML Link <a href="url">text</a>
        "(`([^`]+)`)|" +                                                           // 7: Inline code `code`
        "(<code>(.*?)</code>)|" +                                                  // 9: HTML code <code>code</code>
        "(\\*{3}([^*]+)\\*{3})|" +                                                 // 11: Bold-Italic ***text***
        "(\\*{2}([^*]+)\\*{2})|" +                                                 // 13: Bold **text**
        "(__{2}([^_]+)__{2})|" +                                                   // 15: Bold __text__
        "(<b>(.*?)</b>)|" +                                                        // 17: HTML bold <b>text</b>
        "(<strong>(.*?)</strong>)|" +                                              // 19: HTML strong <strong>text</strong>
        "(<u>(.*?)</u>)|" +                                                        // 21: HTML underline <u>text</u>
        "(\\*{1}([^*]+)\\*{1})|" +                                                 // 23: Italic *text*
        "(_([^_]+)_)|" +                                                           // 25: Italic _text_
        "(<i>(.*?)</i>)|" +                                                        // 27: HTML italic <i>text</i>
        "(<em>(.*?)</em>)|" +                                                      // 29: HTML em <em>text</em>
        "(~~([^~]+)~~)|" +                                                         // 31: Strikethrough ~~text~~
        "(<s>(.*?)</s>)|" +                                                        // 33: HTML strike <s>text</s>
        "(<del>(.*?)</del>)|" +                                                    // 35: HTML del <del>text</del>
        "(<strike>(.*?)</strike>)",                                                // 37: HTML strike <strike>text</strike>
        Pattern.CASE_INSENSITIVE
    )
    val matcher = pattern.matcher(cleanText)
    var lastEnd = 0

    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()

        if (start > lastEnd) {
            builder.append(cleanText.substring(lastEnd, start))
        }

        val fullMatch = matcher.group()

        if (fullMatch.startsWith("[") && fullMatch.contains("](")) {
            // Markdown Link [title](url)
            val linkTitle = matcher.group(2) ?: ""
            val linkUrl = matcher.group(3) ?: ""
            val linkStart = builder.length
            builder.pushStringAnnotation(tag = "URL", annotation = linkUrl)
            builder.pushStyle(
                SpanStyle(
                    color = GeminiBlue,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline
                )
            )
            builder.append(linkTitle)
            builder.pop()
            builder.pop()
            urlActions[linkStart] = linkUrl
        } else if (fullMatch.startsWith("<a", ignoreCase = true)) {
            // HTML Link <a href="url">title</a>
            val linkUrl = matcher.group(5) ?: ""
            val linkTitle = matcher.group(6) ?: ""
            val linkStart = builder.length
            builder.pushStringAnnotation(tag = "URL", annotation = linkUrl)
            builder.pushStyle(
                SpanStyle(
                    color = GeminiBlue,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline
                )
            )
            builder.append(linkTitle)
            builder.pop()
            builder.pop()
            urlActions[linkStart] = linkUrl
        } else if ((fullMatch.startsWith("`") && fullMatch.endsWith("`")) || fullMatch.startsWith("<code", ignoreCase = true)) {
            // Inline code `...` or <code>...</code>
            val codeContent = if (fullMatch.startsWith("`")) fullMatch.removeSurrounding("`") else matcher.group(10) ?: ""
            builder.pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = Color(0x24D97706),
                    fontWeight = FontWeight.SemiBold,
                    color = ClaudeTerracotta,
                    fontSize = 12.5.sp
                )
            )
            builder.append(" $codeContent ")
            builder.pop()
        } else if (fullMatch.startsWith("***") && fullMatch.endsWith("***")) {
            // Bold Italic ***...***
            val content = fullMatch.removeSurrounding("***")
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
            builder.append(content)
            builder.pop()
        } else if (fullMatch.startsWith("**") && fullMatch.endsWith("**")) {
            // Bold **...**
            val content = fullMatch.removeSurrounding("**")
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            builder.append(content)
            builder.pop()
        } else if (fullMatch.startsWith("__") && fullMatch.endsWith("__")) {
            // Bold __...__
            val content = fullMatch.removeSurrounding("__")
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            builder.append(content)
            builder.pop()
        } else if (fullMatch.startsWith("<b", ignoreCase = true) || fullMatch.startsWith("<strong", ignoreCase = true)) {
            // HTML Bold <b>...</b> or <strong>...</strong>
            val content = if (fullMatch.startsWith("<b", ignoreCase = true)) matcher.group(18) ?: "" else matcher.group(20) ?: ""
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            builder.append(content)
            builder.pop()
        } else if (fullMatch.startsWith("<u", ignoreCase = true)) {
            // HTML Underline <u>...</u>
            val content = matcher.group(22) ?: ""
            builder.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
            builder.append(content)
            builder.pop()
        } else if (fullMatch.startsWith("~~") && fullMatch.endsWith("~~") || fullMatch.startsWith("<s", ignoreCase = true) || fullMatch.startsWith("<del", ignoreCase = true)) {
            // Strikethrough ~~...~~, <s>...</s>, <del>...</del>, <strike>...</strike>
            val content = if (fullMatch.startsWith("~~")) fullMatch.removeSurrounding("~~")
            else if (fullMatch.startsWith("<s", ignoreCase = true)) (matcher.group(34) ?: matcher.group(38) ?: "")
            else matcher.group(36) ?: ""
            builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
            builder.append(content)
            builder.pop()
        } else if (fullMatch.startsWith("*") && fullMatch.endsWith("*")) {
            // Italic *...*
            val content = fullMatch.removeSurrounding("*")
            builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            builder.append(content)
            builder.pop()
        } else if (fullMatch.startsWith("_") && fullMatch.endsWith("_")) {
            // Italic _..._
            val content = fullMatch.removeSurrounding("_")
            builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            builder.append(content)
            builder.pop()
        } else if (fullMatch.startsWith("<i", ignoreCase = true) || fullMatch.startsWith("<em", ignoreCase = true)) {
            // HTML Italic <i>...</i> or <em>...</em>
            val content = if (fullMatch.startsWith("<i", ignoreCase = true)) matcher.group(28) ?: "" else matcher.group(30) ?: ""
            builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            builder.append(content)
            builder.pop()
        } else {
            builder.append(fullMatch)
        }

        lastEnd = end
    }

    if (lastEnd < cleanText.length) {
        builder.append(cleanText.substring(lastEnd))
    }

    if (globalStrikethrough) {
        builder.pop()
    }

    return Pair(builder.toAnnotatedString(), urlActions)
}

/**
 * Full Markdown block parser supporting:
 * Fenced Code, <details><summary>, GFM Tables, Headers (1-6), Images, Task Checklists, Blockquotes, Lists, Dividers, Paragraphs.
 */
fun parseMarkdownBlocks(rawText: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val lines = rawText.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 1. Fenced Code block starts: ```
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            result.add(MarkdownBlock.Code(language = lang, code = codeLines.joinToString("\n")))
            i++
            continue
        }

        // 2. <details> and <summary> Collapsible Sections
        if (line.trimStart().startsWith("<details", ignoreCase = true)) {
            val isOpen = line.contains("open", ignoreCase = true)
            var summary = "Details"
            val bodyLines = mutableListOf<String>()
            i++

            while (i < lines.size && !lines[i].trimStart().startsWith("</details>", ignoreCase = true)) {
                val currLine = lines[i]
                if (currLine.trimStart().startsWith("<summary>", ignoreCase = true)) {
                    summary = currLine.replace(Regex("</?summary>", RegexOption.IGNORE_CASE), "").trim()
                } else {
                    bodyLines.add(currLine)
                }
                i++
            }
            result.add(MarkdownBlock.Details(summary = summary, body = bodyLines.joinToString("\n").trim(), defaultOpen = isOpen))
            i++
            continue
        }

        // 3. Standalone Markdown Images: ![alt](url)
        val imageMatch = Regex("^\\s*!\\[(.*?)\\]\\((https?://[^\\s)]+)\\)\\s*$").find(line)
        if (imageMatch != null) {
            val alt = imageMatch.groupValues[1]
            val url = imageMatch.groupValues[2]
            result.add(MarkdownBlock.Image(alt = alt, url = url))
            i++
            continue
        }

        // 4. GFM Tables: | header | header |
        if (line.contains("|") && i + 1 < lines.size) {
            val tableResult = parseTable(lines, i)
            if (tableResult != null) {
                result.add(tableResult.first)
                i = tableResult.second
                continue
            }
        }

        // 5. Headers: # to ######
        val headerMatch = Regex("^(#{1,6})\\s+(.*)").find(line.trimStart())
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val title = headerMatch.groupValues[2].trim()
            result.add(MarkdownBlock.Header(level, title))
        }
        // 6. Task List: - [ ] or - [x]
        else if (line.trimStart().matches(Regex("^[-*]\\s+\\[([ xX])\\]\\s+.*"))) {
            val taskMatch = Regex("^[-*]\\s+\\[([ xX])\\]\\s+(.*)").find(line.trimStart())
            if (taskMatch != null) {
                val isChecked = taskMatch.groupValues[1].trim().lowercase() == "x"
                val taskText = taskMatch.groupValues[2].trim()
                result.add(MarkdownBlock.Task(isChecked, taskText))
            } else {
                result.add(MarkdownBlock.Paragraph(line))
            }
        }
        // 7. Blockquotes: >
        else if (line.trimStart().startsWith(">")) {
            val quoteText = line.trimStart().removePrefix(">").trim()
            result.add(MarkdownBlock.Blockquote(quoteText))
        }
        // 8. Horizontal Rules: ---, ***, ___
        else if (line.trim() == "---" || line.trim() == "***" || line.trim() == "___") {
            result.add(MarkdownBlock.HorizontalRule)
        }
        // 9. Numbered Lists: 1. , 2. 
        else if (line.trimStart().matches(Regex("^\\d+\\.\\s+.*"))) {
            val match = Regex("^(\\d+\\.)\\s+(.*)").find(line.trimStart())
            if (match != null) {
                val num = match.groupValues[1]
                val content = match.groupValues[2]
                result.add(MarkdownBlock.Numbered(num, content))
            } else {
                result.add(MarkdownBlock.Paragraph(line))
            }
        }
        // 10. Bullet Lists (with nested indentation support)
        else if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") || line.trimStart().startsWith("+ ")) {
            val leadingSpaces = line.takeWhile { it == ' ' || it == '\t' }.length
            val indentLevel = (leadingSpaces / 2).coerceIn(0, 3)
            val bulletContent = line.trimStart().substring(2).trim()
            result.add(MarkdownBlock.Bullet(indent = indentLevel, text = bulletContent))
        }
        // 11. Regular Paragraphs
        else if (line.isNotBlank()) {
            result.add(MarkdownBlock.Paragraph(line))
        }

        i++
    }

    return result
}

/**
 * Parses markdown table header, separator, and data rows.
 */
private fun parseTable(lines: List<String>, startIndex: Int): Pair<MarkdownBlock.Table, Int>? {
    if (startIndex + 1 >= lines.size) return null
    val headerLine = lines[startIndex].trim()
    val sepLine = lines[startIndex + 1].trim()

    if (!headerLine.contains("|") || !sepLine.contains("|")) return null

    val sepCells = sepLine.split("|")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    if (sepCells.isEmpty() || !sepCells.all { it.matches(Regex("^:?-+:?$")) }) return null

    val rawHeaders = headerLine.split("|")
        .map { it.trim() }
        .filterIndexed { idx, cell ->
            !(idx == 0 && cell.isEmpty()) && !(idx == headerLine.split("|").lastIndex && cell.isEmpty())
        }

    val headers = if (rawHeaders.isNotEmpty()) rawHeaders else sepCells.mapIndexed { idx, _ -> "Column ${idx + 1}" }

    val alignments = sepCells.map { cell ->
        val left = cell.startsWith(":")
        val right = cell.endsWith(":")
        if (left && right) TableAlignment.CENTER
        else if (right) TableAlignment.RIGHT
        else TableAlignment.LEFT
    }

    val rows = mutableListOf<List<String>>()
    var currIndex = startIndex + 2
    while (currIndex < lines.size) {
        val rowLine = lines[currIndex].trim()
        if (rowLine.isBlank() || !rowLine.contains("|")) break
        val rawCells = rowLine.split("|")
            .map { it.trim() }
            .filterIndexed { idx, cell ->
                !(idx == 0 && cell.isEmpty()) && !(idx == rowLine.split("|").lastIndex && cell.isEmpty())
            }

        if (rawCells.isNotEmpty()) {
            rows.add(rawCells)
            currIndex++
        } else {
            break
        }
    }

    return Pair(MarkdownBlock.Table(headers, rows, alignments), currIndex)
}



