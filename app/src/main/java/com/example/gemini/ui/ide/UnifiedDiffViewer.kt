package com.example.gemini.ui.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
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
    onStageToggle: () -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fileName = remember(filePath) { File(filePath).name }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }

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
            .background(Color(0xFF1E1E1E))
    ) {
        // --- 1. Diff Header Toolbar ---
        Surface(
            color = Color(0xFF252526),
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
                            color = Color(0xFFCCCCCC),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isStaged) Color(0xFF2E7D32).copy(alpha = 0.25f) else Color(0xFFE65100).copy(alpha = 0.25f)
                        ) {
                            Text(
                                text = if (isStaged) "STAGED" else "WORKING TREE",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                color = if (isStaged) Color(0xFF81C784) else Color(0xFFFFB74D),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = filePath,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFF888888)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Stats Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = "+$additionsCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "-$deletionsCount", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Stage / Unstage Action
                IconButton(
                    onClick = onStageToggle,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isStaged) Icons.Default.Remove else Icons.Default.Add,
                        contentDescription = if (isStaged) "Unstage" else "Stage",
                        tint = if (isStaged) Color(0xFFE57373) else Color(0xFF81C784),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Discard Action (if unstaged)
                if (!isStaged) {
                    IconButton(
                        onClick = { showDiscardConfirmDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Discard Changes",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Diff",
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Discard Confirmation Dialog
        if (showDiscardConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardConfirmDialog = false },
                title = { Text("Discard Changes?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                text = {
                    Text(
                        "Are you sure you want to discard all changes in $fileName? This cannot be undone.",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDiscardConfirmDialog = false
                            onDiscard()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Discard", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
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
        DiffLineType.Addition -> Triple(Color(0xFF1B4728), Color(0xFFB9F6CA), "+")
        DiffLineType.Deletion -> Triple(Color(0xFF4B1E1E), Color(0xFFFFCDD2), "-")
        DiffLineType.HunkHeader -> Triple(Color(0xFF1E293B), Color(0xFF90CAF9), "@")
        DiffLineType.Meta -> Triple(Color(0xFF252526), Color(0xFF757575), " ")
        DiffLineType.Context -> Triple(Color.Transparent, Color(0xFFD4D4D4), " ")
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
                .background(Color(0xFF181818))
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = line.oldLineNum?.toString() ?: "",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF6E7681)
            )
            Text(
                text = line.newLineNum?.toString() ?: "",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF6E7681)
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
