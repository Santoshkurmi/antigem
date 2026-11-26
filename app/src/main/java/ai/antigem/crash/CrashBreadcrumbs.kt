package ai.antigem.crash

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the user did just before a crash, for [CrashHandler]'s report: the last screens shown (activities and the main
 * pages), whether the app was on screen, and the open chat's agent. Memory only (nothing is written until a crash) and
 * a few array writes per screen change, so it costs nothing while the app runs. Only screen and agent names are kept,
 * never chat titles, paths or text.
 */
object CrashBreadcrumbs {
    private const val SIZE = 15

    private val times = LongArray(SIZE)
    private val events = arrayOfNulls<String>(SIZE)
    private var next = 0
    private var count = 0

    /** Activities started and not stopped yet: more than 0 means the app is on screen. */
    @Volatile
    private var started = 0

    /** Agent of the chat on screen (AgentKind name), "none" without one. Set by ChatViewModel. */
    @Volatile
    var chatAgent: String? = null

    /** Records one step (one short line, no line breaks). */
    fun add(event: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            times[next] = now
            events[next] = event
            next = (next + 1) % SIZE
            if (count < SIZE) count++
        }
    }

    val isInForeground: Boolean get() = started > 0

    /** The recorded steps, oldest first, as "HH:mm:ss  event" lines. */
    fun lines(): List<String> {
        val copyTimes = LongArray(SIZE)
        val copyEvents = arrayOfNulls<String>(SIZE)
        val n: Int
        val start: Int
        synchronized(this) {
            times.copyInto(copyTimes)
            events.copyInto(copyEvents)
            n = count
            start = (next - count + SIZE) % SIZE
        }
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        return (0 until n).map { i ->
            val at = (start + i) % SIZE
            "${format.format(Date(copyTimes[at]))}  ${copyEvents[at]}"
        }
    }

    /** Records activities being shown and the app going to the background. */
    val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
        }

        override fun onActivityResumed(activity: Activity) {
            add("${activity.javaClass.simpleName} shown")
        }

        override fun onActivityStopped(activity: Activity) {
            started = maxOf(0, started - 1)
            // (not for a rotation or theme change: the activity comes straight back)
            if (started == 0 && !activity.isChangingConfigurations) add("app in background")
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
