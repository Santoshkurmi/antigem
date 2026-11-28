package ai.antigem.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app text size (Settings → Appearance → App text size): the text of the app's own screens, menus, lists and
 * dialogs, on top of Android's own font size and without touching other apps.
 *
 * It is set on the activity's configuration when the activity starts (not at the Compose root), because every popup
 * window (AlertDialog, Dialog, DropdownMenu, ModalBottomSheet) starts its own composition that takes the text size
 * from the window again. So a new size takes effect by restarting the activity once, like a change of Android's own
 * font size does. Chat messages take it back out (see ChatScreen): they keep Android's size × their own chat slider.
 *
 * Used by MainActivity and FloatingChatActivity.
 */
object AppFontScale {
    const val MIN = 0.75f
    const val MAX = 1.30f

    private const val PREFS = "anti_gem_sync_prefs" // AuthPreferences' synchronous mirror
    const val KEY = "app_font_scale"

    /** The saved app text size (1 = Android's own size). Read before anything is drawn, so no preference flow. */
    fun read(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, 1f).coerceIn(MIN, MAX)

    /**
     * Call from the activity's attachBaseContext, after super: starts it with Android's font size × the app text size.
     * Returns the app text size it now uses (1 when it could not be set).
     */
    fun applyTo(activity: Activity, base: Context): Float {
        val scale = read(base)
        if (scale == 1f) return 1f
        return try {
            activity.applyOverrideConfiguration(Configuration().apply {
                fontScale = base.resources.configuration.fontScale * scale
            })
            scale
        } catch (_: IllegalStateException) {
            1f
        }
    }

    /** A Settings → Appearance that asked for the restart opens again on its page (same process, so a plain field). */
    private var reopenSettingsIn: String? = null

    /** Restarts the activity of [context] with the newly saved size; its Settings → Appearance opens again (see [consumeReopenSettings]). */
    fun restart(context: Context) {
        val activity = activityOf(context) ?: return
        reopenSettingsIn = activity.javaClass.name
        activity.recreate()
    }

    /** True once, in the restarted activity whose Settings asked for the restart. */
    fun consumeReopenSettings(context: Context): Boolean {
        val activity = activityOf(context) ?: return false
        if (reopenSettingsIn != activity.javaClass.name) return false
        reopenSettingsIn = null
        return true
    }

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/** The app text size this screen's activity started with (1 = none); chat messages divide it out. */
val LocalAppFontScale = staticCompositionLocalOf { 1f }
