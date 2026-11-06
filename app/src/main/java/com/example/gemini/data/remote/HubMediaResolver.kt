package com.example.gemini.data.remote

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-RAM caching and resolver for media & documents produced by AGY Hub / Cortex
 * (such as Termux host paths, images, or remote files). Avoids disk writes.
 */
object HubMediaResolver {
    private const val TAG = "HubMediaResolver"
    private val imageRamCache = ConcurrentHashMap<String, String>()          // normalizedUri -> data:image/xxx;base64,...
    private val imageBytesCache = ConcurrentHashMap<String, ByteArray>()     // normalizedUri -> ByteArray
    private val documentRamCache = ConcurrentHashMap<String, String>()       // normalizedUri -> content string
    private val downloadMutex = Mutex()

    fun normalizeKey(uriOrPath: String): String {
        return uriOrPath.trim().removePrefix("file://")
    }

    fun getCachedDocument(path: String): String? = documentRamCache[normalizeKey(path)]

    fun putCachedDocument(path: String, content: String) {
        documentRamCache[normalizeKey(path)] = content
    }

    fun invalidateDocument(path: String) {
        documentRamCache.remove(normalizeKey(path))
    }

    fun getImageBytes(uriOrPath: String): ByteArray? {
        val key = normalizeKey(uriOrPath)
        return imageBytesCache[key]
    }

    /** A file served by the IDE bridge (Claude chat attachments): needs the bridge's auth header, so Coil cannot load it. */
    fun isBridgeFileUrl(uri: String): Boolean =
        uri.startsWith(com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/claude/attachments/")

    /** Downloads a bridge file with the authenticated client (null when it is gone or the bridge is offline). */
    suspend fun fetchBridgeFile(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            AgyBridgeService.instance.client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()
    }

    /** Saves a bridge file into the app cache (to open it with another app or the file viewer). */
    suspend fun downloadBridgeFile(context: Context, url: String, name: String): File? {
        val bytes = fetchBridgeFile(url) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "claude-attachments").apply { mkdirs() }
                File(dir, name.substringAfterLast('/').ifBlank { "attachment" }).apply { writeBytes(bytes) }
            }.getOrNull()
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
            val fixed = com.example.gemini.data.preferences.AuthPreferences.rebaseBridgeUrl(s)
            return fixed.takeIf { com.example.gemini.data.preferences.AuthPreferences.isBridgeUrl(it) }
        }
        if (s.startsWith("content://")) return null
        if (!com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode) return null
        val path = s.removePrefix("file://")
        // a file that exists on this phone (for example a video you attached) is always used directly
        if (runCatching { File(path).canRead() }.getOrDefault(false)) return null
        val base = com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
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
        mediaCacheFile(context, url).takeIf { it.exists() && it.length() > 0 }

    /** Streams a bridge file (video) into the app cache with the authenticated client; reused next time. Null on failure. */
    suspend fun downloadBridgeMedia(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        cachedBridgeMedia(context, url)?.let { return@withContext it }
        val target = mediaCacheFile(context, url)
        val part = File(target.parentFile, target.name + ".part")
        try {
            AgyBridgeService.instance.client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                body.byteStream().use { input -> part.outputStream().use { out -> input.copyTo(out) } }
            }
            if (part.length() > 0 && part.renameTo(target)) target else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not download bridge media: ${e.message}")
            null
        } finally {
            if (part.exists()) part.delete()
        }
    }

    fun clearRamCache() {
        imageRamCache.clear()
        imageBytesCache.clear()
        documentRamCache.clear()
    }

    /**
     * Checks if the URI is already local or already cached in RAM memory.
     */
    fun isLocalOrCached(context: Context, uriOrPath: String): Boolean {
        if (uriOrPath.isBlank()) return false
        if (isBridgeFileUrl(uriOrPath)) return imageRamCache.containsKey(normalizeKey(uriOrPath))
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return true
        }
        val key = normalizeKey(uriOrPath)
        if (imageBytesCache.containsKey(key) || imageRamCache.containsKey(key) || documentRamCache.containsKey(key)) {
            return true
        }
        val directFile = File(key)
        return directFile.exists() && directFile.canRead()
    }

    /**
     * Synchronously returns the in-RAM cached data URI or local URI if present,
     * otherwise returns the original URI.
     */
    fun getResolvedUriSync(context: Context, uriOrPath: String): String {
        if (uriOrPath.isBlank()) return ""
        if (isBridgeFileUrl(uriOrPath)) return imageRamCache[normalizeKey(uriOrPath)] ?: ""
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return uriOrPath
        }
        val key = normalizeKey(uriOrPath)
        imageRamCache[key]?.let { return it }

        val directFile = File(key)
        if (directFile.exists() && directFile.canRead()) {
            val res = "file://${directFile.absolutePath}"
            imageRamCache[key] = res
            return res
        }

        return uriOrPath
    }

    /**
     * Asynchronously downloads file data into in-RAM cache from daemon LanguageServerService/ReadFile.
     * Returns in-memory data:image/... URI with zero disk writes.
     */
    suspend fun resolveMediaUri(
        context: Context,
        rawUri: String,
        agyHubClient: AgyHubClient = AgyHubClient(),
        hubUrl: String = com.example.gemini.data.preferences.AuthPreferences.currentHubUrl
    ): String = withContext(Dispatchers.IO) {
        if (rawUri.isBlank()) return@withContext ""
        if (isBridgeFileUrl(rawUri)) {
            val key = normalizeKey(rawUri)
            imageRamCache[key]?.let { return@withContext it }
            val bytes = fetchBridgeFile(rawUri) ?: return@withContext ""
            val mime = java.net.URLConnection.guessContentTypeFromName(rawUri.substringAfterLast("%2F")) ?: "image/jpeg"
            imageBytesCache[key] = bytes
            return@withContext "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}".also { imageRamCache[key] = it }
        }
        if (rawUri.startsWith("data:image/") || rawUri.startsWith("http://") || rawUri.startsWith("https://") || rawUri.startsWith("content://")) {
            return@withContext rawUri
        }

        val key = normalizeKey(rawUri)
        imageRamCache[key]?.let { return@withContext it }

        val directFile = File(key)
        if (directFile.exists() && directFile.canRead()) {
            val localUri = "file://${directFile.absolutePath}"
            imageRamCache[key] = localUri
            return@withContext localUri
        }

        downloadMutex.withLock {
            imageRamCache[key]?.let { return@withContext it }

            try {
                val formattedUri = "file://$key"
                Log.d("ANTI_MEDIA", "HubMediaResolver: Fetching $formattedUri from AGY Hub ($hubUrl)")
                val res = agyHubClient.readFileAsBase64(formattedUri, hubUrl)
                res.onSuccess { base64Data ->
                    if (base64Data.isNotBlank()) {
                        val ext = key.substringAfterLast('.', "jpg").lowercase()
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
                        val dataUri = "data:$mime;base64,$base64Data"
                        try {
                            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                            imageBytesCache[key] = bytes
                        } catch (_: Exception) {}
                        imageRamCache[key] = dataUri
                        documentRamCache[key] = base64Data
                        Log.d("ANTI_MEDIA", "HubMediaResolver: Cached in RAM for $rawUri (len=${dataUri.length})")
                        return@withContext dataUri
                    }
                }.onFailure { err ->
                    Log.w("ANTI_MEDIA", "HubMediaResolver: Failed to read file from hub: $rawUri: ${err.message}")
                }
            } catch (e: Exception) {
                Log.e("ANTI_MEDIA", "HubMediaResolver: Error resolving media $rawUri: ${e.message}")
            }
        }

        return@withContext rawUri
    }
}

