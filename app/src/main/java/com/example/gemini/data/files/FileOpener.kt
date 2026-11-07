package com.example.gemini.data.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands files to other apps (video players, galleries, PDF readers, installers, share targets).
 * A file the app can read directly goes through FileProvider as is; anything else (a remote server, a Linux distro's
 * private folder) is first downloaded from the bridge into the app's cache.
 */
object FileOpener {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    private fun cacheDir(context: Context) = File(context.cacheDir, "explorer_open").apply { mkdirs() }

    /** Old downloads are cleared so the cache does not grow forever. */
    fun trimCache(context: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        cacheDir(context).listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.deleteRecursively() }
    }

    /** A content:// URI for [path], downloading it first if needed. */
    suspend fun uriFor(context: Context, path: String, size: Long = -1, onProgress: (Float) -> Unit = {}): Result<Uri> {
        val local = File(path)
        val file = if (FileExplorerApi.isLocallyReadable(path)) {
            local
        } else {
            trimCache(context)
            val dir = File(cacheDir(context), Integer.toHexString(path.hashCode())).apply { mkdirs() }
            val dest = File(dir, local.name.ifBlank { "file" })
            if (!(dest.exists() && size >= 0 && dest.length() == size)) {
                val res = FileExplorerApi.download(path, dest) { done, total ->
                    val t = if (total > 0) total else size
                    if (t > 0) onProgress((done.toFloat() / t).coerceIn(0f, 1f))
                }
                if (!res.ok) return Result.failure(Exception(res.error ?: "Download failed"))
            }
            dest
        }
        return runCatching { FileProvider.getUriForFile(context, authority(context), file) }
    }

    /** Opens in another app chosen by the system. Returns an error message, or null when an app was started. */
    fun view(context: Context, uri: Uri, name: String, forceChooser: Boolean = false): String? {
        val mime = FileKinds.mimeOf(name)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(name, uri)
        }
        return try {
            val start = if (forceChooser) Intent.createChooser(intent, "Open \"$name\" with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent
            context.startActivity(start)
            null
        } catch (_: ActivityNotFoundException) {
            // nothing handles the exact type: let the user pick from anything that opens files
            try {
                val generic = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(generic, "Open \"$name\" with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            } catch (_: Exception) {
                "No app on this phone can open $name"
            }
        } catch (e: Exception) {
            e.message ?: "Could not open $name"
        }
    }

    fun share(context: Context, uris: List<Pair<Uri, String>>): String? {
        if (uris.isEmpty()) return "Nothing to share"
        val mimes = uris.map { FileKinds.mimeOf(it.second) }.distinct()
        val mime = when {
            mimes.size == 1 -> mimes.first()
            mimes.all { it.startsWith("image/") } -> "image/*"
            mimes.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.first().first) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.map { it.first })) }
        }.apply {
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(uris.first().second, uris.first().first).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it.first)) } }
        }
        return try {
            context.startActivity(Intent.createChooser(intent, if (uris.size == 1) "Share ${uris.first().second}" else "Share ${uris.size} files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: Exception) {
            e.message ?: "Could not share"
        }
    }
}
