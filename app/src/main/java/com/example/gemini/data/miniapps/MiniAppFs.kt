package com.example.gemini.data.miniapps

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * AntiGem.fs: every path goes through [MiniAppPermissions.resolve] (own "data/" folder always; anything else only
 * inside what the storage permission granted, never other apps' data or the dot folders in home).
 */
object MiniAppFs {
    private const val MAX_READ = 64L * 1024 * 1024

    private fun info(f: File, path: String) = JSONObject()
        .put("name", f.name).put("path", path)
        .put("type", if (f.isDirectory) "dir" else "file")
        .put("size", if (f.isFile) f.length() else 0).put("mtime", f.lastModified())

    fun call(context: Context, app: MiniApp, method: String, p: JSONObject): Any? {
        val path = p.optString("path")
        return when (method) {
            "fs.read" -> {
                val f = MiniAppPermissions.resolve(context, app, path, MiniAppPermissions.Need.READ)
                if (!f.isFile) throw MiniAppError("NotFoundError", "No such file: $path")
                if (f.length() > MAX_READ) throw MiniAppError("QuotaExceededError", "File too large to read at once; use AntiGem.fs.url()")
                if (p.optString("enc") == "base64") Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else f.readText()
            }
            "fs.write" -> {
                val f = MiniAppPermissions.resolve(context, app, path, MiniAppPermissions.Need.WRITE)
                if (f.isDirectory) throw MiniAppError("InvalidStateError", "$path is a folder")
                f.parentFile?.mkdirs()
                val bytes = if (p.has("b64")) Base64.decode(p.getString("b64"), Base64.DEFAULT) else p.optString("text").toByteArray()
                if (p.optBoolean("append")) f.appendBytes(bytes) else {
                    // atomic: write a temp file, then rename over the old one
                    val tmp = File(f.parentFile, ".${f.name}.${System.nanoTime()}.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
                }
                null
            }
            "fs.list" -> {
                val f = MiniAppPermissions.resolve(context, app, path, MiniAppPermissions.Need.READ)
                if (!f.isDirectory) throw MiniAppError("NotFoundError", "No such folder: $path")
                val base = path.trimEnd('/')
                JSONArray().apply {
                    f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { child ->
                        val virtual = "$base/${child.name}"
                        // a symlink is shown as what it points to only when that is allowed too; otherwise nothing about
                        // its target (not even type or size) leaks
                        if (MiniAppPermissions.isSymlink(child)) {
                            val target = runCatching { MiniAppPermissions.resolve(context, app, virtual, MiniAppPermissions.Need.META) }.getOrNull()
                            put(if (target != null) info(target, virtual).put("name", child.name).put("link", true)
                                else JSONObject().put("name", child.name).put("path", virtual).put("type", "link").put("size", 0).put("mtime", 0).put("blocked", true))
                        } else put(info(child, virtual))
                    }
                }
            }
            "fs.stat" -> MiniAppPermissions.resolve(context, app, path, MiniAppPermissions.Need.META).let { if (it.exists()) info(it, path) else JSONObject.NULL }
            "fs.exists" -> MiniAppPermissions.resolve(context, app, path, MiniAppPermissions.Need.META).exists()
            "fs.mkdir" -> { MiniAppPermissions.resolve(context, app, path, MiniAppPermissions.Need.WRITE).mkdirs(); null }
            "fs.delete" -> {
                val f = MiniAppPermissions.resolve(context, app, path, MiniAppPermissions.Need.WRITE)
                if (MiniAppPermissions.isRoot(context, app, f)) throw MiniAppError("NotAllowedError", "$path is a root folder and cannot be deleted")
                if (!f.exists()) throw MiniAppError("NotFoundError", "No such file: $path")
                val ok = if (f.isDirectory && p.optBoolean("recursive")) deleteTree(f) else f.delete()
                if (!ok) throw MiniAppError("InvalidStateError", if (f.isDirectory) "Folder not empty (use {recursive: true})" else "Could not delete $path")
                null
            }
            "fs.move" -> {
                val from = MiniAppPermissions.resolve(context, app, p.optString("from"), MiniAppPermissions.Need.WRITE)
                val to = MiniAppPermissions.resolve(context, app, p.optString("to"), MiniAppPermissions.Need.WRITE)
                if (MiniAppPermissions.isRoot(context, app, from)) throw MiniAppError("NotAllowedError", "A root folder cannot be moved")
                if (!from.exists()) throw MiniAppError("NotFoundError", "No such file: ${p.optString("from")}")
                to.parentFile?.mkdirs()
                // across storages rename fails: copy without following symlinks (they could point anywhere), then delete
                if (!from.renameTo(to)) { copyTree(from, to); deleteTree(from) }
                null
            }
            else -> throw MiniAppError("NotSupportedError", "Unknown method $method")
        }
    }

    /** Deletes a folder tree; a symlink inside is removed itself, never followed (its target stays untouched). */
    private fun deleteTree(f: File): Boolean {
        if (!MiniAppPermissions.isSymlink(f) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete()
    }

    /** Copies a tree; symlinks are skipped (copying through them could pull in files from anywhere). */
    private fun copyTree(from: File, to: File) {
        if (MiniAppPermissions.isSymlink(from)) return
        if (from.isDirectory) { to.mkdirs(); from.listFiles()?.forEach { copyTree(it, File(to, it.name)) } }
        else from.copyTo(to, overwrite = true)
    }

    /** Folders the app can use beyond its own: [{path, access, source: "manifest" | "picked"}]. */
    fun folders(context: Context, app: MiniApp): JSONArray = JSONArray().apply {
        put(JSONObject().put("path", "data").put("access", "readwrite").put("source", "own"))
        // apps with the same shared_id (and signer): their data/ folders, as app:<id>
        MiniApps.sharedGroup(context, app, fresh = true).forEach {
            put(JSONObject().put("path", "app:${it.id}").put("access", "readwrite").put("source", "shared").put("app", JSONObject().put("id", it.id).put("name", it.name)))
        }
        MiniAppPermissions.grants(context, app).distinctBy { it.root.path }.forEach { put(JSONObject().put("path", it.root.path).put("access", it.access).put("source", it.source)) }
    }

    /** /_antigem/fs?p=<path>: streams a file (with Range support, so video seeking works). */
    fun serve(context: Context, app: MiniApp, path: String, range: String?, thumb: Int? = null): WebResourceResponse {
        val f = try { MiniAppPermissions.resolve(context, app, path, MiniAppPermissions.Need.READ) } catch (e: MiniAppError) {
            return plain(403, "Forbidden", e.message ?: "Forbidden")
        }
        if (!f.isFile) return plain(404, "Not Found", "No such file")
        val mime = MiniAppNet.guessMime(f.name)
        // thumbnails (images and video frames) and HEIC/HEIF, which the WebView can't show: made natively as JPEG
        val heif = mime == "image/heic" || mime == "image/heif"
        if (thumb != null && (mime.startsWith("image/") || mime.startsWith("video/")) || heif) {
            val jpeg = runCatching { MiniAppMedia.jpeg(context, app, f, thumb?.coerceIn(32, 1600)) }.getOrNull()
                ?: return plain(415, "Unsupported Media Type", "Can't decode ${f.name}")
            return WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Content-Length" to "${jpeg.length()}", "Cache-Control" to "no-store"), FileInputStream(jpeg))
        }
        val len = f.length()
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null && len > 0) {
            val start = m.groupValues[1].toLongOrNull() ?: (len - (m.groupValues[2].toLongOrNull() ?: len)).coerceAtLeast(0)
            val end = (if (m.groupValues[1].isEmpty()) len - 1 else m.groupValues[2].toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
            if (start <= end) {
                val raf = RandomAccessFile(f, "r").apply { seek(start) }
                val stream = object : InputStream() {
                    var left = end - start + 1
                    override fun read(): Int = if (left <= 0) -1 else raf.read().also { if (it >= 0) left-- }
                    override fun read(b: ByteArray, off: Int, l: Int): Int {
                        if (left <= 0) return -1
                        val n = raf.read(b, off, minOf(l.toLong(), left).toInt()); if (n > 0) left -= n; return n
                    }
                    override fun close() = raf.close()
                }
                return WebResourceResponse(mime, null, 206, "Partial Content", mapOf(
                    "Content-Range" to "bytes $start-$end/$len", "Content-Length" to "${end - start + 1}", "Accept-Ranges" to "bytes", "Cache-Control" to "no-store",
                ), stream)
            }
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Content-Length" to "$len", "Accept-Ranges" to "bytes", "Cache-Control" to "no-store"), FileInputStream(f))
    }

    private fun plain(code: Int, reason: String, text: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(text.toByteArray()))

    /** Files the user picked: copied into data/imports/ (the app never gets the original). */
    fun importUris(context: Context, app: MiniApp, uris: List<Uri>): JSONArray {
        val dir = File(MiniApps.filesDir(context, app.id), "imports").also { it.mkdirs() }
        val out = JSONArray()
        for (uri in uris) runCatching {
            var name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "file"
            name = name.replace(Regex("[/\\\\]"), "_")
            var target = File(dir, name)
            var n = 1
            while (target.exists()) target = File(dir, name.substringBeforeLast('.', name) + " ($n)" + name.substringAfterLast('.', "").let { if (it.isEmpty() || !name.contains('.')) "" else ".$it" }).also { n++ }
            context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("cannot read")
            out.put(JSONObject().put("name", target.name).put("path", "data/imports/${target.name}").put("size", target.length())
                .put("type", context.contentResolver.getType(uri) ?: MiniAppNet.guessMime(target.name)))
        }
        return out
    }
}
