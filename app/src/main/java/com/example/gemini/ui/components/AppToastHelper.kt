package com.example.gemini.ui.components

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class ChatToastType {
    SUCCESS,
    ERROR,
    INFO
}

data class ChatToast(
    val id: Long = System.currentTimeMillis(),
    val message: String,
    val type: ChatToastType = ChatToastType.INFO
)

object AppToastHelper {
    private val _toastFlow = MutableSharedFlow<ChatToast>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastFlow = _toastFlow.asSharedFlow()

    fun showToast(message: String, type: ChatToastType? = null) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return
        val resolvedType = type ?: run {
            val lower = trimmed.lowercase()
            when {
                lower.contains("success") || lower.contains("signed in") || lower.contains("logged in") ||
                lower.contains("saved") || lower.contains("switched") || lower.contains("copied") ||
                lower.contains("connected") || lower.contains("ready") -> ChatToastType.SUCCESS

                lower.contains("error") || lower.contains("fail") || lower.contains("cannot") ||
                lower.contains("can't") || lower.contains("invalid") || lower.contains("refused") ||
                lower.contains("timeout") || lower.contains("exception") || lower.contains("denied") -> ChatToastType.ERROR

                else -> ChatToastType.INFO
            }
        }
        _toastFlow.tryEmit(ChatToast(message = trimmed, type = resolvedType))
    }
}
