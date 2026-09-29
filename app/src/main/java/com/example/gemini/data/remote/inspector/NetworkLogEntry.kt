package com.example.gemini.data.remote.inspector

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight metadata model for a single network call logged by the Network Inspector.
 * Stores only routing endpoints, timing, status codes, and headers with zero body allocations.
 */
data class NetworkLogEntry(
    val id: String,
    val timestamp: Long,
    val url: String,
    val path: String,
    val method: String,
    val protocol: String = "HTTP/2",
    val serviceType: String = "AGY Daemon (gRPC)",
    val startTimeMs: Long,
    val durationMs: Long = -1L,
    val statusCode: Int = 0,
    val grpcStatus: String? = null,
    val isStreaming: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val error: String? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(startTimeMs))
        }

    val isConnected: Boolean
        get() = durationMs < 0L && error == null

    val isSuccess: Boolean
        get() = error == null && (statusCode in 200..299 || statusCode == 0 && isConnected) && (grpcStatus == null || grpcStatus == "0" || grpcStatus.startsWith("0 "))

    val displayName: String
        get() {
            return if (path.contains("/")) {
                val segments = path.trim('/').split('/')
                segments.lastOrNull()?.ifBlank { path } ?: path
            } else {
                path
            }
        }
}
