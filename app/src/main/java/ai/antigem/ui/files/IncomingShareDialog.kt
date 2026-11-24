package ai.antigem.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import ai.antigem.data.files.*
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

private const val MAX_EDITOR_TEXT = 20L * 1024 * 1024
private const val MAX_EDITOR_BINARY = 2L * 1024 * 1024

private sealed class ShareStep {
    object Choose : ShareStep()
    object ConfirmBinary : ShareStep()
    object PickFolder : ShareStep()
    data class Saving(val index: Int, val total: Int, val name: String, val progress: Float?) : ShareStep()
    data class Saved(val folder: String, val paths: List<String>, val failed: List<String>) : ShareStep()
}

/**
 * Shown when another app shares or opens files with AntiGem: open in the code editor, preview Markdown, or save into
 * any folder (streamed through the IDE bridge, so it also works for a Linux distro or a remote server).
 */
@Composable
fun IncomingShareDialog(
    items: List<IncomingItem>,
    onOpenInEditor: (IncomingItem) -> Unit,
    onPreviewMarkdown: (IncomingItem) -> Unit,
    onOpenSavedInIde: (path: String, name: String, size: Long) -> Unit,
    onShowInFiles: (folder: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<ShareStep>(ShareStep.Choose) }
    val single = items.singleOrNull()
    val totalSize = items.sumOf { it.size.coerceAtLeast(0) }

    fun save(folder: String, singleName: String?) {
        scope.launch {
            val saved = mutableListOf<String>()
            val failed = mutableListOf<String>()
            items.forEachIndexed { i, item ->
                val name = if (items.size == 1 && !singleName.isNullOrBlank()) singleName else item.name
                step = ShareStep.Saving(i, items.size, name, null)
                val r = FileExplorerApi.upload(
                    input = {
                        item.text?.byteInputStream()
                            ?: context.contentResolver.openInputStream(item.uri!!)
                            ?: throw java.io.IOException("Cannot read $name")
                    },
                    length = item.size,
                    targetPath = "${folder.trimEnd('/')}/$name",
                    conflict = "rename"
                ) { sent -> if (item.size > 0) step = ShareStep.Saving(i, items.size, name, (sent.toFloat() / item.size).coerceIn(0f, 1f)) }
                if (r.ok && r.path != null) saved += r.path else failed += "$name: ${r.error ?: "failed"}"
            }
            step = ShareStep.Saved(folder, saved, failed)
        }
    }

    when (val s = step) {
        ShareStep.PickFolder -> {
            if (single != null) {
                FileExplorerDialog(
                    stateKey = "import",
                    title = L.files.incomingShare.saveSharedFile,
                    saveFileName = single.name,
                    onSave = { folder, name -> save(folder, name) },
                    showClipboard = false,
                    onDismiss = { step = ShareStep.Choose }
                )
            } else {
                FileExplorerDialog(
                    stateKey = "import",
                    title = L.files.incomingShare.saveItemscountSharedFilesTo(items.size),
                    confirmLabel = L.files.incomingShare.saveHere,
                    onConfirmFolder = { folder -> save(folder, null) },
                    showClipboard = false,
                    onDismiss = { step = ShareStep.Choose }
                )
            }
        }

        ShareStep.ConfirmBinary -> {
            val item = single ?: return
            val tooBig = item.size > MAX_EDITOR_BINARY
            AlertDialog(
                onDismissRequest = { step = ShareStep.Choose },
                icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text(if (tooBig) L.files.incomingShare.tooLargeForTheEditor else L.files.incomingShare.thisMayNotWork, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        if (tooBig) L.files.incomingShare.nameIsNotATextFile(item.name, FileKinds.formatSize(item.size))
                        else L.files.incomingShare.nameIsLabelNotTextThe(item.name, item.kind.label.lowercase()),
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    if (tooBig) Button(onClick = { step = ShareStep.PickFolder }) { Text(L.files.incomingShare.saveToFolder) }
                    else TextButton(onClick = { onOpenInEditor(item); onDismiss() }) { Text(L.files.incomingShare.openAnyway, color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { step = ShareStep.Choose }) { Text(L.common.back) }
                        if (!tooBig) Button(onClick = { step = ShareStep.PickFolder }) { Text(L.files.incomingShare.saveInstead) }
                    }
                }
            )
        }

        is ShareStep.Saving -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Outlined.Download, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(if (s.total > 1) L.files.incomingShare.savingValueOfTotal(s.index + 1, s.total) else L.files.incomingShare.saving, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    val p = s.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {}
        )

        is ShareStep.Saved -> {
            val savedOne = s.paths.singleOrNull()
            val textual = savedOne != null && FileKinds.of(savedOne.substringAfterLast('/')).isTextual
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        if (s.failed.isEmpty()) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                        tint = if (s.failed.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text(
                        when {
                            s.paths.isEmpty() -> L.files.incomingShare.nothingWasSaved
                            s.failed.isEmpty() -> if (s.paths.size == 1) L.files.incomingShare.saved else L.files.incomingShare.savedPathscountFiles(s.paths.size)
                            else -> L.files.incomingShare.savedPathscountFailedcountFailed(s.paths.size, s.failed.size)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (savedOne != null) Text(savedOne, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (s.paths.isNotEmpty()) Text(L.files.incomingShare.inFolder(s.folder), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        s.failed.take(4).forEach { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    if (textual && savedOne != null) {
                        Button(onClick = {
                            onOpenSavedInIde(savedOne, savedOne.substringAfterLast('/'), single?.size ?: 0)
                            onDismiss()
                        }) { Text(L.files.incomingShare.openInIde) }
                    } else if (s.paths.isNotEmpty()) {
                        Button(onClick = { onShowInFiles(s.folder); onDismiss() }) { Text(L.files.incomingShare.showInFiles) }
                    } else {
                        Button(onClick = { step = ShareStep.Choose }) { Text(L.common.tryAgain) }
                    }
                },
                dismissButton = {
                    Row {
                        if (textual) TextButton(onClick = { onShowInFiles(s.folder); onDismiss() }) { Text(L.files.incomingShare.showInFiles) }
                        TextButton(onClick = onDismiss) { Text(L.common.done) }
                    }
                }
            )
        }

        ShareStep.Choose -> Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 520.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        if (single != null) L.files.incomingShare.fileReceived else L.files.incomingShare.itemscountFilesReceived(items.size),
                        fontSize = 21.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (single != null) L.files.incomingShare.whatWouldYouLikeToDo else L.files.incomingShare.formatsizeInTotalWhatWouldYou(FileKinds.formatSize(totalSize)),
                        fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(items) { IncomingPreview(it) }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (single != null) {
                        val k = single.kind
                        when {
                            k.isTextual -> {
                                if (single.size > MAX_EDITOR_TEXT) {
                                    ShareOption(Icons.Outlined.Code, L.files.incomingShare.openInCodeEditor, "Too large for the editor (${FileKinds.formatSize(single.size)})", enabled = false) {}
                                } else {
                                    ShareOption(Icons.Outlined.Code, L.files.incomingShare.openInCodeEditor, L.files.incomingShare.editNowSaveItAnywhereLater, recommended = true) { onOpenInEditor(single); onDismiss() }
                                }
                                if (k == FileKind.MARKDOWN && single.uri != null) {
                                    ShareOption(Icons.Outlined.Visibility, L.common.preview, L.files.incomingShare.renderedMarkdown) { onPreviewMarkdown(single); onDismiss() }
                                }
                            }
                            k == FileKind.IMAGE -> ShareOption(Icons.Outlined.Image, L.files.incomingShare.viewInIde, L.files.incomingShare.imageViewerWithZoom) {
                                if (single.size > MAX_EDITOR_TEXT) step = ShareStep.ConfirmBinary else { onOpenInEditor(single); onDismiss() }
                            }
                            else -> ShareOption(Icons.Outlined.Code, L.files.incomingShare.openInCodeEditor, L.files.incomingShare.notATextFileMayNot, warn = true) { step = ShareStep.ConfirmBinary }
                        }
                    }
                    ShareOption(
                        Icons.Outlined.DriveFolderUpload,
                        if (single != null) L.files.incomingShare.saveToAFolder else L.files.incomingShare.saveAllToAFolder,
                        L.files.incomingShare.pickAnyFolderLikeAProject,
                        recommended = single == null || !single.kind.isTextual
                    ) { step = ShareStep.PickFolder }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(L.common.cancel) }
                }
            }
        }
    }
}

@Composable
private fun IncomingPreview(item: IncomingItem) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val kind = item.kind
        val tint = FileKinds.color(kind, item.name, MaterialTheme.colorScheme.primary)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            val icon: @Composable () -> Unit = { Icon(FileKinds.icon(kind, item.name), null, tint = tint, modifier = Modifier.size(23.dp)) }
            if (kind == FileKind.IMAGE && item.uri != null) {
                SubcomposeAsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, loading = { icon() }, error = { icon() }, modifier = Modifier.fillMaxSize())
            } else icon()
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                listOfNotNull(kind.label, if (item.size >= 0) FileKinds.formatSize(item.size) else null).joinToString("  ·  "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            item.text?.let { Text(it.lineSequence().firstOrNull { l -> l.isNotBlank() }?.take(80) ?: "", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ShareOption(icon: ImageVector, title: String, subtitle: String, recommended: Boolean = false, warn: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (recommended) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (recommended) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
                Text(subtitle, fontSize = 12.5.sp, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
