package com.example.gemini.data.remote.inspector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thread-safe singleton manager for capturing and inspecting real-time network activity.
 * Designed with a zero-cost fast-path when disabled to ensure 100% native performance.
 */
object NetworkInspectorManager {
    private const val MAX_LOG_CAPACITY = 1000

    @Volatile
    var isEnabled: Boolean = false

    @Volatile
    var isFloatingBubbleEnabled: Boolean = false

    private val lock = Any()
    private val buffer = ArrayDeque<NetworkLogEntry>(MAX_LOG_CAPACITY)

    private val _logs = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val logs: StateFlow<List<NetworkLogEntry>> = _logs.asStateFlow()

    private val _activeCallsCount = MutableStateFlow(0)
    val activeCallsCount: StateFlow<Int> = _activeCallsCount.asStateFlow()

    private val _activeStreamsCount = MutableStateFlow(0)
    val activeStreamsCount: StateFlow<Int> = _activeStreamsCount.asStateFlow()

    fun recordStart(entry: NetworkLogEntry) {
        if (!isEnabled) return
        synchronized(lock) {
            if (buffer.size >= MAX_LOG_CAPACITY) {
                buffer.removeLast()
            }
            buffer.addFirst(entry)
            _logs.value = buffer.toList()
            recomputeCounts()
        }
    }

    fun recordComplete(
        id: String,
        statusCode: Int,
        grpcStatus: String?,
        durationMs: Long
    ) {
        if (!isEnabled) return
        synchronized(lock) {
            val idx = buffer.indexOfFirst { it.id == id }
            if (idx != -1) {
                val existing = buffer[idx]
                val updated = existing.copy(
                    statusCode = statusCode,
                    grpcStatus = grpcStatus,
                    durationMs = durationMs
                )
                buffer[idx] = updated
                _logs.value = buffer.toList()
                recomputeCounts()
            }
        }
    }

    fun recordError(id: String, errorMsg: String, durationMs: Long) {
        if (!isEnabled) return
        synchronized(lock) {
            val idx = buffer.indexOfFirst { it.id == id }
            if (idx != -1) {
                val existing = buffer[idx]
                val updated = existing.copy(
                    durationMs = durationMs,
                    error = errorMsg
                )
                buffer[idx] = updated
                _logs.value = buffer.toList()
                recomputeCounts()
            }
        }
    }

    fun clearLogs() {
        synchronized(lock) {
            val activeEntries = buffer.filter { it.durationMs < 0 && it.error == null }
            buffer.clear()
            for (entry in activeEntries.reversed()) {
                buffer.addFirst(entry)
            }
            _logs.value = buffer.toList()
            recomputeCounts()
        }
    }

    private fun recomputeCounts() {
        var active = 0
        for (item in buffer) {
            if (item.durationMs < 0 && item.error == null) {
                active++
            }
        }
        _activeCallsCount.value = active
        _activeStreamsCount.value = active
    }
}
