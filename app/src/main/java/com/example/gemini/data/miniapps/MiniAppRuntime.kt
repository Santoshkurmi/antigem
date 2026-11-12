package com.example.gemini.data.miniapps

import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** What the runtime needs from an open page (a WebView). */
interface MiniAppPageHandle {
    val pageId: String
    val appId: String
    /** On screen right now (its activity resumed); background-task pages never are. */
    val isForeground: Boolean
    fun emit(event: String, data: Any?)
    /** The app's files changed on disk: reload, or just swap stylesheets when only CSS changed. */
    fun onFilesChanged(cssOnly: Boolean, manifestChanged: Boolean)
    /** The page's path in the app ("index.html", "settings.html"). */
    val path: String get() = ""
    /** A hidden page running a background task (no screen). */
    val isBackgroundTask: Boolean get() = false
    /** Developer MCP (development apps only): evaluates [js] in the page; [cb] gets the JSON-encoded result, null when gone. */
    fun devEval(js: String, cb: (String?) -> Unit) = cb(null)
    /** Developer MCP (development apps only): what the page's window shows right now, or null. */
    suspend fun capture(): android.graphics.Bitmap? = null
}

/** Per-app shared state (stores, open pages, file watcher), alive while at least one page of the app is open. */
object MiniAppRuntime {
    class AppState(val context: Context, val appId: String) {
        val pages = CopyOnWriteArrayList<MiniAppPageHandle>()
        val kv by lazy { MiniAppKv(File(MiniApps.dataDir(context, appId), "kv.db")) }
        val idb by lazy { MiniAppIdb(File(MiniApps.dataDir(context, appId), "idb.db")) }
        var watcher: MiniAppWatcher? = null
    }

    private val states = ConcurrentHashMap<String, AppState>()

    fun state(context: Context, appId: String): AppState = states.getOrPut(appId) { AppState(context.applicationContext, appId) }

    /** The app's open pages (none when it isn't running), without creating its state. */
    fun openPages(appId: String): List<MiniAppPageHandle> = states[appId]?.pages?.toList().orEmpty()

    fun attach(context: Context, app: MiniApp, page: MiniAppPageHandle) {
        val s = state(context, app.id)
        s.pages += page
        // hot reload is for development apps; installed apps only change through a signed update
        if (s.watcher == null && !app.signed) s.watcher = MiniAppWatcher(app.folder) { cssOnly, manifest -> s.pages.forEach { it.onFilesChanged(cssOnly, manifest) } }.also { it.start() }
    }

    fun detach(page: MiniAppPageHandle) {
        val s = states[page.appId] ?: return
        s.pages -= page
        runCatching { s.idb.pageClosed(page.pageId) }
        if (s.pages.isEmpty()) { s.watcher?.stop(); s.watcher = null }
    }

    /** Sends an event to every open page of the app except [except]. */
    fun broadcast(appId: String, event: String, data: Any?, except: MiniAppPageHandle? = null) {
        states[appId]?.pages?.forEach { if (it !== except) it.emit(event, data) }
    }

    fun broadcastAll(event: String, data: Any?) = states.values.forEach { s -> s.pages.forEach { it.emit(event, data) } }

    // ------------------------------------------------------------------ notification events

    private val pendingEvents = ConcurrentHashMap<String, ArrayDeque<JSONObject>>()

    /**
     * A tap, button, reply or swipe-away on one of the app's notifications. It goes to the app if it is on screen;
     * else, for buttons that don't open the app ([preferBackground]), to its background script
     * (`AntiGem.background.on('notification', …)`); else it waits until the app is opened.
     */
    fun deliverNotificationEvent(context: Context, app: MiniApp, event: JSONObject, preferBackground: Boolean) {
        // on screen: every open page of the app gets it (each listens for what it handles)
        if (states[app.id]?.pages?.any { it.isForeground } == true) { broadcast(app.id, "notification", event); return }
        val action = event.optString("action")
        if (preferBackground && action != "dismiss" && app.backgroundScript != null && MiniAppPermissions.isGranted(context, app, "background")) {
            MiniAppScheduler.runNow(context, app.id, "notification", "notification", event.toString())
            return
        }
        queueEvent(app.id, "notification", event.put("queued", true))
    }

    /** An event for an app that isn't on screen yet: delivered (to all its pages) once one has loaded. */
    fun queueEvent(appId: String, name: String, data: JSONObject) {
        val q = pendingEvents.getOrPut(appId) { ArrayDeque() }
        synchronized(q) { q.addLast(JSONObject().put("ev", name).put("d", data)); while (q.size > 20) q.removeFirst() }
    }

    /** Events that waited for the app to open: [{ev, d}]. */
    fun takePendingEvents(appId: String): List<JSONObject> = pendingEvents[appId]?.let { q -> synchronized(q) { q.toList().also { q.clear() } } }.orEmpty()

    /** Sends an intent event to a mini app: now if it is on screen, else when it opens. */
    fun deliverIntent(appId: String, intent: JSONObject) {
        if (states[appId]?.pages?.any { it.isForeground } == true) broadcast(appId, "intent", intent) else queueEvent(appId, "intent", intent)
    }

    /** "Clear data": closes the databases first so the files can be deleted. */
    fun clearData(context: Context, app: MiniApp) {
        states[app.id]?.let { s ->
            runCatching { s.kv.close() }; runCatching { s.idb.close() }
            states.remove(app.id)
            if (s.pages.isNotEmpty()) {
                // pages stay open: give them fresh state with the same pages
                val fresh = state(context, app.id); fresh.pages.addAll(s.pages); fresh.watcher = s.watcher
            } else s.watcher?.stop()
        }
        // like Android's "Clear data": its reminders, background tasks and cookies go too
        runCatching { MiniAppScheduler.forgetApp(context, app.id) }
        runCatching { MiniAppNotify.forgetApp(context, app.id) }
        pendingEvents.remove(app.id)
        MiniAppNet.forgetApp(app.id)
        MiniApps.dataDir(context, app.id).deleteRecursively()
        MiniAppConsole.clear(app.id)
    }
}

/**
 * Hot reload: watches the app folder (recursively; FileObserver is per directory) and reports a change once
 * writes settle, so an agent writing five files causes one reload.
 */
class MiniAppWatcher(private val root: File, private val onChange: (cssOnly: Boolean, manifest: Boolean) -> Unit) {
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val main = Handler(Looper.getMainLooper())
    private val changed = java.util.Collections.synchronizedSet(HashSet<String>())
    private val fire = Runnable {
        val files = synchronized(changed) { changed.toList().also { changed.clear() } }
        if (files.isEmpty()) return@Runnable
        val manifest = files.any { it.endsWith("manifest.json") }
        onChange(!manifest && files.all { it.endsWith(".css") }, manifest)
    }

    private fun skip(name: String) = name.startsWith(".") || name == "node_modules" || name.endsWith("~") || name.endsWith(".swp") || name.endsWith(".tmp")

    fun start() = watchTree(root)

    private fun watchTree(dir: File) {
        if (!dir.isDirectory || skip(dir.name) && dir != root) return
        watch(dir)
        dir.listFiles()?.filter { it.isDirectory }?.forEach { watchTree(it) }
    }

    @Suppress("DEPRECATION")
    private fun watch(dir: File) {
        if (observers.containsKey(dir.path)) return
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.CREATE or FileObserver.MOVED_FROM
        val o = object : FileObserver(dir.path, mask) {
            override fun onEvent(event: Int, path: String?) {
                val name = path ?: return
                if (skip(name)) return
                val f = File(dir, name)
                if (event and FileObserver.CREATE != 0 && f.isDirectory) { watchTree(f); return }
                if (event and FileObserver.CREATE != 0) return // wait for CLOSE_WRITE
                changed += f.path
                main.removeCallbacks(fire)
                main.postDelayed(fire, 120)
            }
        }
        o.startWatching()
        observers[dir.path] = o
    }

    fun stop() {
        observers.values.forEach { runCatching { it.stopWatching() } }
        observers.clear()
        main.removeCallbacks(fire)
    }
}

/** Console messages of every app: a ring buffer for the native console panel, plus ~/.antigem/mini-apps/<id>/console.log for agents. */
object MiniAppConsole {
    data class Entry(val time: Long, val level: String, val message: String, val source: String)

    private const val MAX = 1000
    private const val MAX_FILE = 1_000_000L
    private val buffers = ConcurrentHashMap<String, ArrayDeque<Entry>>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "miniapp-console").apply { isDaemon = true } }
    /** Bumped on every change, so Compose can observe it. */
    val version = MutableStateFlow(0L)

    fun add(context: Context, appId: String, level: String, message: String, source: String) {
        val e = Entry(System.currentTimeMillis(), level, message, source)
        val buf = buffers.getOrPut(appId) { ArrayDeque() }
        synchronized(buf) { buf.addLast(e); while (buf.size > MAX) buf.removeFirst() }
        version.value = version.value + 1
        if (level == "error") Log.w("MiniApp/$appId", "$message ($source)")
        val dir = MiniApps.dataDir(context, appId)
        io.execute {
            runCatching {
                dir.mkdirs()
                val f = File(dir, "console.log")
                if (f.length() > MAX_FILE) f.renameTo(File(dir, "console.log.1"))
                val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(e.time))
                f.appendText("$time ${level.uppercase().padEnd(5)} $message${if (source.isNotBlank()) "  ($source)" else ""}\n")
            }
        }
    }

    fun entries(appId: String): List<Entry> = buffers[appId]?.let { synchronized(it) { it.toList() } }.orEmpty()

    fun clear(appId: String) { buffers.remove(appId); version.value = version.value + 1 }
}
