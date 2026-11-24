package ai.antigem.data.homescreen

import android.content.Context
import android.content.Intent
import android.net.Uri
import ai.antigem.domain.model.AgentKind
import java.security.MessageDigest
import java.security.SecureRandom
import ai.antigem.i18n.L

/**
 * Everything the home screen can open in AntiGem (a chat, a new chat, a page, a web page, a script, a finished run, the
 * voice call, Jarvis, the floating chat, the mini apps or settings) as one link: `antigem://chat/claude/<id>`,
 * `antigem://page/terminal`, … Home-screen shortcuts, the app icon's long-press menu and the widgets all open
 * [HomeLaunchActivity] with such a link; it hands the link to the right screen.
 *
 * Running a script needs the app's own key in the intent ([EXTRA_KEY]): the launcher screen is exported (launchers of
 * Android 7 start shortcuts as themselves), and no other app may run the user's scripts through it.
 */
object HomeLinks {
    /** [HomeLaunchActivity]: open the link in the intent's data. */
    const val ACTION_OPEN = "ai.antigem.action.HOME_OPEN"
    /** MainActivity: show the link (forwarded by [HomeLaunchActivity]). */
    const val ACTION_SHOW = "ai.antigem.action.HOME_SHOW"
    const val EXTRA_KEY = "ai.antigem.extra.HOME_KEY"
    private const val SCHEME = "antigem"

    /** The app's pages a link can open (AppViewMode names). */
    enum class Page(val viewMode: String, private val labelText: () -> String) {
        CHAT("CHAT", { L.homescreen.homeLinks.chat }),
        TERMINAL("TERMINAL", { L.homescreen.homeLinks.terminal }),
        BROWSER("BROWSER", { L.homescreen.homeLinks.browser }),
        IDE("IDE", { L.homescreen.homeLinks.codeEditor }),
        FILES("FILES", { L.homescreen.homeLinks.files }),
        NOTES("NOTES", { L.homescreen.homeLinks.notes }),
        RUN("RUN", { L.homescreen.homeLinks.run });
        val label: String get() = labelText()
    }

    sealed interface Target {
        /** This exact chat. */
        data class Chat(val agent: AgentKind, val id: String) : Target
        /** The chat in the floating window (the app opens it instead when it is not running). */
        data class Reply(val agent: AgentKind, val id: String) : Target
        /** A new chat with [agent] (null: the new-chat default). */
        data class NewChat(val agent: AgentKind?) : Target
        data class OpenPage(val page: Page) : Target
        /** A web page in AntiGem's browser. */
        data class Web(val url: String) : Target
        /** A script of ~/.shortcuts: in a new terminal tab, or in the background (a Run with a notification at the end). */
        data class Script(val path: String, val background: Boolean) : Target
        /** One run on the Run page (the finished-script notification). */
        data class RunEntry(val id: String) : Target
        data object TerminalWindow : Target
        data object VoiceCall : Target
        data object Jarvis : Target
        data object FloatingChat : Target
        data object MiniApps : Target
        /** Settings; [section] a SettingsSection name (null: the main page). */
        data class Settings(val section: String? = null) : Target
    }

    fun uri(target: Target): Uri {
        val b = Uri.Builder().scheme(SCHEME)
        when (target) {
            is Target.Chat -> b.authority("chat").appendPath(target.agent.name.lowercase()).appendPath(target.id)
            is Target.Reply -> b.authority("reply").appendPath(target.agent.name.lowercase()).appendPath(target.id)
            is Target.NewChat -> b.authority("new").appendPath(target.agent?.name?.lowercase() ?: "default")
            is Target.OpenPage -> b.authority("page").appendPath(target.page.name.lowercase())
            is Target.Web -> b.authority("web").appendQueryParameter("u", target.url)
            is Target.Script -> b.authority("script").appendQueryParameter("p", target.path)
                .appendQueryParameter("bg", if (target.background) "1" else "0")
            is Target.RunEntry -> b.authority("run").appendPath(target.id)
            Target.TerminalWindow -> b.authority("terminal-window")
            Target.VoiceCall -> b.authority("voice")
            Target.Jarvis -> b.authority("jarvis")
            Target.FloatingChat -> b.authority("float")
            Target.MiniApps -> b.authority("miniapps")
            is Target.Settings -> {
                b.authority("settings")
                target.section?.let { b.appendPath(it.lowercase()) }
            }
        }
        return b.build()
    }

    fun parse(uri: Uri?): Target? {
        if (uri == null || uri.scheme != SCHEME) return null
        val seg = uri.pathSegments
        fun agent(s: String?): AgentKind? = AgentKind.entries.firstOrNull { it.name.equals(s, true) }
        return runCatching {
            when (uri.authority) {
                "chat" -> Target.Chat(agent(seg.getOrNull(0)) ?: return null, seg.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null)
                "reply" -> Target.Reply(agent(seg.getOrNull(0)) ?: return null, seg.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null)
                "new" -> Target.NewChat(agent(seg.getOrNull(0)))
                "page" -> Target.OpenPage(Page.entries.firstOrNull { it.name.equals(seg.getOrNull(0), true) } ?: return null)
                "web" -> Target.Web(uri.getQueryParameter("u")?.takeIf { it.isNotBlank() } ?: return null)
                "script" -> Target.Script(uri.getQueryParameter("p")?.takeIf { it.isNotBlank() } ?: return null, uri.getQueryParameter("bg") == "1")
                "run" -> Target.RunEntry(seg.getOrNull(0) ?: return null)
                "terminal-window" -> Target.TerminalWindow
                "voice" -> Target.VoiceCall
                "jarvis" -> Target.Jarvis
                "float" -> Target.FloatingChat
                "miniapps" -> Target.MiniApps
                "settings" -> Target.Settings(seg.getOrNull(0)?.uppercase())
                else -> null
            }
        }.getOrNull()
    }

    /**
     * The intent a shortcut or widget starts for [target]. Each link has its own data, so the widgets' list items and
     * the pending intents of different links never replace each other.
     */
    fun intent(context: Context, target: Target): Intent =
        Intent(context, HomeLaunchActivity::class.java)
            .setAction(ACTION_OPEN)
            .setData(uri(target))
            .putExtra(EXTRA_KEY, key(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The intent carries the app's own key (only the app's shortcuts and widgets have it). */
    fun isTrusted(context: Context, intent: Intent?): Boolean {
        val given = intent?.getStringExtra(EXTRA_KEY) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), key(context).toByteArray())
    }

    @Volatile private var cachedKey: String? = null

    @Synchronized
    private fun key(context: Context): String {
        cachedKey?.let { return it }
        val p = context.applicationContext.getSharedPreferences("home_links", Context.MODE_PRIVATE)
        val k = p.getString("key", null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { p.edit().putString("key", it).apply() }
        cachedKey = k
        return k
    }
}
