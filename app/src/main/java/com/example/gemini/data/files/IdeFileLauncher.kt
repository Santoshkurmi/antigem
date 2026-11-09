package com.example.gemini.data.files

import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Opens bridge files and folders in the IDE, with the size and file-type checks the editor needs. */
object IdeFileLauncher {

    /** Text above this is too heavy for the editor. */
    private const val MAX_TEXT_BYTES = 20L * 1024 * 1024

    /** Non-text files above this would only show megabytes of garbage. */
    private const val MAX_BINARY_BYTES = 2L * 1024 * 1024

    sealed class Outcome {
        object Opened : Outcome()

        /** Not a text file: ask the user before opening anyway (call [open] again with force = true). */
        data class NeedsConfirm(val mimeType: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private fun sizeProblem(name: String, size: Long, textual: Boolean, kind: FileKind): Outcome.Failed? = when {
        size > MAX_TEXT_BYTES -> Outcome.Failed("$name is too large for the editor (${FileKinds.formatSize(size)})")
        !textual && kind != FileKind.IMAGE && size > MAX_BINARY_BYTES ->
            Outcome.Failed("$name is not a text file and too large to open in the editor (${FileKinds.formatSize(size)}). Open it with another app instead.")
        else -> null
    }

    /** False when the editor would refuse the file for its size. */
    fun fitsEditor(name: String, size: Long, textual: Boolean): Boolean = sizeProblem(name, size, textual, FileKinds.of(name)) == null

    /** [textual]: what the file's content says, when already known; otherwise the extension decides the size limit. */
    suspend fun open(path: String, name: String, size: Long, force: Boolean = false, textual: Boolean? = null): Outcome {
        val kind = FileKinds.of(name)
        sizeProblem(name, size, textual ?: kind.isTextual, kind)?.let { return it }
        val res = IdeApiClient.readFileDetailed(path)
            ?: return Outcome.Failed("Cannot open $name. The IDE bridge is offline or the file is unreadable.")
        if (res.category == "binary" && !force && kind != FileKind.IMAGE) return Outcome.NeedsConfirm(res.mimeType)
        TermuxDaemonManager.openOrSelectTab(path, name, res.content)
        return Outcome.Opened
    }

    /**
     * Opens a file the app reads by itself, which the bridge may not see (a download in the app's cache while the
     * bridge runs on a server). [textual] comes from the file's content; [force] skips the not-text question.
     */
    suspend fun openLocal(path: String, name: String, size: Long, textual: Boolean, mimeType: String = "", force: Boolean = false): Outcome {
        val kind = FileKinds.of(name)
        sizeProblem(name, size, textual, kind)?.let { return it }
        // the IDE's image viewer loads the picture from the path itself
        val content = if (kind == FileKind.IMAGE && !textual) "" else {
            if (!textual && !force) return Outcome.NeedsConfirm(mimeType)
            withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrNull() }
                ?: return Outcome.Failed("Cannot read $name")
        }
        TermuxDaemonManager.openOrSelectTab(path, name, content)
        return Outcome.Opened
    }

    fun openFolderAsProject(path: String) {
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        TermuxDaemonManager.setActiveProject(ProjectItem(name = name, path = path))
    }
}
