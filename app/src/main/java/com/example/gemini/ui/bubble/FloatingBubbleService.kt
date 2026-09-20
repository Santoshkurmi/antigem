package com.example.gemini.ui.bubble

import android.content.Context
import android.provider.Settings
import com.example.gemini.data.service.TermuxService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FloatingBubbleManager {
    private val _isBubbleEnabled = MutableStateFlow(false)
    val isBubbleEnabled: StateFlow<Boolean> = _isBubbleEnabled.asStateFlow()

    private val _isMainAppForeground = MutableStateFlow(false)
    val isMainAppForeground: StateFlow<Boolean> = _isMainAppForeground.asStateFlow()

    private val _isActivityOpen = MutableStateFlow(false)
    val isActivityOpen: StateFlow<Boolean> = _isActivityOpen.asStateFlow()

    val isServiceRunning: StateFlow<Boolean> get() = isBubbleEnabled

    fun setMainAppForeground(foreground: Boolean) {
        _isMainAppForeground.value = foreground
    }

    fun setActivityOpen(open: Boolean) {
        _isActivityOpen.value = open
    }

    fun setBubbleEnabled(enabled: Boolean) {
        _isBubbleEnabled.value = enabled
    }

    fun start(context: Context) {
        if (Settings.canDrawOverlays(context)) {
            _isBubbleEnabled.value = true
            TermuxService.start(context)
        }
    }

    fun stop(context: Context) {
        _isBubbleEnabled.value = false
    }
}

/** Backward compatibility alias for FloatingBubbleService calls */
typealias FloatingBubbleService = FloatingBubbleManager
