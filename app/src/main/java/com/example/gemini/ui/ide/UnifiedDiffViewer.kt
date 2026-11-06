package com.example.gemini.ui.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.ClaudeTerracotta
import java.io.File

sealed class DiffLineType {
    object HunkHeader : DiffLineType()
    object Addition : DiffLineType()
    object Deletion : DiffLineType()
    object Context : DiffLineType()
    object Meta : DiffLineType()
}

data class ParsedDiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLineNum: Int? = null,
    val newLineNum: Int? = null
)

@Composable
fun UnifiedDiffViewer(
    filePath: String,
    rawDiff: String,
    isStaged: Boolean,
    commitHash: String? = null,
    onOpenCurrentFile: () -> Unit,
    onOpenHistoricalFile: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fileName = remember(filePath) { File(filePath).name }

    val parsedLines = remember(rawDiff) {
        parseUnifiedDiff(rawDiff)
    }

    val additionsCount = remember(parsedLines) {
        parsedLines.count { it.type == DiffLineType.Addition }
    }
    val deletionsCount = remember(parsedLines) {
        parsedLines.count { it.type == DiffLineType.Deletion }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(com.example.gemini.theme.AppColors.editorBg)
    ) {
        // --- 1. Diff Header Toolbar ---
        Surface(
            color = com.example.gemini.theme.AppColors.editorPanel,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Difference,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = fileName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = com.example.gemini.theme.AppColors.editorText,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when {
                                commitHash != null -> ClaudeTerracotta.copy(alpha = 0.25f)
                                isStaged -> com.example.gemini.theme.AppColors.success.copy(alpha = 0.25f)
                                else -> com.example.gemini.theme.AppColors.warning.copy(alpha = 0.25f)
                            }
                        ) {
                            Text(
                                text = when {
                                    commitHash != null -> "COMMIT ${commitHash.take(7)}"
                                    isStaged -> "STAGED"
                                    else -> "WORKING TREE"
                                },
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                color = when {
                                    commitHash != null -> ClaudeTerracotta
                                    isStaged -> com.example.gemini.theme.AppColors.success
                                    else -> com.example.gemini.theme.AppColors.warning
                                },
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = filePath,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = com.example.gemini.theme.AppColors.editorMuted
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Stats Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(com.example.gemini.theme.AppColors.editorBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = "+$additionsCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = com.example.gemini.theme.AppColors.success)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "-$deletionsCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = com.example.gemini.theme.AppColors.danger)
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 1. Open Current Working File (Editable)
                IconButton(
                    onClick = onOpenCurrentFile,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Open Current File (Editable)",
                        tint = com.example.gemini.theme.AppColors.success,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // 2. Open Revision / Commit File (Read-Only)
                IconButton(
                    onClick = onOpenHistoricalFile,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Open Revision File (Read-Only)",
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Diff",
                        tint = com.example.gemini.theme.AppColors.editorMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }


        // --- 2. Unified Diff Content ---
        if (parsedLines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No changes detected or binary file.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        } else {
            val horizontalScrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight()
                ) {
                    itemsIndexed(parsedLines) { _, line ->
                        DiffLineRow(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: ParsedDiffLine) {
    val (bgColor, textColor, gutterSign) = when (line.type) {
        DiffLineType.Addition -> Triple(com.example.gemini.theme.AppColors.editorAddedBg, com.example.gemini.theme.AppColors.editorAddedText, "+")
        DiffLineType.Deletion -> Triple(com.example.gemini.theme.AppColors.editorRemovedBg, com.example.gemini.theme.AppColors.editorRemovedText, "-")
        DiffLineType.HunkHeader -> Triple(com.example.gemini.theme.AppColors.editorInfoBg, com.example.gemini.theme.AppColors.editorInfoText, "@")
        DiffLineType.Meta -> Triple(com.example.gemini.theme.AppColors.editorPanel, com.example.gemini.theme.AppColors.editorMuted, " ")
        DiffLineType.Context -> Triple(Color.Transparent, com.example.gemini.theme.AppColors.editorText, " ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Line numbers column
        Row(
            modifier = Modifier
                .width(68.dp)
                .background(com.example.gemini.theme.AppColors.editorBg)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = line.oldLineNum?.toString() ?: "",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = com.example.gemini.theme.AppColors.editorMuted
            )
            Text(
                text = line.newLineNum?.toString() ?: "",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = com.example.gemini.theme.AppColors.editorMuted
            )
        }

        // Gutter indicator (+ / - / @)
        Text(
            text = gutterSign,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier
                .width(16.dp)
                .padding(start = 4.dp)
        )

        // Code line text
        Text(
            text = line.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = textColor,
            softWrap = false,
            modifier = Modifier.padding(start = 4.dp, end = 16.dp)
        )
    }
}

private fun parseUnifiedDiff(diffText: String): List<ParsedDiffLine> {
    if (diffText.isBlank()) return emptyList()

    val result = mutableListOf<ParsedDiffLine>()
    val lines = diffText.split("\n")

    var oldLineTracker = 0
    var newLineTracker = 0

    val hunkRegex = Regex("""^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@(.*)""")

    for (raw in lines) {
        // Ignore git special escape markers like "\ No newline at end of file"
        if (raw.startsWith("\\")) {
            continue
        }

        if (raw.startsWith("diff --git") || raw.startsWith("index ") ||
            raw.startsWith("---") || raw.startsWith("+++") ||
            raw.startsWith("new file mode") || raw.startsWith("deleted file mode")) {
            result.add(ParsedDiffLine(type = DiffLineType.Meta, text = raw))
            continue
        }

        val hunkMatch = hunkRegex.find(raw)
        if (hunkMatch != null) {
            oldLineTracker = hunkMatch.groupValues[1].toIntOrNull() ?: 1
            newLineTracker = hunkMatch.groupValues[2].toIntOrNull() ?: 1
            result.add(ParsedDiffLine(type = DiffLineType.HunkHeader, text = raw))
            continue
        }

        if (raw.startsWith("+")) {
            val content = raw.removePrefix("+")
            result.add(
                ParsedDiffLine(
                    type = DiffLineType.Addition,
                    text = content,
                    oldLineNum = null,
                    newLineNum = newLineTracker
                )
            )
            newLineTracker++
        } else if (raw.startsWith("-")) {
            val content = raw.removePrefix("-")
            result.add(
                ParsedDiffLine(
                    type = DiffLineType.Deletion,
                    text = content,
                    oldLineNum = oldLineTracker,
                    newLineNum = null
                )
            )
            oldLineTracker++
        } else {
            val content = if (raw.startsWith(" ")) raw.removePrefix(" ") else raw
            result.add(
                ParsedDiffLine(
                    type = DiffLineType.Context,
                    text = content,
                    oldLineNum = if (oldLineTracker > 0) oldLineTracker else null,
                    newLineNum = if (newLineTracker > 0) newLineTracker else null
                )
            )
            if (oldLineTracker > 0) oldLineTracker++
            if (newLineTracker > 0) newLineTracker++
        }
    }

    return result
}
