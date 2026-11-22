package ai.antigem.data.voice

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * The invisible screen the Voice call tile and shortcut open: it starts the call while it is in front (that is what
 * lets the call use the microphone with the app in the background), then closes at once. See [VoiceCallLauncher].
 */
class VoiceCallLaunchActivity : Activity() {
    private var handled = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        runCatching { VoiceCallLauncher.launch(this) }.onFailure { Log.w("VoiceCallLauncher", "launch: ${it.message}", it) }
        finish()
        overridePendingTransition(0, 0)
    }
}
