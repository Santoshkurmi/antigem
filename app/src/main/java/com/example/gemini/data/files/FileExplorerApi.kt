package com.example.gemini.data.files

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One file or folder as the Files screen shows it. */
data class ExplorerEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modTime: Long = 0,
    val ext: String = "",
    val hidden: Boolean = name.startsWith("."),
    val symlink: Boolean = false,
    val mode: String = "",
    /** Folders: how many entries are inside (capped at 1001); 0 when empty or unknown. */
    val childCount: Int = 0
) {
    val kind: FileKind get() = if (isDir) FileKind.FOLDER else FileKinds.of(name)
}

data class ExplorerListing(
    val path: String,
    val parent: String,
    val home: String,
    val entries: List<ExplorerEntry>
)

data class ExplorerRoot(val name: String, val path: String, val kind: String, val total: Long, val free: Long)

data class ExplorerStat(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modTime: Long,
    val mode: String,
    val modeOctal: String,
    val symlinkTarget: String,
    val mime: String,
    val readable: Boolean,
    val writable: Boolean,
    val executable: Boolean,
    val files: Int,
    val dirs: Int,
    val partial: Boolean,
    /** Files: "text", "image" or "binary", judged from the first bytes by the bridge. Blank from older bridges. */
    val category: String = "",
    /** Files: the type read from the content, not the name. Blank from older bridges. */
    val sniffedMime: String = ""
)

data class ArchiveEntry(val name: String, val size: Long, val isDir: Boolean)

/** The result of an action: ok, with an optional path it produced and a message for the user when it failed. */
data class FsResult(val ok: Boolean, val path: String? = null, val error: String? = null, val failed: Int = 0, val count: Int = 0)

enum class ConflictPolicy(val wire: String) { RENAME("rename"), OVERWRITE("overwrite"), SKIP("skip") }

/**
 * Files screen client. Every action runs on the IDE bridge, so the same explorer works for the phone, a Linux distro and a
 * remote server. Large transfers use their own client (no network inspector buffering, long timeouts).
 */
object FileExplorerApi {
    private const val TAG = "FileExplorerApi"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val OCTET = "application/octet-stream".toMediaType()

    private val base: String get() = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
    private val client: OkHttpClient get() = com.example.gemini.data.daemon.IdeApiClient.okHttpClient

    /** For big uploads / downloads: auth header only, nothing that reads bodies into memory. */
    val transferClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                val b = chain.request().newBuilder().header(AuthPreferences.currentFramedHeader, "true")
                AuthPreferences.edgeHeader()?.let { (n, v) -> b.header(n, v) }
                chain.proceed(b.build())
            }
            .build()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** URL that streams a file's bytes (Coil adds the security header for the bridge address on its own). */
    fun rawUrl(path: String): String = "$base/api/fs/raw?path=${enc(path)}"

    /** True when the bridge runs on this phone and the app can read the file itself (no download needed). */
    fun isLocallyReadable(path: String): Boolean =
        !AuthPreferences.isRemoteEndpoint() && runCatching { File(path).let { it.isFile && it.canRead() } }.getOrDefault(false)

    private fun errorOf(code: Int, body: String?): String {
        val msg = runCatching { JSONObject(body ?: "").optString("error") }.getOrNull().orEmpty()
        return when {
            msg.isNotBlank() -> msg
            code == 404 -> "The IDE bridge is outdated. Restart it (or update it on your server) to use this."
            code == 401 -> "The IDE bridge refused the request (security token)."
            else -> "Request failed ($code)"
        }
    }

    private suspend fun post(path: String, body: JSONObject): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$base$path").post(body.toString().toRequestBody(JSON)).build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                resp.code to (runCatching { JSONObject(raw ?: "") }.getOrNull() ?: JSONObject().put("error", errorOf(resp.code, raw)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST $path failed: ${e.message}")
            0 to JSONObject().put("error", "IDE bridge is not reachable. Make sure it is running.")
        }
    }

    private suspend fun get(path: String, c: OkHttpClient = client): Pair<Int, JSONObject?> = withContext(Dispatchers.IO) {
        try {
            c.newCall(Request.Builder().url("$base$path").get().build()).execute().use { resp ->
                val raw = resp.body?.string()
                resp.code to (runCatching { JSONObject(raw ?: "") }.getOrNull() ?: JSONObject().put("error", errorOf(resp.code, raw)))
            }
        } catch (e: Exception) {
            Log.w(TAG, "GET $path failed: ${e.message}")
            0 to JSONObject().put("error", "IDE bridge is not reachable. Make sure it is running.")
        }
    }

    private fun JSONObject.err(code: Int) = optString("error").ifBlank { errorOf(code, null) }

    private fun parseEntry(o: JSONObject) = ExplorerEntry(
        name = o.optString("name"),
        path = o.optString("path"),
        isDir = o.optBoolean("isDir"),
        size = o.optLong("size"),
        modTime = o.optLong("modTime"),
        ext = o.optString("ext"),
        hidden = o.optBoolean("hidden", o.optString("name").startsWith(".")),
        symlink = o.optBoolean("symlink"),
        mode = o.optString("mode"),
        childCount = o.optInt("childCount")
    )

    /** Lists a folder. Returns the error text on failure. */
    suspend fun list(dir: String): Result<ExplorerListing> {
        val q = if (dir.isBlank()) "" else "?dir=${enc(dir)}"
        val (code, o) = get("/api/fs/browse$q")
        if (code != 200 || o == null || !o.has("currentPath")) {
            return Result.failure(Exception(o?.err(code) ?: "Cannot open folder"))
        }
        val arr = o.optJSONArray("items") ?: JSONArray()
        val entries = (0 until arr.length()).map { parseEntry(arr.getJSONObject(it)) }
        return Result.success(ExplorerListing(o.optString("currentPath"), o.optString("parentPath"), o.optString("homePath"), entries))
    }

    suspend fun roots(): Pair<String, List<ExplorerRoot>> {
        val (code, o) = get("/api/fs/roots")
        if (code != 200 || o == null) return "" to emptyList()
        val arr = o.optJSONArray("roots") ?: JSONArray()
        return o.optString("home") to (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            ExplorerRoot(r.optString("name"), r.optString("path"), r.optString("kind"), r.optLong("total"), r.optLong("free"))
        }
    }

    suspend fun stat(path: String): Result<ExplorerStat> {
        val (code, o) = get("/api/fs/stat?path=${enc(path)}")
        if (code != 200 || o == null || !o.optBoolean("success")) return Result.failure(Exception(o?.err(code) ?: "Details unavailable"))
        return Result.success(
            ExplorerStat(
                name = o.optString("name"), path = o.optString("path"), isDir = o.optBoolean("isDir"), size = o.optLong("size"),
                modTime = o.optLong("modTime"), mode = o.optString("mode"), modeOctal = o.optString("modeOctal"),
                symlinkTarget = o.optString("symlinkTarget"), mime = o.optString("mime"), readable = o.optBoolean("readable"),
                writable = o.optBoolean("writable"), executable = o.optBoolean("executable"), files = o.optInt("files"),
                dirs = o.optInt("dirs"), partial = o.optBoolean("partial"),
                category = o.optString("category"), sniffedMime = o.optString("sniffedMime")
            )
        )
    }

    private fun simple(code: Int, o: JSONObject?): FsResult =
        if (code == 200 && o?.optBoolean("success") == true) FsResult(true, o.optString("path").ifBlank { null }, count = o.optInt("files"))
        else FsResult(false, error = o?.err(code) ?: "Failed")

    suspend fun create(dir: String, name: String, isDir: Boolean): FsResult =
        post("/api/fs/new", JSONObject().put("dir", dir).put("name", name).put("isDir", isDir)).let { (c, o) -> simple(c, o) }

    suspend fun rename(path: String, newName: String): FsResult =
        post("/api/fs/rename", JSONObject().put("path", path).put("newName", newName)).let { (c, o) -> simple(c, o) }

    suspend fun chmod(path: String, mode: String): FsResult =
        post("/api/fs/chmod", JSONObject().put("path", path).put("mode", mode)).let { (c, o) -> simple(c, o) }

    private fun batch(code: Int, o: JSONObject?, total: Int): FsResult {
        if (o == null || !o.has("results")) return FsResult(false, error = o?.err(code) ?: "Failed", failed = total)
        val results = o.optJSONArray("results") ?: JSONArray()
        val firstError = (0 until results.length()).map { results.getJSONObject(it) }
            .firstOrNull { it.optString("error").isNotBlank() }
            ?.let { "${File(it.optString("source")).name}: ${it.optString("error")}" }
        val failed = o.optInt("failed")
        return FsResult(failed == 0, error = firstError, failed = failed, count = results.length() - failed)
    }

    suspend fun transfer(sources: List<String>, targetDir: String, move: Boolean, conflict: ConflictPolicy): FsResult =
        post(
            "/api/fs/transfer",
            JSONObject().put("sources", JSONArray(sources)).put("targetDir", targetDir).put("move", move).put("conflict", conflict.wire)
        ).let { (c, o) -> batch(c, o, sources.size) }

    suspend fun delete(paths: List<String>): FsResult =
        post("/api/fs/delete", JSONObject().put("paths", JSONArray(paths))).let { (c, o) -> batch(c, o, paths.size) }

    suspend fun compress(sources: List<String>, target: String): FsResult =
        post("/api/fs/compress", JSONObject().put("sources", JSONArray(sources)).put("target", target)).let { (c, o) -> simple(c, o) }

    suspend fun extract(path: String, targetDir: String? = null): FsResult {
        val (c, o) = post("/api/fs/extract", JSONObject().put("path", path).put("targetDir", targetDir ?: ""))
        return if (c == 200 && o?.optBoolean("success") == true) FsResult(true, o.optString("targetDir"), count = o.optInt("files"))
        else FsResult(false, error = o?.err(c) ?: "Extract failed")
    }

    suspend fun archiveEntries(path: String): Result<Pair<List<ArchiveEntry>, Boolean>> {
        val (code, o) = get("/api/fs/archive/list?path=${enc(path)}")
        if (code != 200 || o == null || !o.optBoolean("success")) return Result.failure(Exception(o?.err(code) ?: "Cannot read archive"))
        val arr = o.optJSONArray("entries") ?: JSONArray()
        return Result.success(
            (0 until arr.length()).map { arr.getJSONObject(it).let { e -> ArchiveEntry(e.optString("name"), e.optLong("size"), e.optBoolean("isDir")) } } to
                o.optBoolean("truncated")
        )
    }

    suspend fun search(dir: String, query: String, includeHidden: Boolean): Result<Pair<List<ExplorerEntry>, Boolean>> {
        val (code, o) = get("/api/fs/search?dir=${enc(dir)}&q=${enc(query)}&hidden=${if (includeHidden) 1 else 0}", transferClient)
        if (code != 200 || o == null || !o.optBoolean("success")) return Result.failure(Exception(o?.err(code) ?: "Search failed"))
        val arr = o.optJSONArray("items") ?: JSONArray()
        return Result.success((0 until arr.length()).map { parseEntry(arr.getJSONObject(it)) } to o.optBoolean("truncated"))
    }

    /**
     * Streams [input] into [targetPath] on the bridge machine. [onProgress] gets bytes sent so far.
     * conflict: "rename" (default, keeps both), "overwrite" or "fail". Cancelling the calling coroutine stops the
     * transfer; the bridge then removes the partly written file.
     */
    suspend fun upload(
        input: () -> InputStream,
        length: Long,
        targetPath: String,
        conflict: String = "rename",
        onProgress: (Long) -> Unit = {}
    ): FsResult = withContext(Dispatchers.IO) {
        val job = coroutineContext[kotlinx.coroutines.Job]
        val body = object : RequestBody() {
            override fun contentType() = OCTET
            override fun contentLength() = if (length > 0) length else -1
            override fun writeTo(sink: BufferedSink) {
                input().use { stream ->
                    val src = stream.source()
                    var sent = 0L
                    while (true) {
                        if (job?.isActive == false) throw java.io.IOException("Upload cancelled")
                        val n = src.read(sink.buffer, 64 * 1024)
                        if (n == -1L) break
                        sent += n
                        sink.emitCompleteSegments()
                        onProgress(sent)
                    }
                }
            }
        }
        try {
            val req = Request.Builder().url("$base/api/fs/upload?path=${enc(targetPath)}&conflict=$conflict").post(body).build()
            transferClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                val o = runCatching { JSONObject(raw ?: "") }.getOrNull()
                if (resp.code == 200 && o?.optBoolean("success") == true) FsResult(true, o.optString("path"))
                else FsResult(false, error = o?.optString("error")?.ifBlank { null } ?: errorOf(resp.code, raw))
            }
        } catch (e: Exception) {
            FsResult(false, error = e.message ?: "Upload failed")
        }
    }

    /** Downloads a bridge file into [dest] (used to hand files to other apps when the bridge is not on this phone). */
    suspend fun download(path: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): FsResult = withContext(Dispatchers.IO) {
        try {
            transferClient.newCall(Request.Builder().url(rawUrl(path)).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext FsResult(false, error = errorOf(resp.code, resp.body?.string()))
                val body = resp.body ?: return@withContext FsResult(false, error = "Empty response")
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                val tmp = File(dest.path + ".part")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) return@withContext FsResult(false, error = "Could not save download")
                FsResult(true, dest.absolutePath)
            }
        } catch (e: Exception) {
            FsResult(false, error = e.message ?: "Download failed")
        }
    }
}
