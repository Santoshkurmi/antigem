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
 * Resolves media URIs produced by AGY Hub / Cortex (such as Termux host paths or remote paths)
 * into locally accessible files or data URIs for rendering with Coil.
 */
object HubMediaResolver {
    private const val TAG = "HubMediaResolver"
    private val memoryCache = ConcurrentHashMap<String, String>() // rawUri -> localUri
    private val downloadMutex = Mutex()

    /**
     * Obtains the target local cache file for a given raw URI.
     */
    fun getLocalCacheFile(context: Context, rawUri: String): File {
        val clean = rawUri.removePrefix("file://")
        val baseName = clean.substringAfterLast('/').ifBlank { "img_${System.currentTimeMillis()}.jpg" }
        val safeName = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(context.cacheDir, "hub_media").apply { if (!exists()) mkdirs() }
        return File(dir, safeName)
    }

    /**
     * Checks if the URI is already local or already cached on disk.
     */
    fun isLocalOrCached(context: Context, uriOrPath: String): Boolean {
        if (uriOrPath.isBlank()) return false
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return true
        }
        val clean = uriOrPath.removePrefix("file://")
        val directFile = File(clean)
        if (directFile.exists() && directFile.canRead()) return true

        val cached = getLocalCacheFile(context, uriOrPath)
        return cached.exists() && cached.length() > 0
    }

    /**
     * Synchronously returns the cached local URI if present in memory or disk,
     * otherwise returns the original URI.
     */
    fun getResolvedUriSync(context: Context, uriOrPath: String): String {
        if (uriOrPath.isBlank()) return ""
        if (uriOrPath.startsWith("data:image/") || uriOrPath.startsWith("http://") || uriOrPath.startsWith("https://") || uriOrPath.startsWith("content://")) {
            return uriOrPath
        }
        memoryCache[uriOrPath]?.let { return it }

        val clean = uriOrPath.removePrefix("file://")
        val directFile = File(clean)
        if (directFile.exists() && directFile.canRead()) {
            val res = "file://${directFile.absolutePath}"
            memoryCache[uriOrPath] = res
            return res
        }

        val cached = getLocalCacheFile(context, uriOrPath)
        if (cached.exists() && cached.length() > 0) {
            val res = "file://${cached.absolutePath}"
            memoryCache[uriOrPath] = res
            return res
        }

        return uriOrPath
    }

    /**
     * Asynchronously downloads file data from daemon LanguageServerService/ReadFile if not cached locally.
     * Returns local file:/// URI or data URI.
     */
    suspend fun resolveMediaUri(
        context: Context,
        rawUri: String,
        agyHubClient: AgyHubClient = AgyHubClient(),
        hubUrl: String = AgyHubClient.DEFAULT_HUB_URL
    ): String = withContext(Dispatchers.IO) {
        if (rawUri.isBlank()) return@withContext ""
        if (rawUri.startsWith("data:image/") || rawUri.startsWith("http://") || rawUri.startsWith("https://") || rawUri.startsWith("content://")) {
            return@withContext rawUri
        }

        memoryCache[rawUri]?.let { return@withContext it }

        val clean = rawUri.removePrefix("file://")
        val directFile = File(clean)
        if (directFile.exists() && directFile.canRead()) {
            val localUri = "file://${directFile.absolutePath}"
            memoryCache[rawUri] = localUri
            return@withContext localUri
        }

        val cachedFile = getLocalCacheFile(context, rawUri)
        if (cachedFile.exists() && cachedFile.length() > 0) {
            val localUri = "file://${cachedFile.absolutePath}"
            memoryCache[rawUri] = localUri
            return@withContext localUri
        }

        downloadMutex.withLock {
            if (cachedFile.exists() && cachedFile.length() > 0) {
                val localUri = "file://${cachedFile.absolutePath}"
                memoryCache[rawUri] = localUri
                return@withContext localUri
            }

            try {
                val formattedUri = if (rawUri.startsWith("file://")) rawUri else "file://$clean"
                val res = agyHubClient.readFileAsBase64(formattedUri, hubUrl)
                res.onSuccess { base64Data ->
                    if (base64Data.isNotBlank()) {
                        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                        cachedFile.parentFile?.mkdirs()
                        cachedFile.writeBytes(bytes)
                        val localUri = "file://${cachedFile.absolutePath}"
                        memoryCache[rawUri] = localUri
                        Log.d(TAG, "Downloaded hub media: $localUri (${bytes.size} bytes)")
                        return@withContext localUri
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

