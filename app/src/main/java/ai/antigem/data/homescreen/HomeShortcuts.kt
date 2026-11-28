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
    suspend fun scriptInfo(context: Context, script: HomeScripts.Script): ShortcutInfoCompat {
        val custom = HomeScripts.icon(context, script)
        val icon = custom?.let { IconCompat.createWithBitmap(it) } ?: HomeIcons.glyphIcon(
            context,
            if (script.background) 0xFF0F766E.toInt() else 0xFF334155.toInt(),
            if (script.background) ai.antigem.R.drawable.ic_home_task else ai.antigem.R.drawable.ic_home_terminal
        )
        // the old id for AntiGem's own scripts: shortcuts pinned before keep theirs
        val id = "script:" + (if (script.chroot) "chroot:" else "") + script.path
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(script.name.substringBeforeLast('.').ifBlank { script.name }.take(24))
            .setLongLabel(script.label.take(40))
            .setIcon(icon)
            .setIntent(HomeLinks.intent(context, HomeLinks.Target.Script(script.path, script.background, script.chroot)))
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

    /** What the app icon's menu shows now: the same again changes nothing. */
    @Volatile private var lastRecent: List<Pair<String, String>>? = null

    /**
     * The app icon's menu gets the [RECENT_COUNT] newest chats ([recentOn]), and home-screen chat shortcuts take their
     * chat's current name. Called while the app is on screen.
     */
    fun sync(context: Context, chats: List<HomeChat>, recentOn: Boolean) {
        runCatching {
            val recent = if (recentOn) chats.sortedByDescending { it.updatedAt }.take(RECENT_COUNT) else emptyList()
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
