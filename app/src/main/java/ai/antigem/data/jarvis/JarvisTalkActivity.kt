package ai.antigem.data.jarvis

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * The invisible screen the Jarvis tile and the "Jarvis" shortcut open: Jarvis starts listening while it is in front
 * (that is what lets its microphone service start with the app in the background), then it closes at once. See
 * [JarvisLauncher].
 */
class JarvisTalkActivity : Activity() {
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
        runCatching { JarvisLauncher.launch(this) }.onFailure { Log.w("JarvisLauncher", "launch: ${it.message}", it) }
        finish()
        overridePendingTransition(0, 0)
    }
}
