package ai.antigem.data.homescreen

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import ai.antigem.data.agent.ChatCompletionMonitor
import ai.antigem.data.preferences.PinnedChats
import ai.antigem.data.voice.VoiceCallLauncher
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.Conversation
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
    val done: Boolean = false
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
 * Kept: the newest chats and every pinned chat. Subagent, read-only and archived chats are left out.
 */
object HomeSnapshot {
    private const val TAG = "HomeSnapshot"
    private const val NEWEST = 30

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow<HomeData?>(null)
    /** null until [load] read the saved list. */
    val state: StateFlow<HomeData?> = _state.asStateFlow()

    private val _widgetState = MutableStateFlow<HomeData?>(null)
    /**
     * What the home-screen widgets show: [state], handed over by [HomeWidgets] when it redraws them. While the app is
     * on screen the widgets can't be seen, so they don't get changes then (each widget redraw is built on the main
     * thread and made the chat stutter while a reply streamed); as soon as the app leaves the screen they get the
     * newest at once. The app's own screens use [state].
     */
    val widgetState: StateFlow<HomeData?> = _widgetState.asStateFlow()

    /** The widgets take the newest data (see [widgetState]). */
    fun publishToWidgets() {
        _state.value?.let { _widgetState.value = it }
    }

    /** Chats that finished out of sight and have not been opened since. */
    private val done: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** A change outside the chat list (a finished chat, one opened or deleted): build again. */
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
                if (c.done) done += c.id
            }
            _state.value = data
            // a widget drawn before the app ran (its own process start) shows the saved list at once
            if (_widgetState.value == null) _widgetState.value = data
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

        // the chat list (the sidebar's: Jarvis' own chats only when the user lists them)
        scope.launch {
            val listInputs = combine(
                vm.conversations,
                PinnedChats.ids,
                vm.systemConnectionState.map { it.isHubOnline }.distinctUntilChanged()
            ) { convs, pinned, hubOnline -> Triple(convs, pinned, hubOnline) }
            combine(listInputs, vm.enabledAgents, vm.newChatAgent, bump) { l, enabled, def, _ ->
                Inputs(l.first, l.second, l.third, enabled, def)
            }
                .debounce(700)
                .collect { runCatching { build(app, it) }.onFailure { e -> Log.w(TAG, "build: ${e.message}", e) } }
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
        val defaultAgent: AgentKind
    )

    private fun build(app: Application, inp: Inputs) {
        val old = _state.value ?: HomeData()
        val usable = inp.conversations.filter { it.parentConversationId == null && !it.readOnly && !it.archived }
        // an agent whose list is empty right now (server still starting, hub offline): keep its chats from before
        val listed = usable.map { it.agent }.toSet()
        val kept = old.chats.filter { c -> c.agentKind in inp.enabled && c.agentKind !in listed && c.id !in deleted }
            .map { it.copy(running = false) }
        val newest = usable.sortedByDescending { it.updatedAt }
        val wanted = (newest.filter { it.id in inp.pinned } + newest.take(NEWEST)).distinctBy { it.id }
        val fresh = wanted.map { c ->
            val busy = (c.isRunning && (inp.hubOnline || c.agent != AgentKind.AGY)) || (c.agent == AgentKind.AGY && inp.hubOnline && c.notFullyIdle)
            HomeChat(
                id = c.id,
                agent = c.agent.name,
                title = c.title.takeIf { it.isNotBlank() && it != "New Chat" && it != "Conversation" } ?: "New chat",
                project = projectName(c),
                updatedAt = c.updatedAt,
                running = busy,
                pinned = c.id in inp.pinned,
                done = !busy && c.id in done
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
        _state.value = data
        save(app, data)
        HomeWidgets.onChatsChanged(app)
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
}
