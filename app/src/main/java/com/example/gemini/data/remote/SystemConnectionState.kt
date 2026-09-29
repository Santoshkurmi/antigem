package com.example.gemini.data.remote

import com.example.gemini.data.preferences.AuthPreferences

/**
 * Unified system connection state representing the true state of the Go IDE bridge (Layer 1)
 * and the Antigravity Hub daemon (Layer 2).
 */
sealed class SystemConnectionState {
    /**
     * The Go IDE bridge is offline or unreachable.
     */
    object Offline : SystemConnectionState()

    /**
     * The Go IDE bridge is connected and actively reporting AGY Hub status.
     *
     * @param hubStatus "starting", "online", "stopped", "error", or "idle"
     * @param hubUrl The configured AGY Hub endpoint URL
     * @param bridgeUrl The configured IDE Bridge endpoint URL
     * @param error Any error message reported by the hub supervisor
     */
    data class Connected(
        val hubStatus: String = "idle",
        val hubUrl: String = AuthPreferences.currentHubUrl,
        val bridgeUrl: String = AuthPreferences.currentBridgeHttpUrl,
        val error: String? = null,
        override val isAuth: Boolean = true
    ) : SystemConnectionState()

    /**
     * A fatal bridge connection or socket configuration error.
     */
    data class Error(val message: String) : SystemConnectionState()

    val isBridgeOnline: Boolean
        get() = this is Connected

    val isHubOnline: Boolean
        get() = this is Connected && this.hubStatus == "online" && com.example.gemini.data.remote.core.AgyCsrfManager.instance.token.isNotBlank()

    val isHubStarting: Boolean
        get() = this is Connected && this.hubStatus == "starting"

    open val isAuth: Boolean
        get() = true
}

