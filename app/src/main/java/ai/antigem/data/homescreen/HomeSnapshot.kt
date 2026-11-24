package ai.antigem.data.homescreen

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import ai.antigem.data.agent.ChatCompletionMonitor
import ai.antigem.data.preferences.PinnedChats
import ai.antigem.data.voice.VoiceCallLauncher
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.Conversation
import ai.antigem.domain.model.MessageRole
import ai.antigem.ui.chat.ChatViewModel
import ai.antigem.ui.homescreen.HomeWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import ai.antigem.i18n.L

/** One chat as the home screen shows it. */
@Serializable
data class HomeChat(
    val id: String,
    val agent: String,
    val title: String,
    /** The project folder's name ("" when none). */
    val project: String = "",
    val updatedAt: Long = 0L,
    /** The agent is working on it right now. */
    val running: Boolean = false,
    val pinned: Boolean = false,
    /** It finished while you were not looking at it, and you have not opened it since. */
    val done: Boolean = false,
    /** The last reply the app saw in it (plain text, shortened); "" when none yet. */
    val reply: String = "",
    val replyAt: Long = 0L
) {
    val agentKind: AgentKind get() = AgentKind.entries.firstOrNull { it.name == agent } ?: AgentKind.AGY
}

@Serializable
data class HomeData(
    val chats: List<HomeChat> = emptyList(),
    /** The enabled agents (AgentKind names). */
    val agents: List<String> = emptyList(),
    /** The new-chat default (AgentKind name). */
    val defaultAgent: String? = null,
    val savedAt: Long = 0L,
    /** The app has written this at least once (false: the widgets ask to open AntiGem once). */
    val loaded: Boolean = false
) {
    val enabledAgents: List<AgentKind> get() = agents.mapNotNull { a -> AgentKind.entries.firstOrNull { it.name == a } }
}

/**
 * The chats the home screen shows, saved by the app whenever its chat list changes: the widgets and the app icon's
 * menu only read this, so they work while the server is down or the app is not running (then they show the last
 * saved list; it updates live while AntiGem runs, in the background too).
 *
 * Kept: the newest chats, every pinned chat and the chats a Chat widget shows. Subagent, read-only and archived chats
 * are left out. A chat's last reply is the one the app saw on screen.
 */
object HomeSnapshot {
    private const val TAG = "HomeSnapshot"
    private const val NEWEST = 30
    private const val REPLY_CHARS = 700

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow<HomeData?>(null)
    /** null until [load] read the saved list. */
    val state: StateFlow<HomeData?> = _state.asStateFlow()

    /** Last replies seen per chat: text and time. */
    private val replies = ConcurrentHashMap<String, Pair<String, Long>>()
    /** Chats that finished out of sight and have not been opened since. */
    private val done: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** A change outside the chat list (a reply, a finished chat): build again. */
    private val bump = MutableStateFlow(0)
    @Volatile private var started = false

    private fun file(context: Context) = File(context.applicationContext.filesDir, "home_screen/snapshot.json")

    /** The saved list (read from disk the first time). */
    fun load(context: Context): HomeData {
        _state.value?.let { return it }
        synchronized(this) {
            _state.value?.let { return it }
            val data = runCatching {
                val f = file(context)
                if (f.isFile) json.decodeFromString(HomeData.serializer(), f.readText()) else HomeData()
            }.getOrElse { HomeData() }
            data.chats.forEach { c ->
                if (c.reply.isNotBlank()) replies.putIfAbsent(c.id, c.reply to c.replyAt)
                if (c.done) done += c.id
            }
            _state.value = data
            return data
        }
    }

    fun chat(context: Context, id: String): HomeChat? = load(context).chats.firstOrNull { it.id == id }

    /** The chat was opened from the home screen: it is no longer "done". */
    fun markSeen(id: String) {
        if (done.remove(id)) bump.value++
    }

    /** Chats deleted in this run of the app: never kept from the saved list (even if their agent's list is empty now). */
    private val deleted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A chat was deleted: it leaves the home screen. */
    fun forget(id: String) {
        deleted += id
        replies.remove(id)
        done.remove(id)
        bump.value++
    }

    /** Starts keeping the list up to date (MainActivity, once per process). */
    @OptIn(FlowPreview::class)
    fun start(app: Application, vm: ChatViewModel) {
        synchronized(this) {
            if (started) return
            started = true
        }
        load(app)
        PinnedChats.init(app)
        HomePrefs.init(app)
        HomeWidgets.init(app)

        // the chat list (the sidebar's: Jarvis' own chats only when the user lists them)
        scope.launch {
            val listInputs = combine(
                vm.conversations,
                PinnedChats.ids,
                vm.systemConnectionState.map { it.isHubOnline }.distinctUntilChanged()
            ) { convs, pinned, hubOnline -> Triple(convs, pinned, hubOnline) }
            combine(listInputs, vm.enabledAgents, vm.newChatAgent, bump, HomeWidgets.watched) { l, enabled, def, _, watched ->
                Inputs(l.first, l.second, l.third, enabled, def, watched)
            }
                .debounce(700)
                .collect { runCatching { build(app, it) }.onFailure { e -> Log.w(TAG, "build: ${e.message}", e) } }
        }
        // the reply of the chat on screen
        scope.launch {
            combine(vm.currentConversation.map { it?.id }.distinctUntilChanged(), vm.messages) { id, msgs -> id to lastReply(msgs) }
                .debounce(1500)
                .collect { (id, reply) ->
                    if (id == null || reply == null) return@collect
                    if (replies[id]?.first != reply.first) {
                        replies[id] = reply
                        bump.value++
                    }
                }
        }
        // a chat that finished while it was not on screen
        scope.launch {
            ChatCompletionMonitor.events.collect { c ->
                val looking = VoiceCallLauncher.appVisible.value && vm.currentConversation.value?.id == c.id
                if (!looking && done.add(c.id)) bump.value++
            }
        }
        // opening a chat in the app: seen
        scope.launch {
            combine(vm.currentConversation.map { it?.id }.distinctUntilChanged(), VoiceCallLauncher.appVisible) { id, visible -> id to visible }
                .collect { (id, visible) -> if (id != null && visible) markSeen(id) }
        }
        // the theme changed: the widgets take the new colors
        scope.launch {
            combine(
                snapshotFlow { Triple(ai.antigem.theme.ThemeManager.lightId, ai.antigem.theme.ThemeManager.darkId, ai.antigem.theme.ThemeManager.customThemes) },
                vm.themeMode
            ) { a, b -> a to b }
                .distinctUntilChanged()
                .drop(1)
                .debounce(600)
                .collect { HomeWidgets.refreshAll(app) }
        }
        // the app icon's menu and the names of chat shortcuts: only while the app is on screen (Android limits how
        // often an app in the background may change its shortcuts)
        scope.launch {
            combine(_state.filterNotNull(), VoiceCallLauncher.appVisible, HomePrefs.recentInMenu) { s, visible, on -> Triple(s, visible, on) }
                .filter { it.second && it.first.loaded }
                .debounce(1200)
                .collect { (s, _, on) -> HomeShortcuts.sync(app, s.chats, on) }
        }
    }

    private class Inputs(
        val conversations: List<Conversation>,
        val pinned: Set<String>,
        val hubOnline: Boolean,
        val enabled: Set<AgentKind>,
        val defaultAgent: AgentKind,
        val watched: Set<String>
    )

    private fun build(app: Application, inp: Inputs) {
        val old = _state.value ?: HomeData()
        val usable = inp.conversations.filter { it.parentConversationId == null && !it.readOnly && !it.archived }
        // an agent whose list is empty right now (server still starting, hub offline): keep its chats from before
        val listed = usable.map { it.agent }.toSet()
        val kept = old.chats.filter { c -> c.agentKind in inp.enabled && c.agentKind !in listed && c.id !in deleted }
            .map { it.copy(running = false) }
        val newest = usable.sortedByDescending { it.updatedAt }
        val wanted = (newest.filter { it.id in inp.pinned || it.id in inp.watched } + newest.take(NEWEST)).distinctBy { it.id }
        val oldById = old.chats.associateBy { it.id }
        val fresh = wanted.map { c ->
            val busy = (c.isRunning && (inp.hubOnline || c.agent != AgentKind.AGY)) || (c.agent == AgentKind.AGY && inp.hubOnline && c.notFullyIdle)
            val reply = replies[c.id] ?: oldById[c.id]?.let { it.reply to it.replyAt }
            HomeChat(
                id = c.id,
                agent = c.agent.name,
                title = c.title.takeIf { it.isNotBlank() && it != "New Chat" && it != "Conversation" } ?: "New chat",
                project = projectName(c),
                updatedAt = c.updatedAt,
                running = busy,
                pinned = c.id in inp.pinned,
                done = !busy && c.id in done,
                reply = reply?.first.orEmpty(),
                replyAt = reply?.second ?: 0L
            )
        }
        val chats = (fresh + kept).distinctBy { it.id }.sortedByDescending { it.updatedAt }
        val data = HomeData(
            chats = chats,
            agents = AgentKind.entries.filter { it in inp.enabled }.map { it.name },
            defaultAgent = inp.defaultAgent.name,
            savedAt = System.currentTimeMillis(),
            loaded = true
        )
        if (data.copy(savedAt = 0L) == old.copy(savedAt = 0L)) return
        // the list widget redraws only for what it shows (not for every new word of a reply)
        val listChanged = data.copy(savedAt = 0L, chats = data.chats.map { it.copy(reply = "", replyAt = 0L) }) !=
            old.copy(savedAt = 0L, chats = old.chats.map { it.copy(reply = "", replyAt = 0L) })
        _state.value = data
        save(app, data)
        HomeWidgets.onChatsChanged(app, listChanged)
    }

    private fun save(context: Context, data: HomeData) {
        runCatching {
            val f = file(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(HomeData.serializer(), data))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }.onFailure { Log.w(TAG, "save: ${it.message}") }
    }

    private fun projectName(c: Conversation): String {
        if (c.isCloud) return L.homescreen.homeSnapshot.cloud
        val uri = c.workspaceUri.trim().trimEnd('/')
        if (uri.isEmpty()) return ""
        val path = runCatching { URLDecoder.decode(uri.removePrefix("file://"), "UTF-8") }.getOrDefault(uri)
        return path.substringAfterLast('/')
    }

    /** The newest reply with text in [msgs], as plain text. */
    private fun lastReply(msgs: List<ChatMessage>): Pair<String, Long>? {
        val m = msgs.lastOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() } ?: return null
        val text = plain(m.content)
        if (text.isBlank()) return null
        return text to m.createdAt
    }

    /** Markdown made readable as plain text, shortened. */
    fun plain(md: String): String {
        var s = md
        s = s.replace(Regex("```[\\s\\S]*?(```|$)"), " [code] ")
        s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")
        s = s.replace(Regex("[*_~`]{1,3}"), "")
        s = s.replace(Regex("<[^>]{1,40}>"), "")
        s = s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        return if (s.length > REPLY_CHARS) s.take(REPLY_CHARS).trimEnd() + "…" else s
    }
}
