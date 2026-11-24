package ai.antigem.data.files

import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.preferences.AuthPreferences
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.annotation.RequiresApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import ai.antigem.i18n.L

/**
 * Shows AntiGem's folders in Android's file picker and Files app (the Storage Access Framework), like Termux does:
 *
 * - "AntiGem": the built-in environment's home (files/home), read and written directly. Its `storage` folder (links
 *   to the shared storage, which the picker lists already) is left out.
 * - "AntiGem Ubuntu": the rooted Ubuntu chroot's home (/home/<user>). The app cannot read the rootfs (root-owned
 *   folders under /data/local, SELinux), so everything goes through the chroot's IDE bridge on this phone, which runs
 *   inside Ubuntu as its user: Linux paths, links and owners stay right. It works while that bridge runs; otherwise
 *   the picker shows a message instead of the files.
 *
 * Only the system picker (MANAGE_DOCUMENTS) can browse this provider; other apps get the files or folder the user
 * picks and nothing else. Settings → Files in other apps turns it off (the component is disabled) or hides Ubuntu.
 *
 * Nothing here may crash the app: provider calls only throw the exception types a client understands, and the
 * threads it starts (file reads, uploads after a save) catch everything.
 */
class AntiGemDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "AntiGemDocs"

        const val ROOT_HOME = "antigem"
        const val ROOT_CHROOT = "ubuntu"

        private const val PREFS = "file_picker"
        private const val KEY_SHOW_CHROOT = "show_chroot"

        // AuthPreferences' plain SharedPreferences (read directly: creating an AuthPreferences reloads app-wide state)
        private const val SYNC_PREFS = "anti_gem_sync_prefs"

        /** Folders search does not go into (big and never what someone looks for in a picker). */
        private val SEARCH_SKIP = setOf("node_modules", "__pycache__")
        private const val SEARCH_LIMIT = 100
        private const val SEARCH_MILLIS = 4_000L
        private const val LIST_CACHE_MILLIS = 5_000L

        private val OCTET = "application/octet-stream".toMediaType()
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_MIME_TYPES
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )

        fun authority(context: Context) = context.packageName + ".documents"

        private fun component(context: Context) = ComponentName(context, AntiGemDocumentsProvider::class.java)

        /** On unless the user turned it off (the manifest enables it). */
        fun isEnabled(context: Context): Boolean = runCatching {
            context.packageManager.getComponentEnabledSetting(component(context)) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(true)

        /** Adds or removes every AntiGem location in the picker (Android refreshes the picker on its own). */
        fun setEnabled(context: Context, on: Boolean) {
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    component(context),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }.onFailure { Log.w(TAG, "Could not switch the file picker provider: ${it.message}") }
        }

        fun isChrootShown(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_CHROOT, true)

        fun setChrootShown(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_CHROOT, on).apply()
            notifyRootsChanged(context)
        }

        /** The picker caches the locations: tells it they changed (chroot installed, removed or switched). */
        fun notifyRootsChanged(context: Context) {
            runCatching { context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(context)), null) }
        }

        /** The Ubuntu chroot's home folder for its user. */
        fun chrootHome(user: String): String = if (user == "root") "/root" else "/home/$user"

        /**
         * Opens Android's Files app at one of the locations. False when no app on this phone can show it (some phone
         * makers replace the Files app with one that only shows its own places).
         */
        fun openInFilesApp(context: Context, rootId: String, documentId: String): Boolean {
            val auth = authority(context)
            val attempts = listOf(
                DocumentsContract.buildRootUri(auth, rootId) to Root.MIME_TYPE_ITEM,
                DocumentsContract.buildDocumentUri(auth, documentId) to Document.MIME_TYPE_DIR
            )
            for ((uri, type) in attempts) {
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(intent)
                    return true
                } catch (_: Exception) {
                    // next way
                }
            }
            return false
        }

        // ---- names and types ----

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext.isEmpty()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
                "md", "markdown" -> "text/markdown"
                "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "h", "cpp", "hpp", "sh", "bash",
                "zsh", "toml", "yaml", "yml", "ini", "conf", "cfg", "log", "gradle", "properties", "sql", "lua", "rb",
                "php", "swift", "dart", "env", "gitignore" -> "text/plain"
                else -> "application/octet-stream"
            }
        }

        private val THUMB_IMAGES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif")

        private fun canThumb(mime: String) =
            mime in THUMB_IMAGES || (mime.startsWith("video/") && Build.VERSION.SDK_INT >= 29)

        /** A file or folder name the picker asked for, made safe; files get the extension of their type when missing. */
        private fun cleanName(displayName: String?, mime: String): String {
            var name = (displayName ?: "").replace('/', '_').replace('\u0000', '_').trim()
            if (name.isEmpty() || name == "." || name == "..") name = if (mime == Document.MIME_TYPE_DIR) L.files.antiGemDocumentsProvider.newFolder else L.files.antiGemDocumentsProvider.untitled
            if (mime != Document.MIME_TYPE_DIR && mime != "application/octet-stream") {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                if (!ext.isNullOrEmpty() && !name.endsWith(".$ext", ignoreCase = true) && mimeOf(name) != mime) name += ".$ext"
            }
            return name.take(255)
        }

        /** "name", "name (1)", "name (2)"… (before the extension) until [taken] says no. */
        private fun uniqueName(name: String, isDir: Boolean, taken: (String) -> Boolean): String {
            if (!taken(name)) return name
            val dot = if (isDir) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            for (i in 1..9_999) {
                val candidate = "$base ($i)$ext"
                if (!taken(candidate)) return candidate
            }
            return "$base (${System.currentTimeMillis()})$ext"
        }

        /** Splits a path into its names; null when it tries to climb out (".."). */
        private fun segments(path: String): List<String>? {
            val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
            return if (parts.any { it == ".." }) null else parts
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }

    /** The Ubuntu chroot's bridge: where it listens and what it needs to let the app in. */
    private data class Chroot(val home: String, val baseUrl: String, val token: String)

    /** One file or folder as the chroot's bridge lists it. */
    private data class RemoteEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modTime: Long)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /** Uploads of files saved into Ubuntu (after the app that wrote them closed them). */
    private val uploads by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "picker-upload").apply { isDaemon = true } } }

    /** Runs the close listeners of the files this provider hands out. */
    private val closeHandler: Handler by lazy { Handler(HandlerThread("picker-close").apply { start() }.looper) }

    /** Folder listings of the chroot, kept a few seconds: the picker asks for the same folder several times in a row. */
    private val listCache = object : LinkedHashMap<String, Pair<Long, List<RemoteEntry>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<RemoteEntry>>>?) = size > 32
    }

    override fun onCreate(): Boolean = true

    private fun ctx(): Context = context ?: throw FileNotFoundException("AntiGem is starting")

    /**
     * Runs a provider call and turns any failure into an exception a client understands (and that never takes the
     * app down): FileNotFoundException with the reason, or the few types the picker reacts to on its own.
     */
    private inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: UnsupportedOperationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: IllegalStateException) {
        throw e
    } catch (e: SecurityException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "$what failed", e)
        throw FileNotFoundException(e.message ?: "$what failed")
    }

    // =================================================================== roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_PROJECTION)
        val context = context ?: return c
        try {
            val flags = Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
            // the chroot's rootfs is on /data too (chrootctl only installs there)
            val free = runCatching { Environment.getDataDirectory().usableSpace }.getOrDefault(0L)
            val icon = context.applicationInfo.icon
            if (homeDir(context).isDirectory) {
                c.newRow()
                    .add(Root.COLUMN_ROOT_ID, ROOT_HOME)
                    .add(Root.COLUMN_DOCUMENT_ID, "$ROOT_HOME:")
                    .add(Root.COLUMN_TITLE, "AntiGem")
                    .add(Root.COLUMN_SUMMARY, L.files.antiGemDocumentsProvider.homeFolder)
                    .add(Root.COLUMN_FLAGS, flags)
                    .add(Root.COLUMN_ICON, icon)
                    .add(Root.COLUMN_AVAILABLE_BYTES, free)
                    .add(Root.COLUMN_MIME_TYPES, "*/*")
            }
            chrootOrNull(context)?.let { ch ->
                c.newRow()
                    .add(Root.COLUMN_ROOT_ID, ROOT_CHROOT)
                    .add(Root.COLUMN_DOCUMENT_ID, "$ROOT_CHROOT:${ch.home}")
                    .add(Root.COLUMN_TITLE, "AntiGem Ubuntu")
                    .add(Root.COLUMN_SUMMARY, ch.home)
                    .add(Root.COLUMN_FLAGS, flags)
                    .add(Root.COLUMN_ICON, icon)
                    .add(Root.COLUMN_AVAILABLE_BYTES, free)
                    .add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "queryRoots failed", e)
        }
        runCatching { c.setNotificationUri(context.contentResolver, DocumentsContract.buildRootsUri(authority(context))) }
        return c
    }

    // =================================================================== queries

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor = guard("queryDocument") {
        val id = documentId ?: throw FileNotFoundException("No document")
        val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        when (rootOf(id)) {
            ROOT_HOME -> {
                val rel = localRel(id)
                addLocalRow(c, rel, localFile(rel))
            }
            else -> {
                val ch = chroot()
                val path = chrootPath(id, ch)
                if (path == ch.home) addRemoteRow(c, RemoteEntry(L.files.antiGemDocumentsProvider.ubuntuHome, path, true, 0, 0), ch)
                else {
                    val parent = parentOf(path)
                    val name = path.substringAfterLast('/')
                    val entry = listRemote(ch, parent, cached = true).firstOrNull { it.name == name }
                        ?: throw FileNotFoundException("$path not found")
                    addRemoteRow(c, entry, ch)
                }
            }
        }
        c
    }

    override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor =
        guard("queryChildDocuments") {
            val id = parentDocumentId ?: throw FileNotFoundException("No folder")
            val context = ctx()
            val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
            when (rootOf(id)) {
                ROOT_HOME -> {
                    val rel = localRel(id)
                    val dir = localFile(rel)
                    val children = dir.listFiles() ?: throw FileNotFoundException("Cannot open ${dir.name}")
                    for (f in children) {
                        if (rel.isEmpty() && f.name == "storage") continue
                        addLocalRow(c, join(rel, f.name), f)
                    }
                }
                else -> {
                    val ch = chroot()
                    val dir = chrootPath(id, ch)
                    try {
                        listRemote(ch, dir, cached = false).forEach { addRemoteRow(c, it, ch) }
                    } catch (e: FileNotFoundException) {
                        // the picker shows this text in place of the files
                        c.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, e.message) }
                    }
                }
            }
            c.setNotificationUri(context.contentResolver, DocumentsContract.buildChildDocumentsUri(authority(context), id))
            c
        }

    override fun querySearchDocuments(rootId: String?, query: String?, projection: Array<out String>?): Cursor =
        guard("querySearchDocuments") {
            val c = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
            val q = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (q.isEmpty()) return@guard c
            when (rootId) {
                ROOT_HOME -> searchLocal(q, c)
                ROOT_CHROOT -> {
                    val ch = chroot()
                    val hidden = if (q.startsWith(".")) 1 else 0
                    val o = bridgeGet(ch, "/api/fs/search?dir=${enc(ch.home)}&q=${enc(q)}&hidden=$hidden&limit=$SEARCH_LIMIT")
                    val arr = o.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = parseRemote(arr.getJSONObject(i))
                        if (inside(e.path, ch.home) && e.path != ch.home) addRemoteRow(c, e, ch)
                    }
                }
            }
            c
        }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        val parent = parentDocumentId ?: return false
        val child = documentId ?: return false
        return try {
            when {
                rootOf(parent) != rootOf(child) -> false
                rootOf(parent) == ROOT_HOME -> {
                    val p = localRel(parent)
                    val c = localRel(child)
                    p.isEmpty() || c == p || c.startsWith("$p/")
                }
                else -> {
                    val p = parent.substringAfter(':').let { segments(it)?.joinToString("/", prefix = "/") } ?: return false
                    val c = child.substringAfter(':').let { segments(it)?.joinToString("/", prefix = "/") } ?: return false
                    inside(c, p)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    @RequiresApi(26)
    override fun findDocumentPath(parentDocumentId: String?, childDocumentId: String?): DocumentsContract.Path = guard("findDocumentPath") {
        val child = childDocumentId ?: throw FileNotFoundException("No document")
        val root = rootOf(child)
        val ids = mutableListOf<String>()
        if (root == ROOT_HOME) {
            val parts = segments(localRel(child)).orEmpty()
            ids += "$ROOT_HOME:"
            for (i in parts.indices) ids += "$ROOT_HOME:" + parts.subList(0, i + 1).joinToString("/")
        } else {
            val ch = chroot()
            val path = chrootPath(child, ch)
            ids += "$ROOT_CHROOT:${ch.home}"
            val below = path.removePrefix(ch.home).trim('/')
            var cur = ch.home
            if (below.isNotEmpty()) for (name in below.split('/')) {
                cur = "$cur/$name"
                ids += "$ROOT_CHROOT:$cur"
            }
        }
        if (parentDocumentId != null) {
            val at = ids.indexOf(parentDocumentId)
            if (at < 0) throw FileNotFoundException("$childDocumentId is not inside $parentDocumentId")
            DocumentsContract.Path(null, ids.subList(at, ids.size).toList())
        } else {
            DocumentsContract.Path(root, ids)
        }
    }

    // =================================================================== open

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor = guard("openDocument") {
        val id = documentId ?: throw FileNotFoundException("No document")
        val m = (mode ?: "r").lowercase(Locale.ROOT)
        when (rootOf(id)) {
            ROOT_HOME -> {
                val rel = localRel(id)
                val file = localFile(rel)
                if (file.isDirectory) throw FileNotFoundException("${file.name} is a folder")
                val pfdMode = ParcelFileDescriptor.parseMode(m)
                if (m == "r") ParcelFileDescriptor.open(file, pfdMode)
                else {
                    val context = ctx()
                    // tells the picker (and an open folder in it) that the file changed
                    ParcelFileDescriptor.open(file, pfdMode, closeHandler) { notifyChildren(context, "$ROOT_HOME:${parentRel(rel)}") }
                }
            }
            else -> {
                val ch = chroot()
                val path = chrootPath(id, ch)
                if (m == "r") openRemoteRead(ch, path) else openRemoteWrite(ch, path, m, id)
            }
        }
    }

    override fun openDocumentThumbnail(documentId: String?, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor =
        guard("openDocumentThumbnail") {
            val id = documentId ?: throw FileNotFoundException("No document")
            if (rootOf(id) != ROOT_HOME) throw FileNotFoundException("No thumbnail")
            val file = localFile(localRel(id))
            val mime = mimeOf(file.name)
            if (!file.isFile || !canThumb(mime)) throw FileNotFoundException("No thumbnail")
            val w = (sizeHint?.x ?: 256).coerceIn(32, 1024)
            val h = (sizeHint?.y ?: 256).coerceIn(32, 1024)
            val dir = File(ctx().cacheDir, "picker-thumbs").apply { mkdirs() }
            val key = (file.absolutePath + "|" + file.lastModified() + "|" + file.length() + "|" + w + "x" + h).hashCode()
            val out = File(dir, Integer.toHexString(key) + ".jpg")
            if (!out.isFile || out.length() == 0L) {
                val bmp = thumbnail(file, mime, w, h, signal) ?: throw FileNotFoundException("No thumbnail")
                val tmp = File(dir, "${out.name}.${UUID.randomUUID()}.part")
                try {
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (!tmp.renameTo(out)) throw IOException("Could not save the thumbnail")
                } finally {
                    bmp.recycle()
                    tmp.delete()
                }
                trimThumbs(dir)
            }
            AssetFileDescriptor(ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }

    // =================================================================== changes

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String = guard("createDocument") {
        val parentId = parentDocumentId ?: throw FileNotFoundException("No folder")
        val mime = mimeType ?: "application/octet-stream"
        val isDir = mime == Document.MIME_TYPE_DIR
        val context = ctx()
        val newId = when (rootOf(parentId)) {
            ROOT_HOME -> {
                val rel = localRel(parentId)
                val dir = localFile(rel)
                if (!dir.isDirectory) throw FileNotFoundException("${dir.name} is not a folder")
                val name = uniqueName(cleanName(displayName, mime), isDir) { File(dir, it).exists() || isLink(File(dir, it)) }
                val f = File(dir, name)
                val ok = if (isDir) f.mkdir() else f.createNewFile()
                if (!ok) throw IllegalStateException("Could not create $name")
                "$ROOT_HOME:${join(rel, name)}"
            }
            else -> {
                val ch = chroot()
                val dir = chrootPath(parentId, ch)
                val existing = listRemote(ch, dir, cached = false).map { it.name }.toSet()
                val name = uniqueName(cleanName(displayName, mime), isDir) { it in existing }
                val o = bridgePost(ch, "/api/fs/new", JSONObject().put("dir", dir).put("name", name).put("isDir", isDir))
                forget(dir)
                "$ROOT_CHROOT:" + o.optString("path").ifBlank { "$dir/$name" }
            }
        }
        notifyChildren(context, parentId)
        newId
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? = guard("renameDocument") {
        val id = documentId ?: throw FileNotFoundException("No document")
        val context = ctx()
        when (rootOf(id)) {
            ROOT_HOME -> {
                val rel = localRel(id)
                if (rel.isEmpty()) throw UnsupportedOperationException("The home folder cannot be renamed")
                val file = localFile(rel)
                val name = cleanName(displayName, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
                if (name == file.name) return@guard null
                val target = File(file.parentFile, name)
                if ((target.exists() || isLink(target)) && !target.name.equals(file.name, ignoreCase = true)) {
                    throw IllegalStateException("\"$name\" already exists")
                }
                if (!file.renameTo(target)) throw IllegalStateException("Could not rename ${file.name}")
                notifyChildren(context, "$ROOT_HOME:${parentRel(rel)}")
                "$ROOT_HOME:${join(parentRel(rel), name)}"
            }
            else -> {
                val ch = chroot()
                val path = chrootPath(id, ch)
                if (path == ch.home) throw UnsupportedOperationException("The home folder cannot be renamed")
                val name = (displayName ?: "").replace('/', '_').trim()
                if (name.isEmpty() || name == path.substringAfterLast('/')) return@guard null
                val o = bridgePost(ch, "/api/fs/rename", JSONObject().put("path", path).put("newName", name))
                forget(parentOf(path))
                notifyChildren(context, "$ROOT_CHROOT:${parentOf(path)}")
                "$ROOT_CHROOT:" + o.optString("path").ifBlank { parentOf(path) + "/" + name }
            }
        }
    }

    override fun deleteDocument(documentId: String?) = guard("deleteDocument") {
        val id = documentId ?: throw FileNotFoundException("No document")
        val context = ctx()
        when (rootOf(id)) {
            ROOT_HOME -> {
                val rel = localRel(id)
                if (rel.isEmpty()) throw UnsupportedOperationException("The home folder cannot be deleted")
                deleteLocal(localFile(rel))
                notifyChildren(context, "$ROOT_HOME:${parentRel(rel)}")
            }
            else -> {
                val ch = chroot()
                val path = chrootPath(id, ch)
                if (path == ch.home) throw UnsupportedOperationException("The home folder cannot be deleted")
                val o = bridgePost(ch, "/api/fs/delete", JSONObject().put("paths", JSONArray(listOf(path))))
                firstError(o)?.let { throw IllegalStateException(it) }
                forget(parentOf(path))
                notifyChildren(context, "$ROOT_CHROOT:${parentOf(path)}")
            }
        }
        revokeDocumentPermission(id)
    }

    override fun removeDocument(documentId: String?, parentDocumentId: String?) {
        if (documentId == null || parentDocumentId == null) throw FileNotFoundException("No document")
        if (!isChildDocument(parentDocumentId, documentId)) throw FileNotFoundException("$documentId is not in $parentDocumentId")
        deleteDocument(documentId)
    }

    override fun copyDocument(sourceDocumentId: String?, targetParentDocumentId: String?): String =
        transfer(sourceDocumentId, targetParentDocumentId, move = false)

    override fun moveDocument(sourceDocumentId: String?, sourceParentDocumentId: String?, targetParentDocumentId: String?): String =
        transfer(sourceDocumentId, targetParentDocumentId, move = true)

    /** Copies or moves inside one location; between AntiGem and Ubuntu the picker copies the bytes itself. */
    private fun transfer(sourceId: String?, targetParentId: String?, move: Boolean): String = guard(if (move) "moveDocument" else "copyDocument") {
        val src = sourceId ?: throw FileNotFoundException("No document")
        val dst = targetParentId ?: throw FileNotFoundException("No folder")
        if (rootOf(src) != rootOf(dst)) throw UnsupportedOperationException("Between locations the picker copies the file itself")
        val context = ctx()
        val newId = when (rootOf(src)) {
            ROOT_HOME -> {
                val srcRel = localRel(src)
                val dstRel = localRel(dst)
                if (srcRel.isEmpty()) throw UnsupportedOperationException("The home folder cannot be moved")
                if (dstRel == srcRel || dstRel.startsWith("$srcRel/")) throw IllegalArgumentException("A folder cannot go inside itself")
                val from = localFile(srcRel)
                val dir = localFile(dstRel)
                if (!dir.isDirectory) throw FileNotFoundException("${dir.name} is not a folder")
                val name = uniqueName(from.name, from.isDirectory) { File(dir, it).exists() || isLink(File(dir, it)) }
                val to = File(dir, name)
                if (move) {
                    if (!from.renameTo(to)) {
                        copyLocal(from, to)
                        deleteLocal(from)
                    }
                    notifyChildren(context, "$ROOT_HOME:${parentRel(srcRel)}")
                } else {
                    copyLocal(from, to)
                }
                "$ROOT_HOME:${join(dstRel, name)}"
            }
            else -> {
                val ch = chroot()
                val from = chrootPath(src, ch)
                val dir = chrootPath(dst, ch)
                if (from == ch.home) throw UnsupportedOperationException("The home folder cannot be moved")
                if (inside(dir, from)) throw IllegalArgumentException("A folder cannot go inside itself")
                val o = bridgePost(
                    ch, "/api/fs/transfer",
                    JSONObject().put("sources", JSONArray(listOf(from))).put("targetDir", dir).put("move", move).put("conflict", "rename")
                )
                firstError(o)?.let { throw IllegalStateException(it) }
                val target = o.optJSONArray("results")?.optJSONObject(0)?.optString("target").orEmpty()
                forget(dir)
                if (move) {
                    forget(parentOf(from))
                    notifyChildren(context, "$ROOT_CHROOT:${parentOf(from)}")
                }
                "$ROOT_CHROOT:" + target.ifBlank { dir + "/" + from.substringAfterLast('/') }
            }
        }
        notifyChildren(context, dst)
        newId
    }

    // =================================================================== AntiGem home (direct)

    private fun homeDir(context: Context): File = LocalEnvironmentManager.getHomeDir(context)

    private fun rootOf(documentId: String): String = when (documentId.substringBefore(':', "")) {
        ROOT_HOME -> ROOT_HOME
        ROOT_CHROOT -> ROOT_CHROOT
        else -> throw FileNotFoundException("Unknown document $documentId")
    }

    /** The path below the home folder ("" = the home folder itself); refuses anything that climbs out or into `storage`. */
    private fun localRel(documentId: String): String {
        val parts = segments(documentId.substringAfter(':', "")) ?: throw FileNotFoundException("Bad path in $documentId")
        if (parts.firstOrNull() == "storage") throw FileNotFoundException("Not shown here")
        return parts.joinToString("/")
    }

    private fun localFile(rel: String): File {
        val home = homeDir(ctx())
        if (!home.isDirectory) throw FileNotFoundException("AntiGem's environment is not set up")
        return if (rel.isEmpty()) home else File(home, rel)
    }

    private fun join(rel: String, name: String) = if (rel.isEmpty()) name else "$rel/$name"

    private fun parentRel(rel: String) = rel.substringBeforeLast('/', "")

    private fun isLink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.path).st_mode)
    } catch (_: ErrnoException) {
        false
    }

    /** A real folder (not a link to one): what copies, deletes and searches go into. */
    private fun isRealDir(f: File): Boolean = try {
        OsConstants.S_ISDIR(Os.lstat(f.path).st_mode)
    } catch (_: ErrnoException) {
        false
    }

    private fun addLocalRow(c: MatrixCursor, rel: String, file: File) {
        val isRoot = rel.isEmpty()
        val isDir = file.isDirectory
        val mime = if (isDir) Document.MIME_TYPE_DIR else mimeOf(file.name)
        var flags = 0
        if (isDir) {
            if (file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            if (file.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE
            if (canThumb(mime)) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }
        if (!isRoot) {
            flags = flags or Document.FLAG_SUPPORTS_COPY
            if (file.parentFile?.canWrite() == true) {
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                    Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_REMOVE
            }
        }
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, "$ROOT_HOME:$rel")
            .add(Document.COLUMN_DISPLAY_NAME, if (isRoot) L.files.antiGemDocumentsProvider.antigemHome else file.name)
            .add(Document.COLUMN_MIME_TYPE, mime)
            .add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun searchLocal(q: String, c: MatrixCursor) {
        val home = localFile("")
        val withHidden = q.startsWith(".")
        val deadline = System.currentTimeMillis() + SEARCH_MILLIS
        val queue = ArrayDeque<Pair<String, File>>()
        queue.add("" to home)
        while (queue.isNotEmpty() && c.count < SEARCH_LIMIT && System.currentTimeMillis() < deadline) {
            val (rel, dir) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (c.count >= SEARCH_LIMIT) break
                val name = f.name
                if (rel.isEmpty() && name == "storage") continue
                if (!withHidden && name.startsWith(".")) continue
                val childRel = join(rel, name)
                if (name.lowercase(Locale.ROOT).contains(q)) addLocalRow(c, childRel, f)
                if (name !in SEARCH_SKIP && isRealDir(f)) queue.add(childRel to f)
            }
        }
    }

    /** Deletes a file or a folder with what is inside; links are removed, never followed. */
    private fun deleteLocal(f: File) {
        if (isRealDir(f)) f.list()?.forEach { deleteLocal(File(f, it)) }
        if (!f.delete() && (f.exists() || isLink(f))) throw IllegalStateException("Could not delete ${f.name}")
    }

    /** Copies a file or folder; links are copied as links. */
    private fun copyLocal(from: File, to: File) {
        val mode = try {
            Os.lstat(from.path).st_mode
        } catch (e: ErrnoException) {
            throw FileNotFoundException("${from.name}: ${e.message}")
        }
        when {
            OsConstants.S_ISLNK(mode) -> Os.symlink(Os.readlink(from.path), to.path)
            OsConstants.S_ISDIR(mode) -> {
                if (!to.mkdir()) throw IllegalStateException("Could not create ${to.name}")
                from.list()?.forEach { copyLocal(File(from, it), File(to, it)) }
            }
            else -> {
                from.inputStream().use { input -> to.outputStream().use { input.copyTo(it, 64 * 1024) } }
                to.setLastModified(from.lastModified())
            }
        }
    }

    private fun thumbnail(file: File, mime: String, w: Int, h: Int, signal: CancellationSignal?): Bitmap? = try {
        when {
            mime.startsWith("video/") && Build.VERSION.SDK_INT >= 29 -> ThumbnailUtils.createVideoThumbnail(file, Size(w, h), signal)
            // turns the picture the way the camera saved it (EXIF) and reads HEIC
            Build.VERSION.SDK_INT >= 29 -> ThumbnailUtils.createImageThumbnail(file, Size(w, h), signal)
            else -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "No thumbnail for ${file.name}: ${e.message}")
        null
    }

    /** Keeps the thumbnail cache small: the oldest half goes once it holds 400. */
    private fun trimThumbs(dir: File) {
        runCatching {
            val files = dir.listFiles() ?: return
            if (files.size < 400) return
            files.sortedBy { it.lastModified() }.take(files.size / 2).forEach { it.delete() }
        }
    }

    // =================================================================== Ubuntu chroot (through its bridge)

    /** The chroot shown in the picker, or null when there is none or the user hid it. */
    private fun chrootOrNull(context: Context): Chroot? {
        if (!isChrootShown(context)) return null
        val install = runCatching { ChrootManager.activeInstall(context) }.getOrNull() ?: return null
        val sync = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
        // with the chroot as the phone's runtime, a bridge address the user set on this phone is the chroot's
        val chrootMode = sync.getString("environment_type", "TERMUX") == "UBUNTU_CHROOT"
        val saved = sync.getString("agy_bridge_http_url", null)?.trim().orEmpty()
        val savedIsLocal = runCatching { AuthPreferences.isLoopbackHost(java.net.URI(saved).host ?: "") }.getOrDefault(false)
        val base = if (chrootMode && savedIsLocal) saved else AuthPreferences.DEFAULT_CHROOT_BRIDGE_HTTP_URL
        val token = sync.getString("security_token", null).orEmpty()
        return Chroot(chrootHome(install.user.ifBlank { "ubuntu" }), base.trimEnd('/'), token)
    }

    private fun chroot(): Chroot =
        chrootOrNull(ctx()) ?: throw FileNotFoundException("The Ubuntu chroot is not set up in AntiGem (or hidden in its settings)")

    /** The Linux path of a chroot document; only the home folder and what is below it. */
    private fun chrootPath(documentId: String, ch: Chroot): String {
        val parts = segments(documentId.substringAfter(':', "")) ?: throw FileNotFoundException("Bad path in $documentId")
        val path = parts.joinToString("/", prefix = "/")
        if (!inside(path, ch.home)) throw FileNotFoundException("$path is outside the Ubuntu home folder")
        return path
    }

    private fun inside(path: String, base: String) = path == base || path.startsWith("$base/")

    private fun parentOf(path: String) = path.substringBeforeLast('/', "").ifEmpty { "/" }

    private fun request(ch: Chroot, path: String): Request.Builder =
        Request.Builder().url(ch.baseUrl + path).header(AuthPreferences.buildFramedHeader(ch.token), "true")

    private fun notRunning() = FileNotFoundException(
        "Ubuntu is not running. Open AntiGem and start the Ubuntu chroot, then come back here."
    )

    private fun execute(ch: Chroot, req: Request, client: OkHttpClient = http): JSONObject {
        val resp = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw notRunning()
        }
        return resp.use { r ->
            val raw = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(raw) }.getOrNull()
            when {
                r.code == 401 || r.code == 403 -> throw FileNotFoundException("Ubuntu's IDE bridge refused AntiGem (security token). Restart it from AntiGem.")
                r.code != 200 -> throw FileNotFoundException(o?.optString("error")?.ifBlank { null } ?: "Ubuntu's IDE bridge answered ${r.code}")
                o == null -> throw FileNotFoundException("Ubuntu's IDE bridge sent an unexpected answer")
                else -> o
            }
        }
    }

    private fun bridgeGet(ch: Chroot, path: String): JSONObject = execute(ch, request(ch, path).get().build())

    private fun bridgePost(ch: Chroot, path: String, body: JSONObject): JSONObject {
        val o = execute(ch, request(ch, path).post(body.toString().toRequestBody(JSON)).build())
        if (o.has("success") && !o.optBoolean("success") && !o.has("results")) {
            throw IllegalStateException(o.optString("error").ifBlank { L.files.antiGemDocumentsProvider.ubuntusIdeBridgeCouldNotDo })
        }
        return o
    }

    /** The first failure of a batch answer (delete, transfer), or null. */
    private fun firstError(o: JSONObject): String? {
        val results = o.optJSONArray("results") ?: return o.optString("error").ifBlank { null }
        for (i in 0 until results.length()) {
            val e = results.optJSONObject(i)?.optString("error").orEmpty()
            if (e.isNotBlank()) return e
        }
        return null
    }

    private fun parseRemote(o: JSONObject) = RemoteEntry(
        name = o.optString("name"), path = o.optString("path"), isDir = o.optBoolean("isDir"),
        size = o.optLong("size"), modTime = o.optLong("modTime")
    )

    private fun listRemote(ch: Chroot, dir: String, cached: Boolean): List<RemoteEntry> {
        if (cached) synchronized(listCache) {
            listCache[dir]?.let { (at, list) -> if (System.currentTimeMillis() - at < LIST_CACHE_MILLIS) return list }
        }
        val o = bridgeGet(ch, "/api/fs/browse?dir=${enc(dir)}")
        // a bridge whose home is under /data is AntiGem's own (Termux), not Ubuntu's
        if (o.optString("homePath").startsWith("/data/")) {
            throw FileNotFoundException("Ubuntu is not running (AntiGem's own bridge answered). Start the Ubuntu chroot in AntiGem.")
        }
        val arr = o.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).map { parseRemote(arr.getJSONObject(it)) }.filter { it.name.isNotEmpty() }
        synchronized(listCache) { listCache[dir] = System.currentTimeMillis() to list }
        return list
    }

    private fun forget(dir: String) = synchronized(listCache) { listCache.remove(dir) }

    private fun addRemoteRow(c: MatrixCursor, e: RemoteEntry, ch: Chroot) {
        val isRoot = e.path == ch.home
        val mime = if (e.isDir) Document.MIME_TYPE_DIR else mimeOf(e.name)
        var flags = if (e.isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (!isRoot) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_COPY or
                Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_REMOVE
        }
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, "$ROOT_CHROOT:${e.path}")
            .add(Document.COLUMN_DISPLAY_NAME, e.name)
            .add(Document.COLUMN_MIME_TYPE, mime)
            .add(Document.COLUMN_SIZE, if (e.isDir) null else e.size)
            .add(Document.COLUMN_LAST_MODIFIED, if (e.modTime > 0) e.modTime else null)
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** Reading a chroot file: streamed from the bridge with seeking (Android 8+), or copied to the cache first. */
    private fun openRemoteRead(ch: Chroot, path: String): ParcelFileDescriptor {
        val st = bridgeGet(ch, "/api/fs/stat?path=${enc(path)}")
        if (st.optBoolean("isDir")) throw FileNotFoundException("${path.substringAfterLast('/')} is a folder")
        val size = st.optLong("size").coerceAtLeast(0L)
        val context = ctx()
        if (Build.VERSION.SDK_INT >= 26) {
            val thread = HandlerThread("picker-read").apply { start() }
            try {
                val sm = context.getSystemService(StorageManager::class.java) ?: throw IOException("No storage service")
                return sm.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY, RemoteReader(ch, path, size, thread), Handler(thread.looper)
                )
            } catch (e: Exception) {
                // Android could not set the stream up: a copy in the cache works as well (only slower for big files)
                thread.quitSafely()
                Log.w(TAG, "Streaming $path failed, copying it instead: ${e.message}")
            }
        }
        val tmp = File(transferDir(context), UUID.randomUUID().toString())
        try {
            download(ch, path, tmp)
            return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY, closeHandler) { tmp.delete() }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Writing a chroot file: the app writes a file in AntiGem's cache (seeking works, which many apps need), and once
     * it closes it the file goes to Ubuntu. A failed upload shows a message, since the app that saved has moved on.
     */
    private fun openRemoteWrite(ch: Chroot, path: String, mode: String, documentId: String): ParcelFileDescriptor {
        val context = ctx()
        // Ubuntu must be running now: the app would otherwise write the whole file before learning it cannot be saved
        listRemote(ch, parentOf(path), cached = true)
        val tmp = File(transferDir(context), UUID.randomUUID().toString())
        // "rw" and "wa" keep what is in the file; "w", "wt" and "rwt" start empty
        val keep = mode.contains('a') || mode == "rw"
        try {
            if (keep) download(ch, path, tmp) else if (!tmp.createNewFile()) throw IOException("Could not create a temporary file")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        val before = tmp.lastModified() to tmp.length()
        return try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.parseMode(mode), closeHandler) { error ->
                try {
                    // the app died while writing: keep Ubuntu's file as it was rather than save half of it
                    val abandoned = error != null && error !is ParcelFileDescriptor.FileDescriptorDetachedException
                    val unchanged = keep && (tmp.lastModified() to tmp.length()) == before
                    if (abandoned || unchanged) {
                        tmp.delete()
                    } else {
                        uploads.execute { uploadSaved(context, ch, path, tmp, documentId) }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Saving $path into Ubuntu failed", e)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun uploadSaved(context: Context, ch: Chroot, path: String, tmp: File, documentId: String) {
        try {
            val req = request(ch, "/api/fs/upload?path=${enc(path)}&conflict=overwrite").post(tmp.asRequestBody(OCTET)).build()
            execute(ch, req)
            forget(parentOf(path))
            notifyChildren(context, "$ROOT_CHROOT:${parentOf(path)}")
            runCatching { context.contentResolver.notifyChange(DocumentsContract.buildDocumentUri(authority(context), documentId), null) }
        } catch (e: Throwable) {
            Log.w(TAG, "Saving $path into Ubuntu failed", e)
            toast(context, L.files.antiGemDocumentsProvider.couldNotSaveNameIntoUbuntu(path.substringAfterLast('/'), e.message ?: L.files.antiGemDocumentsProvider.unknownError))
        } finally {
            tmp.delete()
        }
    }

    private fun download(ch: Chroot, path: String, dest: File) {
        val resp = try {
            transferClient.newCall(request(ch, "/api/fs/raw?path=${enc(path)}").get().build()).execute()
        } catch (e: IOException) {
            throw notRunning()
        }
        resp.use { r ->
            if (r.code == 404) throw FileNotFoundException("${path.substringAfterLast('/')} not found")
            if (!r.isSuccessful) throw FileNotFoundException("Ubuntu's IDE bridge answered ${r.code}")
            val body = r.body ?: throw FileNotFoundException("Empty answer")
            body.byteStream().use { input -> dest.outputStream().use { input.copyTo(it, 64 * 1024) } }
        }
    }

    /** No read timeout between chunks of a big file the app reads slowly. */
    private val transferClient: OkHttpClient by lazy {
        http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()
    }

    private fun transferDir(context: Context): File {
        val dir = File(context.cacheDir, "picker-transfer")
        dir.mkdirs()
        // what an earlier run left (the app was killed during a save): files older than a day
        runCatching {
            val old = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            dir.listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
        }
        return dir
    }

    /**
     * Serves reads of a chroot file from one HTTP stream that keeps going while the app reads in order; a jump
     * (seek) starts a new ranged request. Runs on its own thread, which ends when the app closes the file.
     */
    @RequiresApi(26)
    private inner class RemoteReader(
        private val ch: Chroot,
        private val path: String,
        private val fileSize: Long,
        private val thread: HandlerThread
    ) : ProxyFileDescriptorCallback() {
        private var response: Response? = null
        private var stream: InputStream? = null
        private var position = -1L

        override fun onGetSize(): Long = fileSize

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                if (offset >= fileSize || size <= 0) return 0
                if (stream == null || offset != position) reopen(offset)
                val input = stream ?: throw ErrnoException("read", OsConstants.EIO)
                val want = minOf(size.toLong(), data.size.toLong(), fileSize - offset).toInt()
                var done = 0
                while (done < want) {
                    val n = input.read(data, done, want - done)
                    if (n < 0) break
                    done += n
                }
                position = offset + done
                return done
            } catch (e: ErrnoException) {
                closeStream()
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Reading $path from Ubuntu failed: ${e.message}")
                closeStream()
                throw ErrnoException("read", OsConstants.EIO)
            }
        }

        private fun reopen(offset: Long) {
            closeStream()
            val req = request(ch, "/api/fs/raw?path=${enc(path)}").header("Range", "bytes=$offset-").get().build()
            val r = transferClient.newCall(req).execute()
            if (r.code != 206 && r.code != 200) {
                r.close()
                throw ErrnoException("open", if (r.code == 404) OsConstants.ENOENT else OsConstants.EIO)
            }
            val input = r.body?.byteStream() ?: run { r.close(); throw ErrnoException("open", OsConstants.EIO) }
            // a server that ignores Range sends the whole file: skip to the offset
            if (r.code == 200 && offset > 0) {
                var left = offset
                while (left > 0) {
                    val skipped = input.skip(left)
                    if (skipped <= 0) {
                        if (input.read() < 0) break
                        left--
                    } else {
                        left -= skipped
                    }
                }
            }
            response = r
            stream = input
            position = offset
        }

        private fun closeStream() {
            runCatching { stream?.close() }
            runCatching { response?.close() }
            stream = null
            response = null
            position = -1L
        }

        override fun onRelease() {
            try {
                closeStream()
            } finally {
                thread.quitSafely()
            }
        }
    }

    // =================================================================== helpers

    private fun notifyChildren(context: Context, folderId: String) {
        runCatching {
            context.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(authority(context), folderId), null)
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
        }
    }
}
