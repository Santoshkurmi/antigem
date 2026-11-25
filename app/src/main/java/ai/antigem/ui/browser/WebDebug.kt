package ai.antigem.ui.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import ai.antigem.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView debugging (DevTools) only while something of AntiGem uses it. While it is on, the shell user (adb, Wireless
 * debugging, every app allowed in Shizuku) and root can attach to every WebView of the app: chat, Google sign-in, mini
 * apps. Holders: "plugins" (an enabled plugin: the plugin host drives tabs through DevTools), "cdp" (browser_mcp's
 * DevTools sessions), "rea" (REA's DevTools bridge). It goes off [IDLE_OFF_MS] after the last holder let go. Debug
 * builds keep it on.
 */
object WebDebug {
    private const val IDLE_OFF_MS = 120_000L
    private val holders = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val off = Runnable { if (holders.isEmpty()) apply(false) }

    /** [who] needs DevTools now: on at once (on the main thread when called from it, else posted first thing). */
    fun hold(who: String) {
        holders.add(who)
        main.removeCallbacks(off)
        apply(true)
    }

    fun drop(who: String) {
        holders.remove(who)
        if (holders.isEmpty()) {
            main.removeCallbacks(off)
            main.postDelayed(off, IDLE_OFF_MS)
        }
    }

    fun set(who: String, on: Boolean) = if (on) hold(who) else drop(who)

    private fun apply(on: Boolean) {
        val want = on || BuildConfig.DEBUG
        val run = Runnable { runCatching { WebView.setWebContentsDebuggingEnabled(want) } }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.postAtFrontOfQueue(run)
    }
}
