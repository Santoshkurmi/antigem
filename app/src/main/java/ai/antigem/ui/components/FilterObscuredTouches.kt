package ai.antigem.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Tapjacking guard for approve / allow buttons: while this is on screen, touches that pass through another app's window
 * drawn over this one (an overlay that hides the real button under a harmless one) are dropped. It sets
 * `filterTouchesWhenObscured` on the root of the window it is in (a dialog's own window, or the screen), and restores it
 * when it leaves. Android 12 and newer already block such touches themselves; this covers Android 7–11.
 */
@Composable
fun FilterObscuredTouches() {
    val view = LocalView.current
    DisposableEffect(view) {
        val root = view.rootView
        val before = root.filterTouchesWhenObscured
        root.filterTouchesWhenObscured = true
        onDispose { root.filterTouchesWhenObscured = before }
    }
}
