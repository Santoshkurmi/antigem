package com.example.gemini.data.files

import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager

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

    suspend fun open(path: String, name: String, size: Long, force: Boolean = false): Outcome {
        val kind = FileKinds.of(name)
        if (size > MAX_TEXT_BYTES) return Outcome.Failed("$name is too large for the editor (${FileKinds.formatSize(size)})")
        if (!kind.isTextual && kind != FileKind.IMAGE && size > MAX_BINARY_BYTES) {
            return Outcome.Failed("$name is not a text file and too large to open in the editor (${FileKinds.formatSize(size)}). Open it with another app instead.")
        }
        val res = IdeApiClient.readFileDetailed(path)
            ?: return Outcome.Failed("Cannot open $name. The IDE bridge is offline or the file is unreadable.")
        if (res.category == "binary" && !force && kind != FileKind.IMAGE) return Outcome.NeedsConfirm(res.mimeType)
        TermuxDaemonManager.openOrSelectTab(path, name, res.content)
        return Outcome.Opened
    }

    fun openFolderAsProject(path: String) {
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        TermuxDaemonManager.setActiveProject(ProjectItem(name = name, path = path))
    }
}
