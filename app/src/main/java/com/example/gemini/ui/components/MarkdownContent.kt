package com.example.gemini.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.ClaudeTerracotta

@Composable
fun MarkdownContent(
    content: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val blocks = parseMarkdownBlocks(content)

        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> {
                    CodeBlock(code = block.code, language = block.language)
                }
                is MarkdownBlock.Header -> {
                    Text(
                        text = block.text,
                        fontSize = if (block.level == 1) 20.sp else if (block.level == 2) 17.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MarkdownBlock.Bullet -> {
                    Row(modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp)) {
                        Text(
                            text = "• ",
                            fontWeight = FontWeight.Bold,
                            color = ClaudeTerracotta,
                            fontSize = 14.5.sp
                        )
                        FormattedInlineText(text = block.text)
                    }
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

@Composable
fun FormattedInlineText(
    text: String,
    modifier: Modifier = Modifier
) {
    val annotatedString = buildAnnotatedString {
        val parts = text.split("`")
        for (i in parts.indices) {
            val part = parts[i]
            if (i % 2 == 1) { // Inline code
                withStyle(
                    style = SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        color = ClaudeTerracotta
                    )
                ) {
                    append(" $part ")
                }
            } else {
                // Parse bold **
                val boldParts = part.split("**")
                for (j in boldParts.indices) {
                    val subPart = boldParts[j]
                    if (j % 2 == 1) {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(subPart)
                        }
                    } else {
                        append(subPart)
                    }
                }
            }
        }
    }

    Text(
        text = annotatedString,
        fontSize = 14.5.sp,
        lineHeight = 22.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
}

sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
}

fun parseMarkdownBlocks(rawText: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val lines = rawText.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code block starts
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

        // Headers
        if (line.startsWith("# ")) {
            result.add(MarkdownBlock.Header(1, line.removePrefix("# ").trim()))
        } else if (line.startsWith("## ")) {
            result.add(MarkdownBlock.Header(2, line.removePrefix("## ").trim()))
        } else if (line.startsWith("### ")) {
            result.add(MarkdownBlock.Header(3, line.removePrefix("### ").trim()))
        } else if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
            result.add(MarkdownBlock.Bullet(line.trimStart().substring(2)))
        } else if (line.isNotBlank()) {
            result.add(MarkdownBlock.Paragraph(line))
        }

        i++
    }

    return result
}
