package com.example.gemini.ui.files

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.gemini.data.files.ExplorerEntry
import com.example.gemini.data.files.ExplorerPrefs
import com.example.gemini.data.files.FileExplorerApi
import com.example.gemini.data.files.FileKind
import com.example.gemini.data.files.FileKinds
import com.example.gemini.data.files.FileOpener
import com.example.gemini.data.files.FileProbe
import com.example.gemini.data.files.IdeFileLauncher
import com.example.gemini.data.files.OpenChoice
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.ui.components.AppToastHelper
import com.example.gemini.ui.components.ChatToastType
import com.example.gemini.ui.components.FullscreenVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Opens file paths tapped outside the Files screen (chat messages, tool output, artifacts). Text opens straight in the
 * code editor; anything else (video, audio, PDF, archive, binary) shows the same "Open with" sheet as Files, so a video
 * plays in the user's own player instead of being loaded into the editor. Text vs not-text is read from the file's
 * first bytes, not its extension. Draw [FileLinkOpenerHost] once next to where [open] is called.
 */
@Stable
class FileLinkOpener internal constructor(private val context: Context, private val scope: CoroutineScope) {

    internal var onOpenedInIde: () -> Unit = {}
    internal var onPreviewMarkdown: (path: String) -> Unit = {}
    internal var onShowFolder: ((path: String) -> Unit)? = null

    internal var busy by mutableStateOf<String?>(null)
    internal var progress by mutableStateOf<Float?>(null)
    internal var sheet by mutableStateOf<SheetRequest?>(null)
    internal var archive by mutableStateOf<FileProbe.Result.Found?>(null)
    internal var extractTo by mutableStateOf<FileProbe.Result.Found?>(null)
    private var job: Job? = null

    internal data class SheetRequest(val file: FileProbe.Result.Found, val ide: Boolean = true)

    /** A tapped path: text opens in the editor, anything else asks how to open it. */
    fun open(path: String) = start(path, alwaysAsk = false)

    /** Always shows the choices, text files included ("Open with…"). */
    fun openWith(path: String) = start(path, alwaysAsk = true)

    private fun start(path: String, alwaysAsk: Boolean) {
        if (job?.isActive == true) return
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        job = scope.launch {
            busy = "Opening $name…"
            progress = null
            val r = try { FileProbe.probe(path) } finally { busy = null }
            when (r) {
                is FileProbe.Result.Unavailable -> toast(r.message)
                is FileProbe.Result.Folder -> onShowFolder?.invoke(r.path) ?: toast("$name is a folder")
                is FileProbe.Result.Found -> {
                    val remembered = ExplorerPrefs.rememberedChoice(FileKinds.extOf(r.name))
                    when {
                        alwaysAsk -> sheet = SheetRequest(r)
                        r.textual -> openInIde(r, force = false)
                        remembered != null && (remembered != OpenChoice.PREVIEW || canPreview(r)) -> performNow(r, remembered)
                        else -> sheet = SheetRequest(r)
                    }
                }
            }
        }
    }

    /** Archive contents and extraction run on the bridge, which cannot see a file only this phone has. */
    internal fun canPreview(f: FileProbe.Result.Found): Boolean =
        f.textual || FileKinds.of(f.name) == FileKind.MARKDOWN || FileKinds.of(f.name) == FileKind.VIDEO ||
            !f.local || !AuthPreferences.isRemoteEndpoint()

    internal fun perform(f: FileProbe.Result.Found, choice: OpenChoice) = launchJob { performNow(f, choice) }

    private suspend fun performNow(f: FileProbe.Result.Found, choice: OpenChoice) {
        when (choice) {
            // the sheet already warned when it is not text, so no second question
            OpenChoice.IDE -> openInIde(f, force = true)
            OpenChoice.EXTERNAL -> openExternal(f)
            OpenChoice.PREVIEW -> when (FileKinds.of(f.name)) {
                FileKind.MARKDOWN -> onPreviewMarkdown(f.path)
                FileKind.VIDEO -> FullscreenVideo.play(context, f.path, f.name)
                else -> archive = f
            }
        }
    }

    private fun launchJob(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch { block() }
    }

    private suspend fun openInIde(f: FileProbe.Result.Found, force: Boolean) {
        busy = "Opening ${f.name}…"
        progress = null
        val outcome = try {
            if (f.local) IdeFileLauncher.openLocal(f.path, f.name, f.size, f.textual, f.mime, force)
            else IdeFileLauncher.open(f.path, f.name, f.size, force, textual = f.textual)
        } finally {
            busy = null
        }
        when (outcome) {
            is IdeFileLauncher.Outcome.Opened -> onOpenedInIde()
            // the bridge read it and found binary content after all
            is IdeFileLauncher.Outcome.NeedsConfirm -> sheet = SheetRequest(f.copy(textual = false))
            is IdeFileLauncher.Outcome.Failed -> {
                toast(outcome.message)
                // too big for the editor: another app may still open it
                if (!IdeFileLauncher.fitsEditor(f.name, f.size, f.textual)) sheet = SheetRequest(f, ide = false)
            }
        }
    }

    private suspend fun openExternal(f: FileProbe.Result.Found) {
        busy = "Preparing ${f.name}…"
        progress = null
        val uri = try {
            if (f.local) runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(f.path)) }
            else FileOpener.uriFor(context, f.path, f.size) { progress = it }
        } finally {
            busy = null
        }
        uri.onSuccess { FileOpener.view(context, it, f.name, contentMime = f.mime)?.let(::toast) }
            .onFailure { toast(it.message ?: "Could not open ${f.name}") }
    }

    internal fun extract(f: FileProbe.Result.Found, targetDir: String?) = launchJob {
        busy = "Extracting ${f.name}…"
        progress = null
        val r = try { FileExplorerApi.extract(f.path, targetDir) } finally { busy = null }
        if (r.ok) {
            AppToastHelper.showToast("Extracted ${r.count} files", ChatToastType.SUCCESS)
            r.path?.let { dir -> onShowFolder?.invoke(dir) }
        } else {
            toast(r.error ?: "Extract failed")
        }
    }

    internal fun cancel() {
        job?.cancel()
        busy = null
        progress = null
    }

    private fun toast(message: String) = AppToastHelper.showToast(message, ChatToastType.ERROR)
}

/**
 * [onOpenedInIde] runs after a file opened in the editor (switch to the IDE). [onPreviewMarkdown] shows the Markdown
 * viewer. [onShowFolder] opens a folder in Files; without it, tapping a folder path just says it is a folder.
 */
@Composable
fun rememberFileLinkOpener(
    onOpenedInIde: () -> Unit,
    onPreviewMarkdown: (path: String) -> Unit,
    onShowFolder: ((path: String) -> Unit)? = null
): FileLinkOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val opener = remember(context, scope) { FileLinkOpener(context, scope) }
    opener.onOpenedInIde = onOpenedInIde
    opener.onPreviewMarkdown = onPreviewMarkdown
    opener.onShowFolder = onShowFolder
    return opener
}

@Composable
fun FileLinkOpenerHost(opener: FileLinkOpener) {
    opener.sheet?.let { req ->
        val f = req.file
        val entry = remember(f) { ExplorerEntry(f.name, f.path, isDir = false, size = f.size) }
        OpenWithSheet(
            entry = entry,
            ideAvailable = req.ide,
            contentIsText = f.textual,
            allowPreview = opener.canPreview(f),
            onChoose = { choice, rememberIt ->
                opener.sheet = null
                val ext = FileKinds.extOf(f.name)
                if (rememberIt && ext.isNotBlank()) {
                    ExplorerPrefs.rememberChoice(ext, choice)
                    AppToastHelper.showToast(".$ext files now open with: ${choice.label}. Change it in Files › Sort & view.", ChatToastType.INFO)
                }
                opener.perform(f, choice)
            },
            onDismiss = { opener.sheet = null }
        )
    }

    opener.archive?.let { f ->
        val entry = remember(f) { ExplorerEntry(f.name, f.path, isDir = false, size = f.size) }
        ArchiveSheet(
            entry = entry,
            onExtractHere = { opener.archive = null; opener.extract(f, null) },
            onExtractTo = { opener.archive = null; opener.extractTo = f },
            onDismiss = { opener.archive = null }
        )
    }

    opener.extractTo?.let { f ->
        FileExplorerDialog(
            stateKey = "destination",
            initialPath = f.path.substringBeforeLast('/', "").ifBlank { null },
            title = "Extract \"${f.name}\" to",
            confirmLabel = "Extract here",
            showClipboard = false,
            onConfirmFolder = { dest -> opener.extractTo = null; opener.extract(f, dest) },
            onDismiss = { opener.extractTo = null }
        )
    }

    opener.busy?.let { label ->
        // quick checks finish before this shows, so taps on small files do not flash a dialog
        var visible by remember(label) { mutableStateOf(false) }
        LaunchedEffect(label) {
            delay(350)
            visible = true
        }
        if (visible) BusyDialog(label, opener.progress, onCancel = opener::cancel)
    }
}

@Composable
private fun BusyDialog(label: String, progress: Float?, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(min = 260.dp, max = 360.dp)) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (progress == null) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    } else {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                        if (progress != null) {
                            Text("${(progress * 100).toInt()}%  ·  copying to this phone", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (progress != null) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End).padding(top = 6.dp)) { Text("Cancel") }
            }
        }
    }
}
