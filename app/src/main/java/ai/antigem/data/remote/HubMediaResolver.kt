package ai.antigem.data.remote

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves media produced by the AGY Hub / Cortex or served by the bridge (images on the agent's machine, chat
 * attachments) into something the app can draw.
 *
 * A fetched image is written once to the app cache ([HUB_MEDIA_DIR]) and handed out as a `file://` uri, so no image
 * data stays in RAM: only a small map of source → cached file is kept. Coil decodes the file on its own thread and
 * keeps the decoded picture in its (size-limited) memory cache. The map is per process (as the old RAM cache was): an
 * image is fetched again after a restart, so a file the agent overwrote at the same path shows its new content.
 */
object HubMediaResolver {
    private const val TAG = "HubMediaResolver"
    /** Cache folder of fetched images (trimmed by [ai.antigem.data.files.CacheJanitor]). */
    const val HUB_MEDIA_DIR = "hub-media"
    /** normalized source → `file://` uri of its cached copy (or of the phone file it already is). */
    private val resolvedFiles = ConcurrentHashMap<String, String>()
    private val downloadMutex = Mutex()

    fun normalizeKey(uriOrPath: String): String {
        return uriOrPath.trim().removePrefix("file://")
    }

    /**
     * The file a source was resolved to in this process (its cached copy, or the phone file it is), or null when it
     * was not resolved yet or the copy was trimmed away since.
     */
    fun cachedFile(uriOrPath: String): File? {
        if (uriOrPath.isBlank()) return null
        val key = normalizeKey(uriOrPath)
        val uri = resolvedFiles[key] ?: return null
        val file = File(uri.removePrefix("file://"))
        if (file.isFile) return file
        resolvedFiles.remove(key, uri)
        return null
    }

    /** The resolved `file://` uri of [key] when its file is still there (a trimmed copy is forgotten). */
    private fun liveUri(key: String): String? {
        val uri = resolvedFiles[key] ?: return null
        if (File(uri.removePrefix("file://")).isFile) return uri
        resolvedFiles.remove(key, uri)
        return null
    }

    private fun mediaFileFor(context: Context, key: String, mimeHint: String?): File {
        val dir = File(context.cacheDir, HUB_MEDIA_DIR)
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        // the file name: a bridge link's ?path=, otherwise the path itself
        val decoded = runCatching { java.net.URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)
        val named = decoded.substringAfter("?path=", "").substringBefore('&').ifBlank { decoded.substringBefore('?') }
        val fromName = named.substringBefore('#').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase().takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }
        val ext = fromName
            ?: mimeHint?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: "img"
        return File(dir, "$hash.$ext")
    }

    /**
     * The copy of a bridge attachment cached by an earlier run of the app. A chat attachment never changes on the bridge
     * (each one is saved under a name of its own), so the copy is still right and a reopened chat shows it at once.
     */
    private fun earlierBridgeCopy(context: Context, key: String): String? {
        val file = mediaFileFor(context, key, null)
        if (!file.isFile || file.length() <= 0) return null
        val uri = "file://${file.absolutePath}"
        resolvedFiles[key] = uri
        return uri
    }

    /** Writes [bytes] as the cached copy of [key] (atomically: a half-written file is never decoded); its uri or null. */
    private fun storeBytes(context: Context, key: String, bytes: ByteArray, mimeHint: String? = null): String? {
        if (bytes.isEmpty()) return null
        return try {
            val target = mediaFileFor(context, key, mimeHint)
            target.parentFile?.mkdirs()
            // a part file of its own: the list and a prefetch may fetch the same picture at the same moment
            val part = File(target.parentFile, "${target.name}.${System.nanoTime()}.part")
            part.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return null
            }
            val uri = "file://${target.absolutePath}"
            resolvedFiles[key] = uri
            ai.antigem.data.files.CacheJanitor.trimAll(context)
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Cannot cache media for $key: ${e.message}")
            null
        }
    }

    /** A file served by the IDE bridge (Claude chat attachments): needs the bridge's auth header, so Coil cannot load it. */
    fun isBridgeFileUrl(uri: String): Boolean =
        uri.startsWith(ai.antigem.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/claude/attachments/")

    /** Downloads a bridge file with the authenticated client (null when it is gone or the bridge is offline). */
    suspend fun fetchBridgeFile(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            AgyBridgeService.instance.client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    /** Saves a bridge file into the app cache (to open it with another app or the file viewer), straight to disk. */
    suspend fun downloadBridgeFile(context: Context, url: String, name: String): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "claude-attachments").apply { mkdirs() }
        val target = File(dir, name.substringAfterLast('/').ifBlank { "attachment" })
        if (streamToFile(url, target) { }) {
            ai.antigem.data.files.CacheJanitor.trimAll(context)
            target
        } else null
    }

    /** How to play or read a media file without copying it first: a file or link the app opens itself, or a bridge stream. */
    data class MediaStream(val uri: android.net.Uri, val headers: Map<String, String>, val viaBridge: Boolean)

    /** The security headers every bridge request needs (also for players that fetch the stream themselves). */
    fun bridgeHeaders(): Map<String, String> = buildMap {
        put(ai.antigem.data.preferences.AuthPreferences.currentFramedHeader, "true")
        ai.antigem.data.preferences.AuthPreferences.edgeHeader()?.let { (n, v) -> put(n, v) }
    }

    private fun bridgeRawUrl(path: String): String =
        ai.antigem.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/') +
            "/api/fs/raw?path=" + java.net.URLEncoder.encode(path, "UTF-8")

    private fun localPathOf(source: String): String =
        if (source.startsWith("file://")) runCatching { android.net.Uri.parse(source).path }.getOrNull() ?: source.removePrefix("file://")
        else source

    /**
     * Where to play [source] from. A web link, `content://` uri or a file this phone can read is used directly. Anything
     * else is a file only the bridge sees (Server mode, or a Linux distro in local mode): it is streamed from
     * `/api/fs/raw`, which supports seeking and never loads the whole file into the bridge's memory.
     */
    fun mediaStreamFor(source: String): MediaStream {
        val s = source.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) {
            val fixed = ai.antigem.data.preferences.AuthPreferences.rebaseBridgeUrl(s)
            if (!ai.antigem.data.preferences.AuthPreferences.isBridgeUrl(fixed)) return MediaStream(android.net.Uri.parse(s), emptyMap(), false)
            // /api/file/read loads the whole file into the bridge's memory; /api/fs/raw streams it
            val u = android.net.Uri.parse(fixed)
            val streamed = if (u.path?.endsWith("/api/file/read") == true) u.getQueryParameter("path")?.let(::bridgeRawUrl) else null
            return MediaStream(android.net.Uri.parse(streamed ?: fixed), bridgeHeaders(), true)
        }
        if (s.startsWith("content://")) return MediaStream(android.net.Uri.parse(s), emptyMap(), false)
        val path = localPathOf(s)
        if (runCatching { File(path).let { it.isFile && it.canRead() } }.getOrDefault(false)) {
            return MediaStream(android.net.Uri.fromFile(File(path)), emptyMap(), false)
        }
        return MediaStream(android.net.Uri.parse(bridgeRawUrl(path)), bridgeHeaders(), true)
    }

    /** A readable file name for [source] (a path, a link, or a bridge link carrying `?path=`). */
    fun displayNameOf(source: String): String {
        val s = source.trim()
        val inner = runCatching { android.net.Uri.parse(s).getQueryParameter("path") }.getOrNull()
        val raw = (inner ?: s).substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).ifBlank { "video" }
    }

    /** Copies [url] from the bridge into [target] in chunks (no whole-file buffer); false on any failure. */
    private suspend fun streamToFile(url: String, target: File, onProgress: (Float?) -> Unit): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        try {
            val client = ai.antigem.data.files.FileExplorerApi.transferClient
            client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val total = body.contentLength()
                val ctx = kotlin.coroutines.coroutineContext
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            ctx.ensureActive() // the player was closed: stop downloading
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                            }
                        }
                    }
                }
            }
            if (part.length() <= 0) return false
            if (target.exists()) target.delete()
            return part.renameTo(target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bridge download failed: ${e.message}")
            return false
        } finally {
            if (part.exists()) part.delete()
        }
    }

    /**
     * Where the bridge serves [source] from, or null when the app can use it directly: a normal link, a `content://`
     * uri, or (local mode) a file on this phone. A link to the bridge needs its security header, and in Server mode a
     * plain path such as `/home/me/clip.mp4` is a file on the SERVER, so it is read through the bridge as well.
     */
    fun bridgeUrlFor(source: String): String? {
        val s = source.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("http://") || s.startsWith("https://")) {
            val fixed = ai.antigem.data.preferences.AuthPreferences.rebaseBridgeUrl(s)
            return fixed.takeIf { ai.antigem.data.preferences.AuthPreferences.isBridgeUrl(it) }
        }
        if (s.startsWith("content://")) return null
        if (!ai.antigem.data.preferences.AuthPreferences.currentRemoteMode) return null
        val path = s.removePrefix("file://")
        // a file that exists on this phone (for example a video you attached) is always used directly
        if (runCatching { File(path).canRead() }.getOrDefault(false)) return null
        val base = ai.antigem.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
        return "$base/api/file/read?path=" + java.net.URLEncoder.encode(path, "UTF-8")
    }

    private fun mediaCacheFile(context: Context, url: String): File {
        val dir = File(context.cacheDir, "bridge-media").apply { mkdirs() }
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        val ext = java.net.URLDecoder.decode(url, "UTF-8").substringBefore('#').substringAfterLast('.', "")
            .takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } } ?: "bin"
        return File(dir, "$hash.$ext")
    }

    /** The file if this bridge media was downloaded before (null otherwise). */
    fun cachedBridgeMedia(context: Context, url: String): File? =
        mediaCacheFile(context, url).takeIf { it.exists() && it.length() > 0 }?.also { ai.antigem.data.files.CacheJanitor.touch(it) }

    /**
     * Copies a bridge file (video) into the app cache, chunk by chunk, trying [url] then each of [alternatives] (an older
     * bridge may lack the streaming endpoint). Reused next time; the cache is trimmed afterwards. Null on failure.
     * One download at a time, so two players asking for the same video never write the same file.
     */
    suspend fun downloadBridgeMedia(
        context: Context,
        url: String,
        alternatives: List<String> = emptyList(),
        onProgress: (Float?) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            cachedBridgeMedia(context, url)?.let { return@withLock it }
            val target = mediaCacheFile(context, url)
            val ok = (listOf(url) + alternatives).distinct().any { streamToFile(it, target, onProgress) }
            ai.antigem.data.files.CacheJanitor.trimAll(context, force = true)
            target.takeIf { ok && it.exists() }
        }
    }

    /** Forgets which sources were resolved (the cached files stay until the cache janitor trims them). */
    fun clearRamCache() {
        resolvedFiles.clear()
    }

    /**
     * True when [uriOrPath] can be drawn right away: a link or data uri, a file on this phone, or a source already
     * resolved to a cached file.
     */
    fun isLocalOrCached(context: Context, uriOrPath: String): Boolean {
        if (uriOrPath.isBlank()) return false
        if (isBridgeFileUrl(uriOrPath)) {
            val key = normalizeKey(uriOrPath)
            return liveUri(key) != null || earlierBridgeCopy(context, key) != null
        }
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return true
        }
        val key = normalizeKey(uriOrPath)
        if (liveUri(key) != null) return true
        val directFile = File(key)
        return directFile.exists() && directFile.canRead()
    }

    /**
     * What to draw for [uriOrPath] right now, without fetching: its cached file, the phone file it is, or the source
     * itself ("" for a bridge file not fetched yet, which Coil could not load without the bridge's header).
     */
    fun getResolvedUriSync(context: Context, uriOrPath: String): String {
        if (uriOrPath.isBlank()) return ""
        if (isBridgeFileUrl(uriOrPath)) {
            val key = normalizeKey(uriOrPath)
            return liveUri(key) ?: earlierBridgeCopy(context, key) ?: ""
        }
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return uriOrPath
        }
        val key = normalizeKey(uriOrPath)
        liveUri(key)?.let { return it }

        val directFile = File(key)
        if (directFile.exists() && directFile.canRead()) {
            val res = "file://${directFile.absolutePath}"
            resolvedFiles[key] = res
            return res
        }

        return uriOrPath
    }

    /**
     * Makes [rawUri] drawable: a bridge attachment or a file on the agent's machine (read through the AGY hub, or the
     * bridge) is fetched once into the app cache and returned as a `file://` uri. Links, data uris and phone files come
     * back as they are; on failure [rawUri] itself ("" for a bridge attachment).
     */
    suspend fun resolveMediaUri(
        context: Context,
        rawUri: String,
        agyHubClient: AgyHubClient = AgyHubClient(),
        hubUrl: String = ai.antigem.data.preferences.AuthPreferences.currentHubUrl
    ): String = withContext(Dispatchers.IO) {
        if (rawUri.isBlank()) return@withContext ""
        if (isBridgeFileUrl(rawUri)) {
            val key = normalizeKey(rawUri)
            liveUri(key)?.let { touch(it); return@withContext it }
            earlierBridgeCopy(context, key)?.let { touch(it); return@withContext it }
            val bytes = fetchBridgeFile(rawUri) ?: return@withContext ""
            val mime = java.net.URLConnection.guessContentTypeFromName(java.net.URLDecoder.decode(rawUri, "UTF-8").substringBefore('#'))
            return@withContext storeBytes(context, key, bytes, mime) ?: ""
        }
        if (rawUri.startsWith("data:image/") || rawUri.startsWith("http://") || rawUri.startsWith("https://") || rawUri.startsWith("content://")) {
            return@withContext rawUri
        }

        val key = normalizeKey(rawUri)
        liveUri(key)?.let { touch(it); return@withContext it }

        val directFile = File(key)
        if (directFile.exists() && directFile.canRead()) {
            val localUri = "file://${directFile.absolutePath}"
            resolvedFiles[key] = localUri
            return@withContext localUri
        }

        downloadMutex.withLock {
            liveUri(key)?.let { return@withContext it }

            // a file on the server / PC (Server mode, Connect to PC): the bridge reads it for every agent, while the AGY
            // hub may not run there at all (only Antigravity uses it)
            if (ai.antigem.data.preferences.AuthPreferences.currentRemoteMode) {
                viaBridge(context, key)?.let { return@withContext it }
            }

            try {
                val formattedUri = "file://$key"
                Log.d("ANTI_MEDIA", "HubMediaResolver: Fetching $formattedUri from AGY Hub ($hubUrl)")
                val res = agyHubClient.readFileAsBase64(formattedUri, hubUrl)
                res.onSuccess { base64Data ->
                    if (base64Data.isNotBlank()) {
                        val ext = key.substringAfterLast('.', "jpg").lowercase()
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
                        val bytes = try { Base64.decode(base64Data, Base64.DEFAULT) } catch (_: Exception) { null }
                        val stored = bytes?.let { storeBytes(context, key, it, mime) }
                        if (stored != null) {
                            Log.d("ANTI_MEDIA", "HubMediaResolver: cached $rawUri (${bytes?.size} bytes)")
                            return@withContext stored
                        }
                    }
                }.onFailure { err ->
                    Log.w("ANTI_MEDIA", "HubMediaResolver: Failed to read file from hub: $rawUri: ${err.message}")
                }
            } catch (e: Exception) {
                Log.e("ANTI_MEDIA", "HubMediaResolver: Error resolving media $rawUri: ${e.message}")
            }
            // the hub could not read it (not running, or not Antigravity's file): the bridge may
            if (!ai.antigem.data.preferences.AuthPreferences.currentRemoteMode) {
                viaBridge(context, key)?.let { return@withContext it }
            }
        }

        return@withContext rawUri
    }

    /** Marks a cached copy as just used, so the cache trim removes it last (phone files are left alone). */
    private fun touch(uri: String) {
        val file = File(uri.removePrefix("file://"))
        if (file.parentFile?.name == HUB_MEDIA_DIR) ai.antigem.data.files.CacheJanitor.touch(file)
    }

    /** Reads a file from the bridge's machine (`/api/fs/raw`) into the cache; its `file://` uri, or null when it cannot. */
    private suspend fun viaBridge(context: Context, path: String): String? {
        if (!path.startsWith("/")) return null
        val bytes = fetchBridgeFile(bridgeRawUrl(path))?.takeIf { it.isNotEmpty() } ?: return null
        val ext = path.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext == "svg") "image/svg+xml" else "image/png"
        return storeBytes(context, path, bytes, mime)?.also {
            Log.d("ANTI_MEDIA", "HubMediaResolver: read $path through the bridge (${bytes.size} bytes)")
        }
    }
}

