package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileLinkHandler(
    val onOpenFile: (path: String) -> Unit = {},
    val onShowDetails: (path: String) -> Unit = {}
)

object ActiveFileLinkHandlerHolder {
    @Volatile
    var current: FileLinkHandler? = null
}

val LocalFileLinkHandler = staticCompositionLocalOf {
    FileLinkHandler()
}

data class FileDetailsInfo(
    val name: String,
    val fullPath: String,
    val extension: String,
    val sizeBytes: Long,
    val lineCount: Int?,
    val lastModified: String,
    val exists: Boolean,
    val isMarkdown: Boolean
)

fun extractFileDetails(rawPath: String): FileDetailsInfo {
    val cleanPath = rawPath.removePrefix("file://").substringBefore("#")
    val file = File(cleanPath)
    val name = file.name.ifBlank { cleanPath.substringAfterLast('/') }
    val ext = file.extension.lowercase()
    val isMd = ext == "md" || ext == "markdown"

    var lineCount: Int? = null
    var size = 0L
    var lastModStr = "Unknown"
    val exists = file.exists()

    if (exists) {
        size = file.length()
        try {
            if (file.isFile && size < 5 * 1024 * 1024) {
                lineCount = file.readLines().size
            }
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
            lastModStr = sdf.format(Date(file.lastModified()))
        } catch (e: Exception) {
            // Ignore read errors for system protected files
        }
    }

    return FileDetailsInfo(
        name = name,
        fullPath = cleanPath,
        extension = ext,
        sizeBytes = size,
        lineCount = lineCount,
        lastModified = lastModStr,
        exists = exists,
        isMarkdown = isMd
    )
}

@Composable
fun FileDetailsDialog(
    filePath: String,
    onDismiss: () -> Unit,
    onOpenInIde: (path: String) -> Unit,
    onOpenMarkdownViewer: (path: String) -> Unit
) {
    val context = LocalContext.current
    val details = remember(filePath) { extractFileDetails(filePath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ClaudeTerracotta.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (details.isMarkdown) Icons.Outlined.Description else Icons.Outlined.Code,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = details.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (details.extension.isNotBlank()) ".${details.extension} file" else "File reference",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Full Path:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = details.fullPath,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Size", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = formatFileSize(details.sizeBytes),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (details.lineCount != null) {
                        Column {
                            Text("Lines", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${details.lineCount} lines",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Column {
                        Text("Modified", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = details.lastModified.take(12),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("File Path", details.fullPath))
                        Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy Path")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = {
                        onDismiss()
                        if (details.isMarkdown) {
                            onOpenMarkdownViewer(details.fullPath)
                        } else {
                            onOpenInIde(details.fullPath)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Icon(
                        imageVector = if (details.isMarkdown) Icons.Outlined.Visibility else Icons.Outlined.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (details.isMarkdown) "Read Markdown" else "Open in IDE")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownDocViewerModal(
    filePath: String,
    content: String,
    onDismiss: () -> Unit,
    onOpenInIde: (path: String) -> Unit
) {
    val context = LocalContext.current
    val fileName = remember(filePath) { File(filePath).name }
    val blocks = remember(content) { parseMarkdownBlocks(content) }
    val listState = rememberLazyListState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top App Bar
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = filePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Markdown Content", content))
                                Toast.makeText(context, "Markdown content copied", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Content")
                        }
                        IconButton(
                            onClick = {
                                onDismiss()
                                onOpenInIde(filePath)
                            }
                        ) {
                            Icon(Icons.Outlined.Code, contentDescription = "Open in IDE", tint = ClaudeTerracotta)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Rendered Markdown Content
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(blocks) { idx, block ->
                        MarkdownBlockView(
                            block = block,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return "%.1f %s".format(bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
