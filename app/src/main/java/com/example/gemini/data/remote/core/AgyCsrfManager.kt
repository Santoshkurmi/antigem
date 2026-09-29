package com.example.gemini.data.remote.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Clean, lightweight holder for the AGY CSRF authentication token.
 * Populated directly by the Go Hub Supervisor over WebSocket / status updates.
 */
class AgyCsrfManager {
    companion object {
        private const val TAG = "AgyCsrfManager"
        val instance by lazy { AgyCsrfManager() }
    }

    @Volatile
    var token: String = ""
        private set

    private val _csrfEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val csrfEvents: SharedFlow<String> = _csrfEvents.asSharedFlow()

    fun getCsrfToken(hubUrl: String = "", forceRefresh: Boolean = false): String = token

    fun getCsrfTokenSync(hubUrl: String = "", forceRefresh: Boolean = false): String = token

    fun setCachedToken(hubUrl: String = "", newToken: String) {
        if (newToken.isNotBlank()) {
            token = newToken
            Log.d(TAG, "⚡ [AgyCsrfManager] CSRF token updated (${newToken.take(8)}...)")
        }
    }

    fun clearToken(hubUrl: String? = null) {
        token = ""
    }

    fun notifyCsrfExpired(hubUrl: String = "", endpoint: String = "") {
        Log.w(TAG, "⚠️ [AgyCsrfManager] CSRF token expired on $endpoint")
        clearToken()
        _csrfEvents.tryEmit("CSRF token expired on $endpoint")
    }
}
