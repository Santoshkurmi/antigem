package com.example.gemini.data.files

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Where the Files screen reads and changes files. [BRIDGE]: the bridge's machine (this phone in local mode, the server
 * or the PC in Server mode / Connect to PC). [PHONE]: this phone's own storage, read directly; the Files screen offers
 * it as a second tab while the bridge is on another machine, so files can be copied and moved between the two.
 */
enum class ExplorerSide { BRIDGE, PHONE }

interface ExplorerSource {
    val side: ExplorerSide
    suspend fun list(dir: String): Result<ExplorerListing>
    suspend fun roots(): Pair<String, List<ExplorerRoot>>
    suspend fun stat(path: String): Result<ExplorerStat>
    suspend fun create(dir: String, name: String, isDir: Boolean): FsResult
    suspend fun rename(path: String, newName: String): FsResult
    suspend fun transfer(sources: List<String>, targetDir: String, move: Boolean, conflict: ConflictPolicy): FsResult
    suspend fun delete(paths: List<String>): FsResult
    suspend fun compress(sources: List<String>, target: String): FsResult
    suspend fun extract(path: String, targetDir: String? = null): FsResult
    suspend fun archiveEntries(path: String): Result<Pair<List<ArchiveEntry>, Boolean>>
    suspend fun search(dir: String, query: String, includeHidden: Boolean): Result<Pair<List<ExplorerEntry>, Boolean>>

    companion object {
        fun of(side: ExplorerSide): ExplorerSource = if (side == ExplorerSide.PHONE) PhoneSource else BridgeSource
    }
}

/** The bridge's machine, through its /api/fs routes (what the Files screen always used). */
object BridgeSource : ExplorerSource {
    override val side = ExplorerSide.BRIDGE
    override suspend fun list(dir: String) = FileExplorerApi.list(dir)
    override suspend fun roots() = FileExplorerApi.roots()
    override suspend fun stat(path: String) = FileExplorerApi.stat(path)
    override suspend fun create(dir: String, name: String, isDir: Boolean) = FileExplorerApi.create(dir, name, isDir)
    override suspend fun rename(path: String, newName: String) = FileExplorerApi.rename(path, newName)
    override suspend fun transfer(sources: List<String>, targetDir: String, move: Boolean, conflict: ConflictPolicy) =
        FileExplorerApi.transfer(sources, targetDir, move, conflict)
    override suspend fun delete(paths: List<String>) = FileExplorerApi.delete(paths)
    override suspend fun compress(sources: List<String>, target: String) = FileExplorerApi.compress(sources, target)
    override suspend fun extract(path: String, targetDir: String?) = FileExplorerApi.extract(path, targetDir)
    override suspend fun archiveEntries(path: String) = FileExplorerApi.archiveEntries(path)
    override suspend fun search(dir: String, query: String, includeHidden: Boolean) = FileExplorerApi.search(dir, query, includeHidden)
}

/** This phone's storage (shared storage, SD cards, the app's own folders), read and changed directly. */
object PhoneSource : ExplorerSource {
    override val side = ExplorerSide.PHONE

    @Volatile private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }

    /** Internal shared storage (/storage/emulated/0): "~" on this tab. */
    val home: String get() = Environment.getExternalStorageDirectory().absolutePath

    private fun resolve(path: String): File = when {
        path.isBlank() || path == "~" -> File(home)
        path.startsWith("~/") -> File(home, path.removePrefix("~/"))
        else -> File(path)
    }

    private fun entryOf(f: File): ExplorerEntry {
        val isDir = f.isDirectory
        val symlink = runCatching { f.canonicalPath != f.absolutePath }.getOrDefault(false)
        return ExplorerEntry(
            name = f.name,
            path = f.absolutePath,
            isDir = isDir,
            size = if (isDir) 0 else f.length(),
            modTime = f.lastModified(),
            ext = if (isDir) "" else f.extension.lowercase(),
            symlink = symlink,
            mode = (if (isDir) "d" else "-") + (if (f.canRead()) "r" else "-") + (if (f.canWrite()) "w" else "-"),
            childCount = if (isDir) (f.list()?.size ?: 0).coerceAtMost(1001) else 0
        )
    }

    private fun noAccess(f: File): String {
        val ctx = appContext
        val allowed = ctx != null && com.example.gemini.data.local.TermuxStorageSetupManager.hasStoragePermission(ctx)
        return if (!allowed && f.absolutePath.startsWith("/storage")) "Allow AntiGem to access this phone's storage to see these files."
        else "Cannot open ${f.absolutePath}"
    }

    override suspend fun list(dir: String): Result<ExplorerListing> = withContext(Dispatchers.IO) {
        val d = resolve(dir)
        if (!d.isDirectory) return@withContext Result.failure(Exception(if (d.exists()) "${d.name} is not a folder" else "${d.absolutePath} does not exist"))
        val files = d.listFiles() ?: return@withContext Result.failure(Exception(noAccess(d)))
        Result.success(ExplorerListing(d.absolutePath, d.parent ?: "/", home, files.map(::entryOf)))
    }

    override suspend fun roots(): Pair<String, List<ExplorerRoot>> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ExplorerRoot>()
        fun add(name: String, f: File, kind: String) {
            if (!f.exists()) return
            val st = runCatching { StatFs(f.absolutePath) }.getOrNull()
            out += ExplorerRoot(name, f.absolutePath, kind, st?.totalBytes ?: 0, st?.availableBytes ?: 0)
        }
        add("Internal storage", File(home), "storage")
        add("Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "folder")
        add("Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "folder")
        add("Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "folder")
        // SD cards / USB drives: the volume root of every external files dir that is not internal storage
        appContext?.getExternalFilesDirs(null)?.filterNotNull()?.forEach { f ->
            val root = f.absolutePath.substringBefore("/Android/")
            if (root != home && root.startsWith("/storage/")) add("SD card (${root.substringAfterLast('/')})", File(root), "removable")
        }
        appContext?.let { add("AntiGem home", com.example.gemini.data.local.LocalEnvironmentManager.getHomeDir(it), "home") }
        home to out
    }

    override suspend fun stat(path: String): Result<ExplorerStat> = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.exists()) return@withContext Result.failure(Exception("${f.absolutePath} does not exist"))
        var files = 0
        var dirs = 0
        var size = if (f.isDirectory) 0L else f.length()
        var partial = false
        if (f.isDirectory) {
            for (c in f.walkTopDown().drop(1)) {
                if (files + dirs > 20_000) { partial = true; break }
                if (c.isDirectory) dirs++ else { files++; size += c.length() }
            }
        }
        val mime = if (f.isDirectory) "inode/directory" else FileKinds.mimeOf(f.name)
        Result.success(
            ExplorerStat(
                name = f.name, path = f.absolutePath, isDir = f.isDirectory, size = size, modTime = f.lastModified(),
                mode = entryOf(f).mode, modeOctal = "", symlinkTarget = runCatching { if (f.canonicalPath != f.absolutePath) f.canonicalPath else "" }.getOrDefault(""),
                mime = mime, readable = f.canRead(), writable = f.canWrite(), executable = f.canExecute(),
                files = files, dirs = dirs, partial = partial
            )
        )
    }

    override suspend fun create(dir: String, name: String, isDir: Boolean): FsResult = withContext(Dispatchers.IO) {
        if (name.isBlank() || name.contains('/')) return@withContext FsResult(false, error = "Invalid name")
        val f = File(resolve(dir), name)
        if (f.exists()) return@withContext FsResult(false, error = "\"$name\" already exists")
        val ok = runCatching { if (isDir) f.mkdirs() else f.createNewFile() }.getOrDefault(false)
        if (ok) FsResult(true, f.absolutePath) else FsResult(false, error = noAccess(f.parentFile ?: f))
    }

    override suspend fun rename(path: String, newName: String): FsResult = withContext(Dispatchers.IO) {
        if (newName.isBlank() || newName.contains('/')) return@withContext FsResult(false, error = "Invalid name")
        val f = resolve(path)
        val to = File(f.parentFile, newName)
        if (to.exists()) return@withContext FsResult(false, error = "\"$newName\" already exists")
        if (f.renameTo(to)) FsResult(true, to.absolutePath) else FsResult(false, error = "Could not rename ${f.name}")
    }

    override suspend fun transfer(sources: List<String>, targetDir: String, move: Boolean, conflict: ConflictPolicy): FsResult = withContext(Dispatchers.IO) {
        val dest = resolve(targetDir)
        var done = 0
        var failed = 0
        var error: String? = null
        for (s in sources) {
            coroutineContext.ensureActive()
            val src = resolve(s)
            var to = File(dest, src.name)
            if (to.absolutePath == src.absolutePath) {
                if (move) { done++; continue }
                to = uniqueFile(dest, src.name) // duplicate next to itself
            } else if (to.exists()) {
                when (conflict) {
                    ConflictPolicy.SKIP -> { done++; continue }
                    ConflictPolicy.RENAME -> to = uniqueFile(dest, src.name)
                    ConflictPolicy.OVERWRITE -> {}
                }
            }
            if (src.isDirectory && to.absolutePath.startsWith(src.absolutePath + "/")) { failed++; error = "Cannot copy a folder into itself"; continue }
            val ok = runCatching {
                if (move && (!to.exists() || conflict != ConflictPolicy.OVERWRITE) && src.renameTo(to)) true
                else {
                    if (to.exists() && conflict == ConflictPolicy.OVERWRITE && !to.isDirectory) to.delete()
                    src.copyRecursively(to, overwrite = true)
                    if (move) src.deleteRecursively() else true
                }
            }.getOrElse { e -> error = e.message; false }
            if (ok) done++ else { failed++; if (error == null) error = "Could not ${if (move) "move" else "copy"} ${src.name}" }
        }
        FsResult(failed == 0, dest.absolutePath, error, failed, done)
    }

    override suspend fun delete(paths: List<String>): FsResult = withContext(Dispatchers.IO) {
        var done = 0
        var failed = 0
        for (p in paths) if (resolve(p).deleteRecursively()) done++ else failed++
        FsResult(failed == 0, error = if (failed > 0) "Could not delete $failed item(s)" else null, failed = failed, count = done)
    }

    override suspend fun compress(sources: List<String>, target: String): FsResult = withContext(Dispatchers.IO) {
        val out = uniqueFile(resolve(target).parentFile ?: File(home), resolve(target).name)
        var count = 0
        runCatching {
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                for (s in sources) {
                    val root = resolve(s)
                    for (f in root.walkTopDown()) {
                        coroutineContext.ensureActive()
                        val rel = root.name + f.absolutePath.removePrefix(root.absolutePath).let { if (f.isDirectory) "$it/" else it }
                        zip.putNextEntry(ZipEntry(rel))
                        if (f.isFile) { FileInputStream(f).use { it.copyTo(zip) }; count++ }
                        zip.closeEntry()
                    }
                }
            }
            FsResult(true, out.absolutePath, count = count)
        }.getOrElse { e -> out.delete(); FsResult(false, error = e.message ?: "Compress failed") }
    }

    override suspend fun extract(path: String, targetDir: String?): FsResult = withContext(Dispatchers.IO) {
        val zipFile = resolve(path)
        if (!zipFile.name.endsWith(".zip", true)) return@withContext FsResult(false, error = "Only ZIP files can be extracted on this phone's storage")
        val base = targetDir?.let(::resolve) ?: uniqueFile(zipFile.parentFile ?: File(home), zipFile.nameWithoutExtension)
        var count = 0
        runCatching {
            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    coroutineContext.ensureActive()
                    val f = File(base, e.name)
                    // never write outside the target folder ("zip slip")
                    if (!f.canonicalPath.startsWith(base.canonicalPath + File.separator) && f.canonicalPath != base.canonicalPath) continue
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) }; count++ }
                }
            }
            FsResult(true, base.absolutePath, count = count)
        }.getOrElse { e -> FsResult(false, error = e.message ?: "Extract failed") }
    }

    override suspend fun archiveEntries(path: String): Result<Pair<List<ArchiveEntry>, Boolean>> = withContext(Dispatchers.IO) {
        val f = resolve(path)
        if (!f.name.endsWith(".zip", true)) return@withContext Result.failure(Exception("Only ZIP files can be looked into on this phone's storage"))
        runCatching {
            val out = mutableListOf<ArchiveEntry>()
            var more = false
            ZipInputStream(f.inputStream().buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (out.size >= 500) { more = true; break }
                    out += ArchiveEntry(e.name, e.size.coerceAtLeast(0), e.isDirectory)
                }
            }
            out.toList() to more
        }
    }

    override suspend fun search(dir: String, query: String, includeHidden: Boolean): Result<Pair<List<ExplorerEntry>, Boolean>> = withContext(Dispatchers.IO) {
        val root = resolve(dir)
        val q = query.trim()
        val hits = mutableListOf<ExplorerEntry>()
        var truncated = false
        runCatching {
            val all = root.walkTopDown().onEnter { d -> d == root || includeHidden || !d.name.startsWith(".") }.drop(1)
            for (f in all) {
                coroutineContext.ensureActive()
                if (hits.size >= 500) { truncated = true; break }
                if ((includeHidden || !f.name.startsWith(".")) && f.name.contains(q, ignoreCase = true)) hits += entryOf(f)
            }
            hits.toList() to truncated
        }
    }

    /** "name.ext", or "name (2).ext", "name (3).ext"… when taken. */
    fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var i = 2
        while (f.exists()) f = File(dir, "$stem ($i)$ext").also { i++ }
        return f
    }
}

/**
 * Copies / moves between the two sides of the Files screen (this phone ⇄ the bridge's machine): files stream through the
 * bridge's raw download and upload, folders are walked. A move deletes the original only after its copy worked.
 */
object CrossTransfer {

    private data class Item(val src: String, val rel: String, val isDir: Boolean, val size: Long)

    /** Every file and folder below the selected [roots] on [side], with their path relative to the target. */
    private suspend fun plan(side: ExplorerSide, roots: List<ExplorerEntry>, topNames: Map<String, String>): List<Item> {
        val out = mutableListOf<Item>()
        suspend fun walk(path: String, rel: String) {
            coroutineContext.ensureActive()
            val listing = ExplorerSource.of(side).list(path).getOrThrow()
            for (e in listing.entries) {
                out += Item(e.path, "$rel/${e.name}", e.isDir, e.size)
                if (e.isDir) walk(e.path, "$rel/${e.name}")
            }
        }
        for (r in roots) {
            val top = topNames[r.path] ?: continue
            out += Item(r.path, top, r.isDir, r.size)
            if (r.isDir) walk(r.path, top)
        }
        return out
    }

    /**
     * Copies (or moves) [items] from [from] into [destDir] on the other side. Names that exist there follow [policy].
     * [onProgress]: 0..1 by bytes.
     */
    suspend fun run(
        from: ExplorerSide,
        items: List<ExplorerEntry>,
        destDir: String,
        move: Boolean,
        policy: ConflictPolicy,
        onProgress: (Float) -> Unit
    ): FsResult {
        val to = if (from == ExplorerSide.PHONE) ExplorerSide.BRIDGE else ExplorerSide.PHONE
        val existing = ExplorerSource.of(to).list(destDir).getOrElse { return FsResult(false, error = it.message ?: "Cannot open the target folder") }
        val destPath = existing.path
        val taken = existing.entries.map { it.name }.toMutableSet()
        // the name each selected item gets there
        val topNames = linkedMapOf<String, String>()
        for (e in items) {
            val name = when {
                e.name !in taken -> e.name
                policy == ConflictPolicy.SKIP -> null
                policy == ConflictPolicy.OVERWRITE -> e.name
                else -> {
                    val stem = e.name.substringBeforeLast('.', e.name)
                    val ext = if (e.isDir) "" else e.name.substringAfterLast('.', "").let { if (it.isEmpty() || it == e.name) "" else ".$it" }
                    val base = if (e.isDir) e.name else stem
                    var i = 2
                    var n = "$base ($i)$ext"
                    while (n in taken) { i++; n = "$base ($i)$ext" }
                    n
                }
            } ?: continue
            taken += name
            topNames[e.path] = name
        }
        val plan = try { plan(from, items, topNames) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return FsResult(false, error = e.message ?: "Cannot read the files to copy")
        }
        val total = plan.filter { !it.isDir }.sumOf { it.size }.coerceAtLeast(1)
        var sent = 0L
        var failed = 0
        var done = 0
        var error: String? = null
        val failedTops = mutableSetOf<String>()

        for (it in plan) {
            coroutineContext.ensureActive()
            val target = "${destPath.trimEnd('/')}/${it.rel}"
            val top = it.rel.substringBefore('/')
            val ok: Boolean = if (to == ExplorerSide.PHONE) {
                if (it.isDir) File(target).let { d -> d.isDirectory || d.mkdirs() }
                else {
                    val base = sent
                    val r = FileExplorerApi.download(it.src, File(target)) { d, _ -> onProgress(((base + d).toFloat() / total).coerceIn(0f, 1f)) }
                    if (!r.ok) error = r.error
                    r.ok
                }
            } else {
                if (it.isDir) {
                    val parent = target.substringBeforeLast('/')
                    val r = FileExplorerApi.create(parent, target.substringAfterLast('/'), isDir = true)
                    // already there (overwrite into an existing folder) is fine
                    r.ok || BridgeSource.list(target).isSuccess
                } else {
                    val f = File(it.src)
                    val base = sent
                    val r = FileExplorerApi.upload({ FileInputStream(f) }, f.length(), target, conflict = "overwrite") { d ->
                        onProgress(((base + d).toFloat() / total).coerceIn(0f, 1f))
                    }
                    if (!r.ok) error = r.error
                    r.ok
                }
            }
            if (!it.isDir) sent += it.size
            if (ok) { if (!it.rel.contains('/')) done++ } else { failed++; failedTops += top }
            onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
        }

        // a move removes each original whose whole copy worked
        if (move) {
            val gone = items.filter { e -> topNames[e.path]?.let { it !in failedTops } == true }.map { it.path }
            if (gone.isNotEmpty()) {
                val r = ExplorerSource.of(from).delete(gone)
                if (!r.ok) error = (error ?: "") + " The originals could not all be removed: ${r.error}"
            }
        }
        return FsResult(failed == 0, destPath, error?.trim(), failed, done)
    }
}
