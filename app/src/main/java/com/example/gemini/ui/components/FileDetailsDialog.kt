package com.example.gemini.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileDetailsDialog(
    name: String,
    path: String,
    isDir: Boolean,
    size: Long = 0L,
    modTime: Long = 0L,
    childCount: Int? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val localFile = remember(path) { File(path) }
    val existsLocally = remember(localFile) {
        try { localFile.exists() } catch (_: Exception) { false }
    }

    val resolvedSize = remember(size, existsLocally, localFile) {
        if (size > 0) size
        else if (existsLocally) {
            try { if (localFile.isFile) localFile.length() else 0L } catch (_: Exception) { 0L }
        } else 0L
    }

    val resolvedModTime = remember(modTime, existsLocally, localFile) {
        if (modTime > 0) modTime
        else if (existsLocally) {
            try { localFile.lastModified() } catch (_: Exception) { 0L }
        } else 0L
    }

    val permissionsInfo = remember(existsLocally, localFile) {
        if (existsLocally) {
            try {
                val r = if (localFile.canRead()) "r" else "-"
                val w = if (localFile.canWrite()) "w" else "-"
                val x = if (localFile.canExecute()) "x" else "-"
                val readStr = if (localFile.canRead()) "Yes" else "No"
                val writeStr = if (localFile.canWrite()) "Yes" else "No"
                val execStr = if (localFile.canExecute()) "Yes" else "No"
                "$r$w$x (Read: $readStr, Write: $writeStr, Exec: $execStr)"
            } catch (_: Exception) {
                "Read/Write Access (Bridge Managed)"
            }
        } else {
            "Read/Write Access (Bridge Managed)"
        }
    }

    val ext = remember(name) { name.substringAfterLast('.', "").lowercase() }
    val typeName = remember(isDir, ext) {
        if (isDir) "Folder (Directory)"
        else when (ext) {
            "kt", "kts" -> "Kotlin Source File (.${ext})"
            "java" -> "Java Source File (.java)"
            "py" -> "Python Script (.py)"
            "js" -> "JavaScript File (.js)"
            "ts" -> "TypeScript File (.ts)"
            "jsx", "tsx" -> "React Component (.${ext})"
            "html", "htm" -> "HTML Document (.${ext})"
            "css", "scss", "sass" -> "Stylesheet (.${ext})"
            "json" -> "JSON Data (.json)"
            "xml" -> "XML Document (.xml)"
            "yaml", "yml" -> "YAML Configuration (.${ext})"
            "toml" -> "TOML Configuration (.toml)"
            "md", "markdown" -> "Markdown Document (.${ext})"
            "txt", "log" -> "Text Document (.${ext})"
            "sh", "bash", "zsh" -> "Shell Script (.${ext})"
            "c", "cpp", "h", "hpp" -> "C/C++ Source (.${ext})"
            "go" -> "Go Source File (.go)"
            "rs" -> "Rust Source File (.rs)"
            "png", "jpg", "jpeg", "webp", "gif", "svg" -> "Image Asset (.${ext})"
            "zip", "tar", "gz", "rar" -> "Archive File (.${ext})"
            "" -> "File"
            else -> "${ext.uppercase()} File (.$ext)"
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header: Icon + Name + Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (isDir) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (isDir) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = if (isDir) "DIRECTORY" else "FILE",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(14.dp))

                // Detail Items
                DetailRow(
                    icon = Icons.Outlined.Category,
                    label = "Type",
                    value = typeName
                )

                Spacer(modifier = Modifier.height(10.dp))

                DetailRow(
                    icon = Icons.Outlined.FolderOpen,
                    label = "Location",
                    value = path,
                    isMonospace = true,
                    actionIcon = Icons.Default.ContentCopy,
                    actionDescription = "Copy Path",
                    onActionClick = {
                        clipboardManager.setText(AnnotatedString(path))
                        Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                val parentPath = remember(path) { File(path).parent ?: "/" }
                DetailRow(
                    icon = Icons.Outlined.DriveFileMove,
                    label = "Parent Directory",
                    value = parentPath,
                    isMonospace = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                val sizeText = remember(isDir, resolvedSize, childCount) {
                    if (isDir) {
                        if (childCount != null) "$childCount items"
                        else if (resolvedSize > 0) formatDetailedFileSize(resolvedSize)
                        else "Directory"
                    } else {
                        formatDetailedFileSize(resolvedSize)
                    }
                }
                DetailRow(
                    icon = Icons.Outlined.DataUsage,
                    label = "Size",
                    value = sizeText
                )

                Spacer(modifier = Modifier.height(10.dp))

                DetailRow(
                    icon = Icons.Outlined.Security,
                    label = "Permissions",
                    value = permissionsInfo,
                    isMonospace = true
                )

                if (resolvedModTime > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy · hh:mm:ss a", Locale.getDefault()) }
                    DetailRow(
                        icon = Icons.Outlined.Schedule,
                        label = "Last Modified",
                        value = dateFormat.format(Date(resolvedModTime))
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Actions: Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    isMonospace: Boolean = false,
    actionIcon: ImageVector? = null,
    actionDescription: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.5.sp,
                    fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp
                )
            }

            if (actionIcon != null && onActionClick != null) {
                IconButton(
                    onClick = onActionClick,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = actionDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

fun formatDetailedFileSize(size: Long): String {
    if (size <= 0) return "0 B (0 bytes)"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val formattedVal = DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble()))
    val rawFormatted = NumberFormat.getNumberInstance(Locale.getDefault()).format(size)
    return if (digitGroups == 0) "$rawFormatted bytes" else "$formattedVal ${units[digitGroups]} ($rawFormatted bytes)"
}
