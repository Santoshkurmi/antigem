package ai.antigem.data.local

import android.os.Environment
import android.os.StatFs
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File

/**
 * Free storage before a big install (a full phone gets "free up X" instead of a half-done install), and a plain
 * message when a write runs out of space anyway (ENOSPC).
 */
object StorageSpace {
    /** Kept free after an install: apt / npm caches and temp files during it, and room for the app to keep working. */
    const val MARGIN_BYTES = 200L * 1024 * 1024

    /** Free bytes on [dir]'s partition (/data when [dir] can't be read, e.g. a root-only chroot path); -1 when unknown. */
    fun freeBytes(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }
            .recoverCatching { StatFs(Environment.getDataDirectory().absolutePath).availableBytes }
            .getOrDefault(-1L)

    /**
     * Null when [needed] bytes fit on [dir]'s partition (or its free space can't be read), else the message to show.
     * [reclaimable]: bytes the install deletes before it writes (an old copy it replaces).
     */
    fun shortage(dir: File, needed: Long, what: String, reclaimable: Long = 0L): String? {
        val free = freeBytes(dir)
        if (free < 0 || free + reclaimable >= needed) return null
        val have = free + reclaimable
        return "Not enough storage for $what: it needs about ${format(needed)} free and the phone has ${format(have)}. " +
            "Free up at least ${format(needed - have)}, then try again."
    }

    /** True when [e] or one of its causes is a write that failed because the storage is full. */
    fun isOutOfSpace(e: Throwable?): Boolean = generateSequence(e) { it.cause }.take(8).any {
        (it is ErrnoException && it.errno == OsConstants.ENOSPC) || isOutOfSpaceLine(it.message)
    }

    /** True for an output line that means the storage is full: the kernel's ENOSPC text, or apt's own free-space check. */
    fun isOutOfSpaceLine(line: String?): Boolean = line != null && (
        line.contains("ENOSPC") ||
            line.contains("No space left on device", ignoreCase = true) ||
            line.contains("You don't have enough free space", ignoreCase = true))

    /** [what]: what was being installed; null for any task (a script, a build). */
    fun outOfSpaceMessage(what: String? = null): String =
        "The phone ran out of storage${what?.let { " while installing $it" } ?: ""}. Free up some space, then try again."

    /** An install step's size label ("≈150 MB", "≈1.5 GB", "<1 MB") in bytes; 0 when it has no size. */
    fun parseSize(label: String): Long {
        val m = Regex("([0-9]+(?:[.][0-9]+)?) *(KB|MB|GB)", RegexOption.IGNORE_CASE).find(label) ?: return 0L
        val unit = when (m.groupValues[2].uppercase()) {
            "GB" -> 1024.0 * 1024 * 1024
            "MB" -> 1024.0 * 1024
            else -> 1024.0
        }
        return (m.groupValues[1].toDouble() * unit).toLong()
    }

    fun format(bytes: Long): String = LocalEnvironmentManager.formatFileSize(bytes)
}
