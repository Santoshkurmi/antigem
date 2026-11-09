package com.example.gemini.data.files

import android.content.Context
import java.io.File

/**
 * Keeps the app's temporary copies small: videos fetched from the bridge, chat attachments, and files copied so another
 * app can open or share them. Files older than a day go, then the oldest until the folder fits its budget. The newest
 * file always stays, since something (a player, a share target) may be using it right now.
 */
object CacheJanitor {

    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val MAX_BYTES = 512L * 1024 * 1024
    /** An unfinished download this old was abandoned (the app was killed mid-way). */
    private const val PART_MAX_AGE_MS = 60L * 60 * 1000
    private const val MIN_INTERVAL_MS = 60_000L

    val FOLDERS = listOf("bridge-media", "claude-attachments", "explorer_open")

    @Volatile private var lastRun = 0L

    /** Trims every temporary folder; calls closer than a minute apart are skipped unless [force]. */
    fun trimAll(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < MIN_INTERVAL_MS) return
        lastRun = now
        FOLDERS.forEach { trim(File(context.cacheDir, it)) }
    }

    fun trim(dir: File, maxBytes: Long = MAX_BYTES) {
        if (!dir.isDirectory) return
        val now = System.currentTimeMillis()
        val files = dir.walkBottomUp().filter { it.isFile }.toMutableList()
        files.removeAll { f ->
            val age = now - f.lastModified()
            val stale = if (f.name.endsWith(".part")) age > PART_MAX_AGE_MS else age > MAX_AGE_MS
            stale && f.delete()
        }
        // newest first: keep adding until the budget is used up, then drop the rest
        var total = 0L
        files.filter { !it.name.endsWith(".part") }.sortedByDescending { it.lastModified() }.forEachIndexed { i, f ->
            total += f.length()
            if (i > 0 && total > maxBytes) f.delete()
        }
        dir.walkBottomUp().filter { it.isDirectory && it != dir }.forEach { if (it.list().isNullOrEmpty()) it.delete() }
    }

    /** Marks a cached file as just used, so the size trim removes it last. */
    fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }
}
