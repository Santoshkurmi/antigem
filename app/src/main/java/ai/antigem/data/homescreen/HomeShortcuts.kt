package ai.antigem.data.homescreen

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ai.antigem.domain.model.AgentKind
import ai.antigem.i18n.L

/**
 * Home-screen shortcuts: one chat, an action (a page, a new chat, …), a script or a web page, each asked from the
 * launcher (Android shows its own "Add to home screen?" box). Also the app icon's long-press menu: the newest chats,
 * after the Voice call (and Jarvis) entries. A chat's shortcut has the same id in both places, so renaming the chat
 * renames both; deleting it greys the pinned one out.
 */
object HomeShortcuts {
    private const val TAG = "HomeShortcuts"
    private const val CHAT_PREFIX = "chat:"
    private const val RECENT_COUNT = 3
    private const val SCRIPT_PREFIX = "script:"
    /** Scripts the user may put in the app icon's menu (launchers show about 4 entries in all). */
    const val MENU_SCRIPTS_MAX = 4
    /** After Voice call and Jarvis (unranked), before the newest chats (10+). */
    private const val SCRIPT_RANK = 5

    fun chatShortcutId(id: String) = CHAT_PREFIX + id

    fun canPin(context: Context): Boolean = runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    fun pinChat(context: Context, id: String, agent: AgentKind, title: String): Boolean =
        pin(context, chatInfo(context, id, agent, title))

    fun pinAction(context: Context, action: HomeAction): Boolean =
        pin(
            context,
            ShortcutInfoCompat.Builder(context, "home:${action.id}")
                .setShortLabel(action.label)
                .setLongLabel(action.description.take(40))
                .setIcon(HomeIcons.action(context, action))
                .setIntent(HomeLinks.intent(context, action.target))
                .build()
        )

    /**
     * One script's shortcut. Its icon is ~/.shortcuts/icons/<name>.png when there is one (as in Termux:Widget), else
     * a terminal (or a task) glyph.
     */
    /** A script's shortcut id: the same for its home-screen icon and its entry in the app icon's menu. */
    fun scriptShortcutId(script: HomeScripts.Script): String =
        SCRIPT_PREFIX + (if (script.chroot) "chroot:" else "") + script.path

    suspend fun scriptInfo(context: Context, script: HomeScripts.Script, rank: Int? = null): ShortcutInfoCompat {
        val custom = HomeScripts.icon(context, script)
        val icon = custom?.let { IconCompat.createWithBitmap(it) } ?: HomeIcons.glyphIcon(
            context,
            if (script.background) 0xFF0F766E.toInt() else 0xFF334155.toInt(),
            if (script.background) ai.antigem.R.drawable.ic_home_task else ai.antigem.R.drawable.ic_home_terminal
        )
        // the old id for AntiGem's own scripts: shortcuts pinned before keep theirs
        return ShortcutInfoCompat.Builder(context, scriptShortcutId(script))
            .setShortLabel(script.name.substringBeforeLast('.').ifBlank { script.name }.take(24))
            .setLongLabel(script.label.take(40))
            .setIcon(icon)
            .setIntent(HomeLinks.intent(context, HomeLinks.Target.Script(script.path, script.background, script.chroot)))
            .apply { if (rank != null) setRank(rank) }
            .build()
    }

    /** Asks the launcher to place [script]'s shortcut. */
    suspend fun pinScript(context: Context, script: HomeScripts.Script): Boolean {
        val info = scriptInfo(context, script)
        return withContext(Dispatchers.Main) { pin(context, info) }
    }

    fun pinWeb(context: Context, title: String, url: String): Boolean =
        pin(
            context,
            ShortcutInfoCompat.Builder(context, "web:${url.hashCode()}")
                .setShortLabel(title.ifBlank { url }.take(24))
                .setLongLabel(title.ifBlank { url }.take(40))
                .setIcon(HomeIcons.glyphIcon(context, 0xFF0EA5E9.toInt(), ai.antigem.R.drawable.ic_home_browser))
                .setIntent(HomeLinks.intent(context, HomeLinks.Target.Web(url)))
                .build()
        )

    private fun pin(context: Context, info: ShortcutInfoCompat): Boolean {
        if (!canPin(context)) {
            Toast.makeText(context, L.homescreen.homeShortcuts.thisHomeScreenAppCantAdd, Toast.LENGTH_LONG).show()
            return false
        }
        // Android 8+: Android keeps the link and asks the launcher (it never sees the link itself)
        if (Build.VERSION.SDK_INT >= 26) {
            return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }
                .onFailure { Log.w(TAG, "pin: ${it.message}") }
                .getOrDefault(false)
        }
        return pinLegacy(context, info)
    }

    /**
     * Android 7 pins through the INSTALL_SHORTCUT broadcast, with the shortcut's signed link in it. androidx sends it
     * to every app listening for it, so any app could take the link; here it goes only to the launchers that may have
     * it ([HomeLaunchers]: the default home app, or one that came with the phone).
     */
    private fun pinLegacy(context: Context, info: ShortcutInfoCompat): Boolean = runCatching {
        val action = "com.android.launcher.action.INSTALL_SHORTCUT"
        val targets = context.packageManager.queryBroadcastReceivers(Intent(action), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { HomeLaunchers.isTrusted(context, it) && it != context.packageName }
        if (targets.isEmpty()) {
            Toast.makeText(context, L.homescreen.homeShortcuts.thisHomeScreenAppCantAdd, Toast.LENGTH_LONG).show()
            return@runCatching false
        }
        // the old-style extras (link, name, icon) launchers of Android 7 read
        val base = ShortcutManagerCompat.createShortcutResultIntent(context, info).setAction(action)
        targets.forEach { pkg -> context.sendBroadcast(Intent(base).setPackage(pkg)) }
        true
    }.onFailure { Log.w(TAG, "pin (Android 7): ${it.message}") }.getOrDefault(false)

    private fun chatInfo(context: Context, id: String, agent: AgentKind, title: String, rank: Int? = null): ShortcutInfoCompat {
        val name = title.trim().takeIf { it.isNotBlank() && it != "New Chat" } ?: "${HomeIcons.agentLabel(agent)} chat"
        return ShortcutInfoCompat.Builder(context, chatShortcutId(id))
            .setShortLabel(name.take(24))
            .setLongLabel(name.take(40))
            .setIcon(HomeIcons.chat(context, agent, name))
            .setIntent(HomeLinks.intent(context, HomeLinks.Target.Chat(agent, id)))
            .apply { if (rank != null) setRank(rank) }
            .build()
    }

    /** A chat was deleted: out of the app icon's menu, and its home-screen shortcut greyed out. */
    fun onChatDeleted(context: Context, id: String) {
        HomeSnapshot.forget(id)
        val sid = chatShortcutId(id)
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(sid))
            ShortcutManagerCompat.disableShortcuts(context, listOf(sid), L.homescreen.homeShortcuts.thisChatWasDeleted)
        }.onFailure { Log.w(TAG, "delete: ${it.message}") }
        lastRecent = null
    }

    /** The launcher learns which chats are used (it may suggest them). */
    fun reportUsed(context: Context, id: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, chatShortcutId(id)) }
    }

    /** [script] is one of the scripts chosen for the app icon's menu. */
    fun isInMenu(script: HomeScripts.Script): Boolean = scriptShortcutId(script) in HomePrefs.menuScripts.value

    /**
     * Puts [script] in the app icon's menu or takes it out ([syncScripts] then updates the menu). False when
     * [MENU_SCRIPTS_MAX] scripts of this runtime are in it already.
     */
    fun setInMenu(context: Context, script: HomeScripts.Script, on: Boolean): Boolean {
        val id = scriptShortcutId(script)
        val now = HomePrefs.menuScripts.value
        if (on && id !in now) {
            val present = HomeScripts.list(context).map { scriptShortcutId(it) }.toSet()
            if (now.count { it in present } >= MENU_SCRIPTS_MAX) return false
        }
        HomePrefs.setMenuScripts(if (on) now + id else now - id)
        return true
    }

    /** The scripts in the app icon's menu now (id and label, in order): the same again changes nothing. */
    @Volatile private var lastScripts: List<Pair<String, String>>? = null

    /**
     * The chosen scripts of the phone's runtime into the app icon's menu, after Voice call and Jarvis and before the
     * newest chats, within Android's limit of entries (5 up to Android 9). Scripts deleted or renamed drop out. Called
     * while the app is on screen: Android limits how often an app in the background may change these.
     */
    suspend fun syncScripts(context: Context) {
        if (Build.VERSION.SDK_INT < 25) return
        val app = context.applicationContext
        val listing = HomeScripts.listing(app)
        // the Ubuntu chroot's list not read (or not readable) yet: leave the menu as it is
        if (!listing.read || listing.error != null) return
        val chosen = HomePrefs.menuScripts.value
        runCatching {
            val dynamic = ShortcutManagerCompat.getDynamicShortcuts(app)
            val others = dynamic.count { !it.id.startsWith(SCRIPT_PREFIX) && !it.id.startsWith(CHAT_PREFIX) }
            val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(app) - others).coerceIn(0, MENU_SCRIPTS_MAX)
            val keep = listing.scripts.filter { scriptShortcutId(it) in chosen }.take(room)
            val sig = keep.map { scriptShortcutId(it) to it.label }
            val present = dynamic.map { it.id }.filter { it.startsWith(SCRIPT_PREFIX) }
            if (sig == lastScripts && present.toSet() == sig.map { it.first }.toSet()) return
            val keepIds = sig.map { it.first }.toSet()
            val stale = present.filter { it !in keepIds }
            if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(app, stale)
            keep.forEachIndexed { i, s -> ShortcutManagerCompat.pushDynamicShortcut(app, scriptInfo(app, s, rank = SCRIPT_RANK + i)) }
            lastScripts = sig
            // the chats take what is left
            lastRecent = null
        }.onFailure { Log.w(TAG, "scripts in menu: ${it.message}") }
    }

    /** What the app icon's menu shows now: the same again changes nothing. */
    @Volatile private var lastRecent: List<Pair<String, String>>? = null

    /**
     * The app icon's menu gets the [RECENT_COUNT] newest chats ([recentOn]), and home-screen chat shortcuts take their
     * chat's current name. Called while the app is on screen.
     */
    fun sync(context: Context, chats: List<HomeChat>, recentOn: Boolean) {
        runCatching {
            // after Voice call, Jarvis and the chosen scripts, within Android's limit of entries
            val others = ShortcutManagerCompat.getDynamicShortcuts(context).count { !it.id.startsWith(CHAT_PREFIX) }
            val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - others).coerceIn(0, RECENT_COUNT)
            val recent = if (recentOn) chats.sortedByDescending { it.updatedAt }.take(room) else emptyList()
            val sig = recent.map { it.id to it.title }
            if (sig != lastRecent) {
                val keep = recent.map { chatShortcutId(it.id) }.toSet()
                val stale = ShortcutManagerCompat.getDynamicShortcuts(context)
                    .map { it.id }.filter { it.startsWith(CHAT_PREFIX) && it !in keep }
                if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
                recent.forEachIndexed { i, c ->
                    ShortcutManagerCompat.pushDynamicShortcut(context, chatInfo(context, c.id, c.agentKind, c.title, rank = 10 + i))
                }
                lastRecent = sig
            }
            // home-screen chat shortcuts follow renames
            val byId = chats.associateBy { chatShortcutId(it.id) }
            val renamed = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.id.startsWith(CHAT_PREFIX) && it.isEnabled }
                .mapNotNull { s ->
                    val c = byId[s.id] ?: return@mapNotNull null
                    if (s.shortLabel.toString() == c.title.take(24) || c.title == "New chat") null
                    else chatInfo(context, c.id, c.agentKind, c.title)
                }
            if (renamed.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, renamed)
        }.onFailure { Log.w(TAG, "sync: ${it.message}") }
    }
}
