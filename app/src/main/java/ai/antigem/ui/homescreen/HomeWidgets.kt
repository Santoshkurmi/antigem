package ai.antigem.ui.homescreen

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import ai.antigem.BuildConfig
import ai.antigem.data.homescreen.HomeScripts
import ai.antigem.data.homescreen.HomeSnapshot
import ai.antigem.data.voice.VoiceCallLauncher
import ai.antigem.data.preferences.AuthPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/** AntiGem's three home-screen widgets. */
enum class WidgetKind(private val titleText: () -> String, private val descriptionText: () -> String, val receiver: Class<out GlanceAppWidgetReceiver>) {
    CHATS({ L.homescreen.homeWidgets.chats }, { L.homescreen.homeWidgets.yourNewestOrPinnedChatsLive }, ChatsWidgetReceiver::class.java),
    ACTIONS({ L.homescreen.homeWidgets.quickActions }, { L.homescreen.homeWidgets.oneTapButtonsYouChooseNew }, ActionsWidgetReceiver::class.java),
    SCRIPTS({ L.homescreen.homeWidgets.scripts }, { L.homescreen.homeWidgets.yourScriptsFromShortcutsLikeTermux }, ScriptsWidgetReceiver::class.java);
    val title: String get() = titleText()
    val description: String get() = descriptionText()

    fun widget(): GlanceAppWidget = when (this) {
        CHATS -> ChatsWidget()
        ACTIONS -> ActionsWidget()
        SCRIPTS -> ScriptsWidget()
    }

    companion object {
        fun ofProvider(className: String?): WidgetKind? = entries.firstOrNull { it.receiver.name == className }
    }
}

/**
 * Keeps the widgets up to date and lets the app place them. Widgets read [ai.antigem.data.homescreen.HomeSnapshot];
 * this redraws them when it changes, when the look in Settings changes, or when the theme does.
 */
object HomeWidgets {
    private const val TAG = "HomeWidgets"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _version = MutableStateFlow(0)
    /** Bumped to redraw every widget (settings or theme changed). */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile private var themeModeCache: String? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("home_widgets", Context.MODE_PRIVATE)

    /** The app's light / dark mode ("LIGHT", "DARK", or the phone's). */
    fun themeMode(context: Context): String =
        themeModeCache ?: runCatching { AuthPreferences(context.applicationContext).getThemeModeSync() }.getOrDefault("SYSTEM")
            .also { themeModeCache = it }

    /** The Chats widget is waiting to be redrawn. */
    private val pending = MutableStateFlow(false)
    private val refreshLoop = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The saved chat list changed. The Chats widget is redrawn by one loop: at once while the app is not on screen;
     * while it is, not at all (the widgets can't be seen then) until the app leaves the screen, and then at once with
     * the newest data. Changes that come meanwhile are merged into one redraw; none is lost.
     *
     * Why not while the app is on screen: Glance builds a widget on the main thread, 80-130 ms in a debug build, and
     * after each redraw it went on re-processing the widget for several seconds by itself (measured: 18 times in 9 s
     * after one redraw), which made the chat stutter while a reply streamed. In split-screen or a floating window
     * with the home screen visible, the widgets catch up when the app leaves the screen.
     */
    fun onChatsChanged(context: Context) {
        val app = context.applicationContext
        pending.value = true
        if (!refreshLoop.compareAndSet(false, true)) return
        scope.launch {
            while (true) {
                pending.first { it }
                // the app is on screen: wait until it leaves it
                VoiceCallLauncher.appVisible.first { !it }
                pending.value = false
                HomeSnapshot.publishToWidgets()
                runCatching { ChatsWidget().updateAll(app) }.onFailure { Log.w(TAG, "update: ${it.message}") }
            }
        }
    }

    /** Every widget again (the look or the theme changed). */
    fun refreshAll(context: Context) {
        themeModeCache = null
        _version.value++
        val app = context.applicationContext
        // the look changed: every widget is redrawn now, with the newest data
        HomeSnapshot.publishToWidgets()
        scope.launch {
            WidgetKind.entries.forEach { k -> runCatching { k.widget().updateAll(app) } }
        }
    }

    fun refreshScripts(context: Context) {
        HomeScripts.touch()
        val app = context.applicationContext
        scope.launch { runCatching { ScriptsWidget().updateAll(app) } }
    }

    fun count(context: Context, kind: WidgetKind): Int =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, kind.receiver)).size }.getOrDefault(0)

    /** The launcher can place a widget the app asks for (Android 8+). */
    fun canPin(context: Context): Boolean = Build.VERSION.SDK_INT >= 26 &&
        runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    /** Asks the launcher to place a [kind] widget. */
    fun pin(context: Context, kind: WidgetKind) {
        val app = context.applicationContext
        scope.launch {
            runCatching { GlanceAppWidgetManager(app).requestPinGlanceAppWidget(kind.receiver) }
                .onFailure { Log.w(TAG, "pin: ${it.message}") }
        }
    }

    /**
     * Android 15+: the widget picker shows each widget as it really looks (with your chats). Once per app version; Android
     * limits how often this may change.
     */
    fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val app = context.applicationContext
        val p = prefs(app)
        if (p.getInt("previews_version", 0) == BuildConfig.VERSION_CODE) return
        scope.launch {
            runCatching {
                val m = GlanceAppWidgetManager(app)
                WidgetKind.entries.forEach { k -> m.setWidgetPreviews(k.receiver.kotlin) }
                p.edit().putInt("previews_version", BuildConfig.VERSION_CODE).apply()
            }.onFailure { Log.w(TAG, "previews: ${it.message}") }
        }
    }
}

/** Reused preference keys of the widgets' own state. */
internal object WidgetKeys {
    val FILTER = stringPreferencesKey("filter")
    val ACTIONS = stringPreferencesKey("actions")
    val LABELS = stringPreferencesKey("labels")
}
