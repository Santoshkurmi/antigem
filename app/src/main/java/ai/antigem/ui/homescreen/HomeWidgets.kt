package ai.antigem.ui.homescreen

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import ai.antigem.BuildConfig
import ai.antigem.data.homescreen.HomeScripts
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.domain.model.AgentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/** AntiGem's four home-screen widgets. */
enum class WidgetKind(private val titleText: () -> String, private val descriptionText: () -> String, val receiver: Class<out GlanceAppWidgetReceiver>) {
    CHATS({ L.homescreen.homeWidgets.chats }, { L.homescreen.homeWidgets.yourNewestOrPinnedChatsLive }, ChatsWidgetReceiver::class.java),
    ACTIONS({ L.homescreen.homeWidgets.quickActions }, { L.homescreen.homeWidgets.oneTapButtonsYouChooseNew }, ActionsWidgetReceiver::class.java),
    CHAT({ L.homescreen.homeWidgets.oneChat }, { L.homescreen.homeWidgets.aSingleChatItsStatusAnd }, ChatWidgetReceiver::class.java),
    SCRIPTS({ L.homescreen.homeWidgets.scripts }, { L.homescreen.homeWidgets.yourScriptsFromShortcutsLikeTermux }, ScriptsWidgetReceiver::class.java);
    val title: String get() = titleText()
    val description: String get() = descriptionText()

    fun widget(): GlanceAppWidget = when (this) {
        CHATS -> ChatsWidget()
        ACTIONS -> ActionsWidget()
        CHAT -> ChatWidget()
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

    private val _watched = MutableStateFlow<Set<String>>(emptySet())
    /** The chats the One chat widgets show (the saved chat list always keeps them). */
    val watched: StateFlow<Set<String>> = _watched.asStateFlow()

    @Volatile private var themeModeCache: String? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("home_widgets", Context.MODE_PRIVATE)

    @Volatile private var inited = false

    fun init(context: Context) {
        if (inited) return
        inited = true
        _watched.value = prefs(context).all.filterKeys { it.startsWith("chat_of_") }.values.mapNotNull { it as? String }.toSet()
    }

    /** The app's light / dark mode ("LIGHT", "DARK", or the phone's). */
    fun themeMode(context: Context): String =
        themeModeCache ?: runCatching { AuthPreferences(context.applicationContext).getThemeModeSync() }.getOrDefault("SYSTEM")
            .also { themeModeCache = it }

    /** The saved chat list changed. */
    fun onChatsChanged(context: Context, listChanged: Boolean) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                if (listChanged) ChatsWidget().updateAll(app)
                ChatWidget().updateAll(app)
            }.onFailure { Log.w(TAG, "update: ${it.message}") }
        }
    }

    /** Every widget again (the look or the theme changed). */
    fun refreshAll(context: Context) {
        themeModeCache = null
        _version.value++
        val app = context.applicationContext
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

    /** Asks the launcher to place a [kind] widget. A One chat widget shows [chat] (id, agent, title). */
    fun pin(context: Context, kind: WidgetKind, chat: Triple<String, AgentKind, String>? = null) {
        val app = context.applicationContext
        if (chat != null) setPendingChat(app, chat)
        scope.launch {
            runCatching {
                val callback = chat?.let {
                    PendingIntent.getBroadcast(
                        app, it.first.hashCode(),
                        Intent(app, HomeWidgetPinReceiver::class.java)
                            .putExtra(EXTRA_CHAT_ID, it.first)
                            .putExtra(EXTRA_CHAT_AGENT, it.second.name)
                            .putExtra(EXTRA_CHAT_TITLE, it.third),
                        // the launcher adds the new widget's id to it
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    )
                }
                GlanceAppWidgetManager(app).requestPinGlanceAppWidget(kind.receiver, successCallback = callback)
            }.onFailure { Log.w(TAG, "pin: ${it.message}") }
        }
    }

    /** A One chat widget now shows this chat. */
    suspend fun applyChat(context: Context, appWidgetId: Int, id: String, agent: String, title: String) {
        val app = context.applicationContext
        val gid = GlanceAppWidgetManager(app).getGlanceIdBy(appWidgetId)
        updateAppWidgetState(app, gid) { p ->
            p[ChatWidget.KEY_ID] = id
            p[ChatWidget.KEY_AGENT] = agent
            p[ChatWidget.KEY_TITLE] = title
        }
        init(app)
        prefs(app).edit().putString("chat_of_$appWidgetId", id).apply()
        _watched.value = _watched.value + id
        ChatWidget().update(app, gid)
    }

    /** A One chat widget was removed. */
    fun forget(context: Context, appWidgetId: Int) {
        val p = prefs(context)
        p.edit().remove("chat_of_$appWidgetId").apply()
        _watched.value = p.all.filterKeys { it.startsWith("chat_of_") }.values.mapNotNull { it as? String }.toSet()
    }

    // the chat a One chat widget placed from the app will show: its setup screen (if the launcher opens it) takes it
    private fun setPendingChat(context: Context, chat: Triple<String, AgentKind, String>) {
        prefs(context).edit()
            .putString("pending_id", chat.first).putString("pending_agent", chat.second.name)
            .putString("pending_title", chat.third).putLong("pending_at", System.currentTimeMillis())
            .apply()
    }

    /** The chat just asked for from the app (at most 3 minutes ago), taken once. */
    fun takePendingChat(context: Context): Triple<String, String, String>? {
        val p = prefs(context)
        val at = p.getLong("pending_at", 0L)
        val id = p.getString("pending_id", null)
        p.edit().remove("pending_at").remove("pending_id").remove("pending_agent").remove("pending_title").apply()
        if (id == null || System.currentTimeMillis() - at > 3 * 60_000L) return null
        return Triple(id, p.getString("pending_agent", null) ?: AgentKind.AGY.name, p.getString("pending_title", null) ?: "")
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

    const val EXTRA_CHAT_ID = "ai.antigem.widget.CHAT_ID"
    const val EXTRA_CHAT_AGENT = "ai.antigem.widget.CHAT_AGENT"
    const val EXTRA_CHAT_TITLE = "ai.antigem.widget.CHAT_TITLE"
}

/** The launcher placed a One chat widget the app asked for: it shows the chat picked in the app. */
class HomeWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val id = intent.getStringExtra(HomeWidgets.EXTRA_CHAT_ID) ?: return
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val agent = intent.getStringExtra(HomeWidgets.EXTRA_CHAT_AGENT) ?: AgentKind.AGY.name
        val title = intent.getStringExtra(HomeWidgets.EXTRA_CHAT_TITLE).orEmpty()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                HomeWidgets.applyChat(context, widgetId, id, agent, title)
            } catch (e: Exception) {
                Log.w("HomeWidgets", "pin callback: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reused preference keys of the widgets' own state. */
internal object WidgetKeys {
    val FILTER = stringPreferencesKey("filter")
    val ACTIONS = stringPreferencesKey("actions")
    val LABELS = stringPreferencesKey("labels")
}
