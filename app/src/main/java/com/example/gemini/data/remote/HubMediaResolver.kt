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

