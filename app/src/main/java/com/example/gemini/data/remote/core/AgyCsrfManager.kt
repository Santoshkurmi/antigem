package com.example.gemini.data.remote.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Clean, lightweight holder for the AGY CSRF authentication token.
 * Populated directly by the Go Hub Supervisor over WebSocket / status updates.
 */
class AgyCsrfManager {
    companion object {
        private const val TAG = "AgyCsrfManager"
        val instance by lazy { AgyCsrfManager() }
    }

    private val _token = MutableStateFlow("")

    val token: String
        get() = _token.value

    private val _csrfEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val csrfEvents: SharedFlow<String> = _csrfEvents.asSharedFlow()

    fun getCsrfToken(hubUrl: String = "", forceRefresh: Boolean = false): String = token

    fun getCsrfTokenSync(hubUrl: String = "", forceRefresh: Boolean = false): String = token

    fun setCachedToken(hubUrl: String = "", newToken: String) {
        if (newToken.isNotBlank()) {
            _token.value = newToken
            Log.d(TAG, "⚡ [AgyCsrfManager] CSRF token updated (${newToken.take(8)}...)")
        }
    }

    fun clearToken(hubUrl: String? = null) {
        _token.value = ""
    }

    /** Waits up to [timeoutMs] for a token (the hub reports it shortly after it starts); "" if none came. */
    suspend fun awaitToken(timeoutMs: Long): String =
        token.ifBlank { withTimeoutOrNull(timeoutMs) { _token.first { it.isNotBlank() } } ?: "" }

    /**
     * A request was rejected for its CSRF token. [usedToken] is the token it was sent with (null = unknown): the
     * cached token is only dropped when it is still that one. A request sent before the token arrived (or with an
     * older one) must not wipe the valid token that came in meanwhile, which made the next requests fail too.
     */
    fun notifyCsrfExpired(hubUrl: String = "", endpoint: String = "", usedToken: String? = null) {
        val current = token
        if (usedToken != null && usedToken != current) {
            Log.d(TAG, "[AgyCsrfManager] stale CSRF rejection on $endpoint (sent before the current token): kept")
            return
        }
        Log.w(TAG, "⚠️ [AgyCsrfManager] CSRF token expired on $endpoint")
        _token.compareAndSet(current, "")
        _csrfEvents.tryEmit("CSRF token expired on $endpoint")
    }
}
