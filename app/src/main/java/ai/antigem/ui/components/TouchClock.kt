package ai.antigem.ui.components

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * When the user last touched a view. Web content (mini apps, interactive chat blocks) may write the clipboard only right
 * after a tap: a page that writes it on its own can plant a command the user pastes into the terminal later.
 */
class TouchClock {
    @Volatile
    private var last = 0L

    /** Records touches on [view] (they still reach the view as before). */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View) {
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_UP) last = SystemClock.uptimeMillis()
            false
        }
    }

    /** A touch within the last [withinMs]. */
    fun recent(withinMs: Long = 5_000L): Boolean = last != 0L && SystemClock.uptimeMillis() - last <= withinMs
}
