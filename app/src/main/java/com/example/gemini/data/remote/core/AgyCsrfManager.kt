package com.example.gemini.data.remote.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Thread-safe manager for Antigravity (AGY) CSRF authentication tokens.
 * Utilizes Kotlin Coroutines Mutex to prevent race conditions during token refresh.
 */
class AgyCsrfManager(
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val TAG = "AgyCsrfManager"
        private val CSRF_PATTERN = Pattern.compile(""""csrfToken":\s*"([^"]+)"""")
        private val CSRF_HTML_PATTERN = Pattern.compile("""(?:csrf[_-]?token|csrfToken)["']?\s*[:=]\s*["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)

        val instance by lazy { AgyCsrfManager() }
    }

    private val cachedTokens = ConcurrentHashMap<String, String>()
    private val refreshMutex = Mutex()

    private val _csrfEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val csrfEvents = _csrfEvents.asSharedFlow()

    /**
     * Gets existing CSRF token or safely fetches a new one from the AGY Hub.
     */
    suspend fun getCsrfToken(
        hubUrl: String,
        forceRefresh: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val normalizedUrl = hubUrl.trimEnd('/')
        if (!forceRefresh) {
            cachedTokens[normalizedUrl]?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }

        refreshMutex.withLock {
            if (!forceRefresh) {
                cachedTokens[normalizedUrl]?.takeIf { it.isNotBlank() }?.let { return@withLock it }
            }

            val token = fetchTokenFromHub(normalizedUrl)
            if (token.isNotBlank()) {
                cachedTokens[normalizedUrl] = token
                Log.d(TAG, "Successfully acquired CSRF token for $normalizedUrl (${token.take(8)}...)")
            } else {
                Log.w(TAG, "Failed to resolve CSRF token from $normalizedUrl")
            }
            token
        }
    }

    /**
     * Synchronous CSRF token retrieval for OkHttp interceptors with thread safety.
     */
    fun getCsrfTokenSync(
        hubUrl: String,
        forceRefresh: Boolean = false
    ): String {
        val normalizedUrl = hubUrl.trimEnd('/')
        if (!forceRefresh) {
            cachedTokens[normalizedUrl]?.takeIf { it.isNotBlank() }?.let { return it }
        }

        synchronized(this) {
            if (!forceRefresh) {
                cachedTokens[normalizedUrl]?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val token = fetchTokenFromHub(normalizedUrl)
            if (token.isNotBlank()) {
                cachedTokens[normalizedUrl] = token
                Log.d(TAG, "Successfully acquired CSRF token for $normalizedUrl (${token.take(8)}...)")
            } else {
                Log.w(TAG, "Failed to resolve CSRF token from $normalizedUrl")
            }
            return token
        }
    }

    /**
     * Clears cached token for a specific URL or all URLs.
     */
    fun clearToken(hubUrl: String? = null) {
        if (hubUrl != null) {
            cachedTokens.remove(hubUrl.trimEnd('/'))
        } else {
            cachedTokens.clear()
        }
    }

    fun notifyCsrfExpired(hubUrl: String, endpoint: String) {
        clearToken(hubUrl)
        _csrfEvents.tryEmit("CSRF token expired on $endpoint ($hubUrl). Refreshing...")
    }

    private fun fetchTokenFromHub(hubUrl: String): String {
        val candidates = listOf(
            "$hubUrl/",
            "$hubUrl/login",
            "$hubUrl/auth"
        )

        for (url in candidates) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "antiGem-Android-Native")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val html = resp.body?.string() ?: ""

                    // 1. Try standard JSON csrfToken pattern
                    val m1 = CSRF_PATTERN.matcher(html)
                    if (m1.find()) {
                        val token = m1.group(1)?.trim()
                        if (!token.isNullOrBlank()) return token
                    }

                    // 2. Try HTML/tag csrfToken pattern
                    val m2 = CSRF_HTML_PATTERN.matcher(html)
                    if (m2.find()) {
                        val token = m2.group(1)?.trim()
                        if (!token.isNullOrBlank()) return token
                    }

                    // 3. Try header
                    resp.header("x-codeium-csrf-token")?.takeIf { it.isNotBlank() }?.let {
                        return it
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Attempt to fetch CSRF token from $url failed: ${e.message}")
            }
        }
        return ""
    }
}

