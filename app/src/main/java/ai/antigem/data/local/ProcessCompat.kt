package ai.antigem.data.local

import android.os.Build
import java.util.concurrent.TimeUnit

// Process.waitFor(timeout, unit), Process.isAlive() and Process.destroyForcibly() only exist from
// Android 8.0 (API 26). On Android 7.x (API 24/25) calling them throws NoSuchMethodError (an Error,
// so `catch (e: Exception)` does not catch it), and core library desugaring does not backport them.
// These helpers call the real methods on API 26+ and an equivalent fallback below that.

/** Same as [Process.waitFor] with a timeout: true if the process exited before the time ran out. */
fun Process.waitForCompat(timeout: Long, unit: TimeUnit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return waitFor(timeout, unit)
    // Same polling the JDK's default Process.waitFor(long, TimeUnit) does
    val total = unit.toNanos(timeout)
    val start = System.nanoTime()
    while (isAliveCompat) {
        val left = total - (System.nanoTime() - start)
        if (left <= 0) return false
        Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(left) + 1, 100L))
    }
    return true
}

/** Same as [Process.isAlive]. */
val Process.isAliveCompat: Boolean
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        isAlive
    } else {
        try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

/** Same as [Process.destroyForcibly]; below API 26 it falls back to [Process.destroy]. */
fun Process.destroyForciblyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}
