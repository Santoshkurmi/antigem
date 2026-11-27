package ai.antigem.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Community languages: translations hosted in the antigem_languages repository on GitHub. The app ships only English,
 * so every other language comes from here (or is made by the user).
 *
 * The repository's index.json (rebuilt by its publish workflow) lists every pack with its SHA-256; a pack is
 * languages/<code>/<id>.json, downloaded from the commit that last changed it, so the file always matches the hash.
 * Downloads are saved byte for byte by [LanguageManager.installRepoPack]: an installed pack's SHA-256 against the
 * index tells whether an update is waiting. Nothing is fetched until the Language page (or a tool) asks for the list,
 * or, on launch while the app is in a community language, [LanguageUpdateCheck] looks for that language's update.
 */
object LanguageRepo {
    const val REPO = "Santoshkurmi/antigem_languages"
    const val PAGE = "https://github.com/$REPO"
    const val CONTRIBUTING = "$PAGE/blob/HEAD/CONTRIBUTING.md"
    const val SHARE_FORM = "$PAGE/issues/new?template=share-translation.yml"
    const val PROBLEM_FORM = "$PAGE/issues/new?template=translation-problem.yml"
    const val REQUEST_FORM = "$PAGE/issues/new?template=request-language.yml"
    private const val RAW = "https://raw.githubusercontent.com/$REPO"
    /** HEAD = the repository's default branch. */
    const val INDEX_URL = "$RAW/HEAD/index.json"
    private const val MAX_SIZE = 8L * 1024 * 1024

    val PACK_ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val PATH = Regex("^languages/[A-Za-z0-9-]+/[a-z0-9]+(-[a-z0-9]+)*\\.json$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val COMMIT = Regex("^[0-9a-f]{40}$")

    /** One translation in the repository. [translated] / [total] are counted against the repository's English. */
    class Pack(
        val id: String, val code: String, val name: String, val nativeName: String, val variant: String, val rtl: Boolean,
        val description: String, val authors: List<LanguageManager.Author>, val basedOn: String, val appVersion: String,
        val translated: Int, val outdated: Int, val total: Int,
        val path: String, val commit: String, val updated: String, val size: Long, val sha256: String,
    ) {
        val label: String get() = LanguageManager.labelOf(name, nativeName, variant)
        /** The language's id in the app once downloaded. */
        val localId: String get() = LanguageManager.repoId(id)
        val percent: Int get() = if (total <= 0) 0 else (translated * 100L / total).toInt().coerceIn(0, 100)
        /** Pinned to the commit that last changed the file (it always matches [sha256]); HEAD for an older index. */
        val url: String get() = "$RAW/${commit.ifBlank { "HEAD" }}/$path"
        val pageUrl: String get() = "$PAGE/blob/HEAD/$path"
    }

    enum class State { AVAILABLE, INSTALLED, UPDATE }

    /** Why the list or a download failed; the UI shows its own text for [kind], tools show [message]. */
    class RepoException(val kind: Kind, message: String) : Exception(message)
    enum class Kind { OFFLINE, NOT_PUBLISHED, BAD_DATA, NEWER_APP, CHANGED, TOO_BIG }

    // ------------------------------------------------------------------ state (read by the UI)

    /** The packs of the last list (from the network, or the copy saved on the phone). */
    var packs by mutableStateOf<List<Pack>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    /** Why the last refresh failed, or null. */
    var error by mutableStateOf<RepoException?>(null)
        private set
    /** When the list was last fetched (ms), 0 = never in this run. */
    var fetchedAt by mutableStateOf(0L)
        private set
    /** Pack id → state, for the packs of [packs] (recomputed by [recheck]). */
    var states by mutableStateOf<Map<String, State>>(emptyMap())
        private set
    /** Pack ids being downloaded now. */
    var downloading by mutableStateOf<Set<String>>(emptySet())
        private set

    private val client by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    private val refreshLock = Mutex()
    private var loadedCache = false
    @Volatile private var prewarmed = false
    /** Installed file → (length, modified, SHA-256), so a hash is computed once per file version. */
    private val hashes = HashMap<String, Triple<Long, Long, String>>()

    private fun cacheFile() = File(LanguageManager.repoDir(), "index.cache.json")

    fun stateOf(packId: String): State = states[packId] ?: State.AVAILABLE

    fun updates(): List<Pack> = packs.filter { states[it.id] == State.UPDATE }

    /** A pack by its id, its app id ("repo.<id>") or its name. */
    fun find(query: String): Pack? {
        val q = query.trim().removePrefix(LanguageManager.REPO_PREFIX)
        if (q.isEmpty()) return null
        return packs.firstOrNull { it.id.equals(q, true) }
            ?: packs.firstOrNull { it.label.equals(q, true) || it.name.equals(q, true) || it.nativeName.equals(q, true) }
            ?: packs.firstOrNull { it.code.equals(q, true) }
    }

    /** The last list saved on the phone, shown until (or instead of, offline) a fresh one. Call off the main thread. */
    fun loadCached() {
        if (loadedCache) return
        loadedCache = true
        if (packs.isNotEmpty()) return
        runCatching { cacheFile().takeIf { it.exists() }?.readText()?.let { publish(parse(it)) } }
    }

    /**
     * Reads the languages on the phone and the saved community list ahead of the Language page (when Settings opens),
     * so the page opens complete instead of filling in while it slides in. Once per run; off the main thread.
     */
    suspend fun prewarm() {
        if (prewarmed) return
        prewarmed = true
        try {
            withContext(Dispatchers.IO) {
                // Settings draws its first frames first
                delay(250)
                runCatching { LanguageManager.refreshList() }
                refreshLock.withLock { runCatching { loadCached() } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Settings closed right away: the next time it opens reads them
            prewarmed = false
            throw e
        }
    }

    /**
     * Shows [list] with each pack's state, in one step: the rows never show "Download" for a moment before they find
     * out the pack is installed.
     */
    private fun publish(list: List<Pack>) {
        val s = statesOf(list)
        Snapshot.withMutableSnapshot {
            packs = list
            states = s
        }
    }

    /**
     * Fetches the list from GitHub (the Language page calls it each time it opens; [maxAgeMs] skips a fetch made
     * moments ago). On failure the saved list stays and [error] says why.
     */
    suspend fun refresh(maxAgeMs: Long = 0): Result<List<Pack>> = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            loadCached()
            if (maxAgeMs > 0 && fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < maxAgeMs && error == null) {
                return@withLock Result.success(packs)
            }
            loading = true
            try {
                val text = fetchIndex()
                val list = parse(text)
                runCatching { cacheFile().writeText(text) }
                publish(list)
                fetchedAt = System.currentTimeMillis()
                error = null
                Result.success(list)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val re = e as? RepoException ?: RepoException(Kind.OFFLINE, e.message ?: e.javaClass.simpleName)
                error = re
                runCatching { recheck() }
                Result.failure(re)
            } finally {
                loading = false
            }
        }
    }

    private fun fetchIndex(): String {
        val req = Request.Builder().url(INDEX_URL).header("Cache-Control", "no-cache").build()
        val r = try { client.newCall(req).execute() } catch (e: Exception) {
            throw RepoException(Kind.OFFLINE, "Couldn't reach GitHub: ${e.message ?: e.javaClass.simpleName}")
        }
        r.use {
            if (r.code == 404) throw RepoException(Kind.NOT_PUBLISHED, "The language list isn't published yet (no index.json in $REPO)")
            if (!r.isSuccessful) throw RepoException(Kind.OFFLINE, "GitHub answered ${r.code}")
            return r.body?.string() ?: throw RepoException(Kind.BAD_DATA, "The language list is empty")
        }
    }

    private fun parse(text: String): List<Pack> {
        val o = runCatching { JSONObject(text) }.getOrElse { throw RepoException(Kind.BAD_DATA, "The language list isn't valid JSON") }
        if (o.optString("format") != "antigem-language-index") throw RepoException(Kind.BAD_DATA, "Not a language list")
        if (o.optInt("format_version", 1) > 1) throw RepoException(Kind.NEWER_APP, "The language list needs a newer AntiGem")
        val arr = o.optJSONArray("languages") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { p -> runCatching { pack(p) }.getOrNull() }
            .distinctBy { it.id }
    }

    private fun pack(p: JSONObject): Pack {
        val id = p.getString("id")
        require(PACK_ID.matches(id) && id.length <= 60)
        val path = p.getString("path")
        require(PATH.matches(path) && path.substringAfterLast('/') == "$id.json")
        val sha = p.getString("sha256").lowercase()
        require(HEX64.matches(sha))
        val commit = p.optString("commit").lowercase().takeIf { COMMIT.matches(it) }.orEmpty()
        val authors = p.optJSONArray("authors")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
                val name = x.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                LanguageManager.Author(name, x.optString("github").trim(), x.optString("url").trim().takeIf { it.startsWith("https://") }.orEmpty(), x.optString("role").trim())
            }
        }.orEmpty()
        return Pack(
            id = id, code = p.optString("code").ifBlank { id }, name = p.optString("name").ifBlank { id },
            nativeName = p.optString("native_name"), variant = p.optString("variant"), rtl = p.optBoolean("rtl"),
            description = p.optString("description"), authors = authors, basedOn = p.optString("based_on"),
            appVersion = p.optString("app_version"),
            translated = p.optInt("translated"), outdated = p.optInt("outdated"), total = p.optInt("total"),
            path = path, commit = commit, updated = p.optString("updated"), size = p.optLong("size"), sha256 = sha,
        )
    }

    /** Recomputes [states] from the installed files. Call off the main thread. */
    fun recheck() {
        states = statesOf(packs)
    }

    private fun statesOf(list: List<Pack>): Map<String, State> {
        val installed = LanguageManager.repoPackIds().toSet()
        return list.associate { p ->
            p.id to when {
                p.id !in installed -> State.AVAILABLE
                installedSha(p.id) == p.sha256 -> State.INSTALLED
                else -> State.UPDATE
            }
        }
    }

    private fun installedSha(packId: String): String? = synchronized(hashes) {
        val f = LanguageManager.repoFile(LanguageManager.repoId(packId))
        if (!f.exists()) return null
        hashes[packId]?.let { (len, mod, sha) -> if (len == f.length() && mod == f.lastModified()) return sha }
        val sha = runCatching { sha256(f.readBytes()) }.getOrNull() ?: return null
        hashes[packId] = Triple(f.length(), f.lastModified(), sha)
        sha
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Downloads [pack] (or its update), checks it against the list's size and SHA-256 and saves it. If it's the
     * language on screen, the app redraws in the new texts.
     */
    suspend fun install(pack: Pack): Result<LanguageManager.Language> = withContext(Dispatchers.IO) {
        if (pack.id in downloading) return@withContext Result.failure(RepoException(Kind.CHANGED, "Already downloading ${pack.label}"))
        downloading = downloading + pack.id
        try {
            if (pack.size > MAX_SIZE) throw RepoException(Kind.TOO_BIG, "${pack.label} is too big (${pack.size} bytes)")
            val bytes = try {
                client.newCall(Request.Builder().url(pack.url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw RepoException(if (r.code == 404) Kind.CHANGED else Kind.OFFLINE, "GitHub answered ${r.code} for ${pack.path}")
                    val body = r.body ?: throw RepoException(Kind.BAD_DATA, "Empty download")
                    if (body.contentLength() > MAX_SIZE) throw RepoException(Kind.TOO_BIG, "${pack.label} is too big")
                    body.byteStream().use { s -> s.readNBytesCompat(MAX_SIZE + 1) }
                }
            } catch (e: RepoException) { throw e } catch (e: Exception) {
                throw RepoException(Kind.OFFLINE, "Couldn't download ${pack.label}: ${e.message ?: e.javaClass.simpleName}")
            }
            if (bytes.size > MAX_SIZE) throw RepoException(Kind.TOO_BIG, "${pack.label} is too big")
            if (sha256(bytes) != pack.sha256) {
                throw RepoException(Kind.CHANGED, "${pack.label} changed on GitHub while the list was loading: refresh the list and try again")
            }
            val lang = try { LanguageManager.installRepoPack(pack.id, bytes) } catch (e: Exception) {
                throw RepoException(Kind.BAD_DATA, "${pack.label} isn't a valid language file: ${e.message}")
            }
            synchronized(hashes) { hashes.remove(pack.id) }
            recheck()
            runCatching { LanguageManager.refreshList() }
            Result.success(lang)
        } catch (e: RepoException) {
            Result.failure(e)
        } finally {
            downloading = downloading - pack.id
        }
    }

    /** After a community language was removed: its pack shows as available again. Call off the main thread. */
    fun removed(packId: String) {
        synchronized(hashes) { hashes.remove(packId) }
        recheck()
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
