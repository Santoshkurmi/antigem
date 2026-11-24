package ai.antigem.ui.components

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
import ai.antigem.i18n.L

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
                val readStr = if (localFile.canRead()) L.components.fileDetails.yes else L.components.fileDetails.no
                val writeStr = if (localFile.canWrite()) L.components.fileDetails.yes else L.components.fileDetails.no
                val execStr = if (localFile.canExecute()) L.components.fileDetails.yes else L.components.fileDetails.no
                L.components.fileDetails.rWXReadReadstrWrite(r, w, x, readStr, writeStr, execStr)
            } catch (_: Exception) {
                L.components.fileDetails.readWriteAccessBridgeManaged
            }
        } else {
            L.components.fileDetails.readWriteAccessBridgeManaged
        }
    }

    val ext = remember(name) { name.substringAfterLast('.', "").lowercase() }
    val typeName = remember(isDir, ext) {
        if (isDir) L.components.fileDetails.folderDirectory
        else when (ext) {
            "kt", "kts" -> L.components.fileDetails.kotlinSourceFileExt(ext)
            "java" -> L.components.fileDetails.javaSourceFileJava
            "py" -> L.components.fileDetails.pythonScriptPy
            "js" -> L.components.fileDetails.javascriptFileJs
            "ts" -> L.components.fileDetails.typescriptFileTs
            "jsx", "tsx" -> L.components.fileDetails.reactComponentExt(ext)
            "html", "htm" -> L.components.fileDetails.htmlDocumentExt(ext)
            "css", "scss", "sass" -> "Stylesheet (.${ext})"
            "json" -> L.components.fileDetails.jsonDataJson
            "xml" -> L.components.fileDetails.xmlDocumentXml
            "yaml", "yml" -> L.components.fileDetails.yamlConfigurationExt(ext)
            "toml" -> L.components.fileDetails.tomlConfigurationToml
            "md", "markdown" -> L.components.fileDetails.markdownDocumentExt(ext)
            "txt", "log" -> L.components.fileDetails.textDocumentExt(ext)
            "sh", "bash", "zsh" -> L.components.fileDetails.shellScriptExt(ext)
            "c", "cpp", "h", "hpp" -> L.components.fileDetails.cCSourceExt(ext)
            "go" -> L.components.fileDetails.goSourceFileGo
            "rs" -> L.components.fileDetails.rustSourceFileRs
            "png", "jpg", "jpeg", "webp", "gif", "svg" -> L.components.fileDetails.imageAssetExt(ext)
            "zip", "tar", "gz", "rar" -> L.components.fileDetails.archiveFileExt(ext)
            "" -> L.common.file
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
                                text = if (isDir) L.components.fileDetails.directory else L.components.fileDetails.file,
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
                    label = L.components.fileDetails.type,
                    value = typeName
                )

                Spacer(modifier = Modifier.height(10.dp))

                DetailRow(
                    icon = Icons.Outlined.FolderOpen,
                    label = L.common.location,
                    value = path,
                    isMonospace = true,
                    actionIcon = Icons.Default.ContentCopy,
                    actionDescription = L.components.fileDetails.copyPath,
                    onActionClick = {
                        clipboardManager.setText(AnnotatedString(path))
                        Toast.makeText(context, L.components.fileDetails.pathCopiedToClipboard, Toast.LENGTH_SHORT).show()
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                val parentPath = remember(path) { File(path).parent ?: "/" }
                DetailRow(
                    icon = Icons.Outlined.DriveFileMove,
                    label = L.components.fileDetails.parentDirectory,
                    value = parentPath,
                    isMonospace = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                val sizeText = remember(isDir, resolvedSize, childCount) {
                    if (isDir) {
                        if (childCount != null) "$childCount items"
                        else if (resolvedSize > 0) formatDetailedFileSize(resolvedSize)
                        else L.components.fileDetails.directory2
                    } else {
                        formatDetailedFileSize(resolvedSize)
                    }
                }
                DetailRow(
                    icon = Icons.Outlined.DataUsage,
                    label = L.common.size,
                    value = sizeText
                )

                Spacer(modifier = Modifier.height(10.dp))

                DetailRow(
                    icon = Icons.Outlined.Security,
                    label = L.common.permissions,
                    value = permissionsInfo,
                    isMonospace = true
                )

                if (resolvedModTime > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy · hh:mm:ss a", Locale.getDefault()) }
                    DetailRow(
                        icon = Icons.Outlined.Schedule,
                        label = L.components.fileDetails.lastModified,
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
                        Text(L.common.close, fontWeight = FontWeight.SemiBold)
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
    if (size <= 0) return L.components.fileDetails.n0B0Bytes
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val formattedVal = DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble()))
    val rawFormatted = NumberFormat.getNumberInstance(Locale.getDefault()).format(size)
    return if (digitGroups == 0) "$rawFormatted bytes" else "$formattedVal ${units[digitGroups]} ($rawFormatted bytes)"
}
