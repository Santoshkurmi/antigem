package com.example.gemini.data.remote.core

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
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

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val base = if (trimmed.isBlank()) AuthPreferences.DEFAULT_HUB_URL else trimmed
        return if (!base.startsWith("http://") && !base.startsWith("https://")) {
            "http://$base"
        } else {
            base
        }.trimEnd('/')
    }

    /**
     * Gets existing CSRF token or safely fetches a new one from the AGY Hub.
     */
    suspend fun getCsrfToken(
        hubUrl: String,
        forceRefresh: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(hubUrl)
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
        val normalizedUrl = normalizeUrl(hubUrl)
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
            val normalized = normalizeUrl(hubUrl)
            cachedTokens.remove(normalized)
            cachedTokens.remove(hubUrl.trimEnd('/'))
        } else {
            cachedTokens.clear()
        }
    }

    fun notifyCsrfExpired(hubUrl: String, endpoint: String) {
        Log.w(TAG, "⚠️ [notifyCsrfExpired] Invalidating CSRF cache for $hubUrl (triggered by $endpoint)")
        clearToken(hubUrl)
        _csrfEvents.tryEmit("CSRF token expired on $endpoint ($hubUrl). Refreshing...")
    }

    private fun fetchTokenFromHub(hubUrl: String): String {
        val base = normalizeUrl(hubUrl)
        val candidates = listOf(
            "$base/"
        )

        Log.d(TAG, "🔍 [fetchTokenFromHub] Attempting to fetch CSRF token for '$base' across candidates: $candidates")

        for (url in candidates) {
            try {
                Log.d(TAG, "🌐 [fetchTokenFromHub] Querying candidate: $url")
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "antiGem-Android-Native")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val headerToken = resp.header("x-codeium-csrf-token")
                    val html = resp.body?.string() ?: ""

                    // 1. Try standard JSON csrfToken pattern
                    val m1 = CSRF_PATTERN.matcher(html)
                    if (m1.find()) {
                        val token = m1.group(1)?.trim()
                        if (!token.isNullOrBlank()) {
                            return token
                        }
                    }

                    // 2. Try HTML/tag csrfToken pattern
                    val m2 = CSRF_HTML_PATTERN.matcher(html)
                    if (m2.find()) {
                        val token = m2.group(1)?.trim()
                        if (!token.isNullOrBlank()) {
                            return token
                        }
                    }

                    // 3. Try header
                    if (!headerToken.isNullOrBlank()) {
                        return headerToken
                    }

                    Log.w(TAG, "⚠️ [fetchTokenFromHub] No token matched in body (preview: ${html.take(150).replace('\n', ' ')})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ [fetchTokenFromHub] Attempt to fetch CSRF token from $url threw exception: ${e::class.simpleName}: ${e.message}", e)
            }
        }
        Log.w(TAG, "❌ [fetchTokenFromHub] All candidates exhausted. Returning empty CSRF token for $hubUrl")
        return ""
    }
}

