package com.example.gemini.data.remote

import androidx.compose.ui.graphics.Color
import com.example.gemini.data.preferences.AuthPreferences

/**
 * Unified system connection state enum.
 * Single source of truth for UI indicators, status labels, and send gating across the app.
 */
enum class SystemStatus(
    val label: String,
    val colorHex: Long,
    val canSend: Boolean
) {
    /** Bridge (:8080) is offline or unreachable */
    OFFLINE("Offline", 0xFF9CA3AF, false),

    /** Bridge (:8080) is connected, Antigravity Hub (:8090) is starting/idle */
    STARTING("Starting...", 0xFFF59E0B, false),

    /** Hub (:8090) is running, but CSRF token is not yet acquired */
    ACQUIRING_CSRF("Acquiring Token...", 0xFF06B6D4, false),

    /** Hub (:8090) + CSRF token ready, actively verifying authentication */
    CHECKING_AUTH("Checking Auth...", 0xFF3B82F6, false),

    /** Hub (:8090) + CSRF token ready, but user is not logged in / auth failed */
    UNAUTHENTICATED("Sign In Required", 0xFFA855F7, false),

    /** Everything connected, token ready, user authenticated - fully operational */
    READY("Ready", 0xFF22C55E, true),

    /** Fatal bridge or hub error */
    ERROR("Error", 0xFFEF4444, false);

    val dotColor: Color
        get() = Color(colorHex)
}

/**
 * Unified system connection state representing the true state of the Go IDE bridge (Layer 1)
 * and the Antigravity Hub daemon (Layer 2).
 */
sealed class SystemConnectionState {
    abstract val status: SystemStatus

    /**
     * The Go IDE bridge is offline or unreachable.
     */
    object Offline : SystemConnectionState() {
        override val status: SystemStatus get() = SystemStatus.OFFLINE
    }

    /**
     * The Go IDE bridge is connected and actively reporting AGY Hub status.
     *
     * @param hubStatus "starting", "online", "stopped", "error", or "idle"
     * @param hubUrl The configured AGY Hub endpoint URL
     * @param bridgeUrl The configured IDE Bridge endpoint URL
     * @param error Any error message reported by the hub supervisor
     * @param isAuth Whether the user is currently authenticated with the hub
     * @param isAuthChecking Whether an auth verification call is in progress
     * @param hasCsrfToken Whether a valid CSRF token has been received and cached
     */
    data class Connected(
        val hubStatus: String = "idle",
        val hubUrl: String = AuthPreferences.currentHubUrl,
        val bridgeUrl: String = AuthPreferences.currentBridgeHttpUrl,
        val error: String? = null,
        val isAuth: Boolean = true,
        val isAuthChecking: Boolean = false,
        val hasCsrfToken: Boolean = false
    ) : SystemConnectionState() {
        override val status: SystemStatus
            get() = when {
                error != null || hubStatus == "error" -> SystemStatus.ERROR
                hubStatus == "stopped" -> SystemStatus.OFFLINE
                hubStatus == "starting" || hubStatus == "idle" -> SystemStatus.STARTING
                hubStatus == "online" -> when {
                    !hasCsrfToken -> SystemStatus.ACQUIRING_CSRF
                    isAuthChecking -> SystemStatus.CHECKING_AUTH
                    !isAuth -> SystemStatus.UNAUTHENTICATED
                    else -> SystemStatus.READY
                }
                else -> SystemStatus.STARTING
            }
    }

    /**
     * A fatal bridge connection or socket configuration error.
     */
    data class Error(val message: String) : SystemConnectionState() {
        override val status: SystemStatus get() = SystemStatus.ERROR
    }

    val isBridgeOnline: Boolean
        get() = this is Connected

    val isHubOnline: Boolean
        get() = status == SystemStatus.READY || status == SystemStatus.CHECKING_AUTH || status == SystemStatus.UNAUTHENTICATED || status == SystemStatus.ACQUIRING_CSRF

    val isReady: Boolean
        get() = status == SystemStatus.READY

    val isAuth: Boolean
        get() = status != SystemStatus.UNAUTHENTICATED

    val canSend: Boolean
        get() = status.canSend

    val dotColor: Color
        get() = status.dotColor

    val label: String
        get() = status.label
}
