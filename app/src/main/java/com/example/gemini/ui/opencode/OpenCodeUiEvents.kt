package com.example.gemini.ui.opencode

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** App-wide requests from deep inside the chat (an error card's button) to the chat screen. */
object OpenCodeUiEvents {
    private val _openProviders = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Open Settings → OpenCode → Providers (reconnect an account). */
    val openProviders: SharedFlow<Unit> = _openProviders

    fun requestProviders() { _openProviders.tryEmit(Unit) }

    /** Error-card action that opens the OpenCode providers page. */
    const val ACTION_PROVIDERS = "opencode_providers"
}
