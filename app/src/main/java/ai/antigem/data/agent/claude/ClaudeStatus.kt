package ai.antigem.data.agent.claude

import androidx.compose.ui.graphics.Color
import ai.antigem.i18n.L

/**
 * Claude Code health as the UI shows it (status dot, accounts, empty chat). Colors follow AGY's [SystemStatus]
 * palette so both agents read the same way: grey offline, blue checking, purple signed out, green ready, red error.
 */
enum class ClaudeStatus(
    private val labelText: () -> String,
    val colorHex: Long
) {
    /** First status check still running. */
    CHECKING({ L.agent.claudeStatus.checking }, 0xFF3B82F6),

    /** The bridge did not answer. */
    BRIDGE_OFFLINE({ L.agent.claudeStatus.bridgeOffline }, 0xFF9CA3AF),

    /** The running bridge cannot serve Claude (an older binary, or started with Claude turned off): restart it. */
    BRIDGE_OUTDATED({ L.agent.claudeStatus.restartNeeded }, 0xFFF97316),

    /** No `claude` binary on the device. */
    NOT_INSTALLED({ L.agent.claudeStatus.claudeCodeNotFound }, 0xFFF97316),

    /** Installed but not signed in. */
    SIGNED_OUT({ L.agent.claudeStatus.signInRequired }, 0xFFA855F7),

    /** OAuth sign-in in progress. */
    SIGNING_IN({ L.agent.claudeStatus.signingIn }, 0xFF06B6D4),

    READY({ L.agent.claudeStatus.ready }, 0xFF22C55E),

    /** The bridge answered but the check failed (e.g. `claude auth status` crashed). */
    ERROR({ L.agent.claudeStatus.error }, 0xFFEF4444);
    val label: String get() = labelText()

    val dotColor: Color
        get() = Color(colorHex)

    /** Messages can go out; the CLI itself reports anything the status check could not see. */
    val canSend: Boolean
        get() = this == READY || this == CHECKING || this == ERROR

    /** Something on the device has to change first (bridge, install), so sending would only fail. */
    val isBlocking: Boolean
        get() = this == BRIDGE_OFFLINE || this == BRIDGE_OUTDATED || this == NOT_INSTALLED
}
