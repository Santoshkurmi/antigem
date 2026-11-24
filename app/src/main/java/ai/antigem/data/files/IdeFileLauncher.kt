package ai.antigem.data.files

import ai.antigem.data.daemon.FileReadOutcome
import ai.antigem.data.daemon.IdeApiClient
import ai.antigem.data.daemon.ProjectItem
import ai.antigem.data.daemon.IdeDaemonManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import ai.antigem.i18n.L

/** Opens bridge files and folders in the IDE, with the size and file-type checks the editor needs. */
object IdeFileLauncher {

    /** Text above this is too heavy for the editor (it keeps the whole file in memory, more than once). */
    const val MAX_TEXT_BYTES = 5L * 1024 * 1024

    /** Non-text files opened as text anyway: above this they would only show megabytes of garbage. */
    private const val MAX_BINARY_BYTES = 2L * 1024 * 1024

    /** Pictures shown by the image viewer (it loads them itself, scaled to the screen). */
    const val MAX_IMAGE_BYTES = 15L * 1024 * 1024

    /** The picture types the IDE shows in its image viewer instead of the editor. */
    val VIEWER_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "ico")

    fun isViewerImage(name: String): Boolean = FileKinds.extOf(name) in VIEWER_IMAGE_EXTS

    sealed class Outcome {
        object Opened : Outcome()

        /** Not a text file: ask the user before opening anyway (call [open] again with force = true). */
        data class NeedsConfirm(val mimeType: String) : Outcome()

        /** [tooLarge]: refused for its size, the file itself is fine (it can be shared or opened with another app). */
        data class Failed(val message: String, val tooLarge: Boolean = false) : Outcome()
    }

    private fun tooLarge(name: String, size: Long, limit: Long, what: String) = Outcome.Failed(
        L.files.ideFileLauncher.nameIsTooLargeToOpen2(name, FileKinds.formatSize(size), what, FileKinds.formatSize(limit)),
        tooLarge = true
    )

    private fun sizeProblem(name: String, size: Long, textual: Boolean): Outcome.Failed? = when {
        isViewerImage(name) -> if (size > MAX_IMAGE_BYTES) tooLarge(name, size, MAX_IMAGE_BYTES, L.files.ideFileLauncher.pictures) else null
        size > MAX_TEXT_BYTES -> tooLarge(name, size, MAX_TEXT_BYTES, L.files.ideFileLauncher.textFiles)
        !textual && size > MAX_BINARY_BYTES -> Outcome.Failed(
            L.files.ideFileLauncher.nameIsNotATextFile(name, FileKinds.formatSize(size)),
            tooLarge = true
        )
        else -> null
    }

    /** False when the editor would refuse the file for its size. */
    fun fitsEditor(name: String, size: Long, textual: Boolean): Boolean = sizeProblem(name, size, textual) == null

    /**
     * [size]: what the caller knows (-1 when nothing); the bridge's own stat wins. [textual]: what the file's content
     * says, when already known; otherwise the bridge's look at the first bytes, then the extension, decide.
     * Nothing bigger than the limits is ever downloaded, and a non-text file is not downloaded before the user agrees.
     */
    suspend fun open(path: String, name: String, size: Long, force: Boolean = false, textual: Boolean? = null): Outcome {
        val kind = FileKinds.of(name)
        val stat = FileExplorerApi.stat(path).getOrNull()?.takeIf { !it.isDir }
        val isText = when (stat?.category) {
            "text" -> true
            "image", "binary" -> false
            else -> textual ?: kind.isTextual
        }
        sizeProblem(name, stat?.size ?: size, isText)?.let { return it }
        if (isViewerImage(name)) {
            // the image viewer loads the picture by itself: nothing to download here
            IdeDaemonManager.openOrSelectTab(path, name, "")
            stat?.let { IdeDaemonManager.setDiskStamp(path, it.size, it.modTime) }
            return Outcome.Opened
        }
        // the bridge already looked at the first bytes: ask before downloading anything that is not text
        if (stat != null && !isText && !force && stat.category.isNotBlank()) return Outcome.NeedsConfirm(stat.sniffedMime.ifBlank { stat.mime })
        val limit = if (isText) MAX_TEXT_BYTES else MAX_BINARY_BYTES
        return when (val r = IdeApiClient.readFileLimited(path, limit)) {
            is FileReadOutcome.TooLarge ->
                if (r.size > 0) sizeProblem(name, r.size, isText) ?: tooLarge(name, r.size, limit, L.files.ideFileLauncher.filesLikeThis)
                else Outcome.Failed(L.files.ideFileLauncher.nameIsTooLargeToOpen(name, FileKinds.formatSize(limit)), tooLarge = true)
            is FileReadOutcome.Failed -> Outcome.Failed(L.files.ideFileLauncher.cannotOpenNameTheIdeBridge(name))
            is FileReadOutcome.Ok -> {
                if (r.result.category != "text" && !force) return Outcome.NeedsConfirm(r.result.mimeType)
                IdeDaemonManager.openOrSelectTab(path, name, r.result.content)
                stat?.let { IdeDaemonManager.setDiskStamp(path, it.size, it.modTime) }
                Outcome.Opened
            }
        }
    }

    /**
     * Opens a file the app reads by itself, which the bridge may not see (a download in the app's cache while the
     * bridge runs on a server). [textual] comes from the file's content; [force] skips the not-text question.
     */
    suspend fun openLocal(path: String, name: String, size: Long, textual: Boolean, mimeType: String = "", force: Boolean = false): Outcome {
        sizeProblem(name, size, textual)?.let { return it }
        // the IDE's image viewer loads the picture from the path itself
        val content = if (isViewerImage(name) && !textual) "" else {
            if (!textual && !force) return Outcome.NeedsConfirm(mimeType)
            withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrNull() }
                ?: return Outcome.Failed(L.files.ideFileLauncher.cannotReadName(name))
        }
        IdeDaemonManager.openOrSelectTab(path, name, content)
        return Outcome.Opened
    }

    fun openFolderAsProject(path: String) {
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        IdeDaemonManager.setActiveProject(ProjectItem(name = name, path = path))
    }
}
