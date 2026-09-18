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
    private val imageRamCache = ConcurrentHashMap<String, String>()       // rawUri -> data:image/xxx;base64,...
    private val documentRamCache = ConcurrentHashMap<String, String>()    // filePath -> content string
    private val downloadMutex = Mutex()

    fun getCachedDocument(path: String): String? = documentRamCache[path]

    fun putCachedDocument(path: String, content: String) {
        documentRamCache[path] = content
    }

    fun invalidateDocument(path: String) {
        documentRamCache.remove(path)
    }

    fun clearRamCache() {
        imageRamCache.clear()
        documentRamCache.clear()
    }

    fun getLocalCacheFile(context: Context, rawUri: String): File {
        val clean = rawUri.removePrefix("file://")
        val baseName = clean.substringAfterLast('/').ifBlank { "img_${System.currentTimeMillis()}.jpg" }
        val pathHash = clean.hashCode().let { if (it < 0) -it else it }.toString(16)
        val safeName = "${pathHash}_${baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
        val dir = File(context.cacheDir, "hub_media").apply { if (!exists()) mkdirs() }
        return File(dir, safeName)
    }

    /**
     * Checks if the URI is already local or already cached in RAM memory.
     */
    fun isLocalOrCached(context: Context, uriOrPath: String): Boolean {
        if (uriOrPath.isBlank()) return false
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return true
        }
        if (imageRamCache.containsKey(uriOrPath) || documentRamCache.containsKey(uriOrPath)) {
            return true
        }
        val clean = uriOrPath.removePrefix("file://")
        val directFile = File(clean)
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
        imageRamCache[uriOrPath]?.let { return it }

        val clean = uriOrPath.removePrefix("file://")
        val directFile = File(clean)
        if (directFile.exists() && directFile.canRead()) {
            val res = "file://${directFile.absolutePath}"
            imageRamCache[uriOrPath] = res
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

        imageRamCache[rawUri]?.let { return@withContext it }

        val clean = rawUri.removePrefix("file://")
        val directFile = File(clean)
        if (directFile.exists() && directFile.canRead()) {
            val localUri = "file://${directFile.absolutePath}"
            imageRamCache[rawUri] = localUri
            return@withContext localUri
        }

        downloadMutex.withLock {
            imageRamCache[rawUri]?.let { return@withContext it }

            try {
                val formattedUri = if (rawUri.startsWith("file://")) rawUri else "file://$clean"
                val res = agyHubClient.readFileAsBase64(formattedUri, hubUrl)
                res.onSuccess { base64Data ->
                    if (base64Data.isNotBlank()) {
                        val ext = clean.substringAfterLast('.', "jpg").lowercase()
                        val mime = when (ext) {
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            "gif" -> "image/gif"
                            "svg" -> "image/svg+xml"
                            else -> "image/jpeg"
                        }
                        val dataUri = "data:$mime;base64,$base64Data"
                        imageRamCache[rawUri] = dataUri
                        Log.d(TAG, "Cached hub media in RAM: $rawUri")
                        return@withContext dataUri
                    }
                }.onFailure { err ->
                    Log.w(TAG, "Failed to read file from hub: $rawUri: ${err.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving media $rawUri: ${e.message}")
            }
        }

        return@withContext rawUri
    }
}

