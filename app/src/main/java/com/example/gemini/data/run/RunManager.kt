package com.example.gemini.data.run

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalPtySession
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class RunStatus { RUNNING, SUCCEEDED, FAILED, STOPPED }

/** One command started from the Run panel or the IDE's Run button. Restarting keeps the id, with a new session. */
data class RunEntry(
    val id: String,
    val name: String,
    val command: String,
    val folder: String,
    val session: LocalPtySession,
    val status: RunStatus = RunStatus.RUNNING,
    val exitCode: Int? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** "panel" or "ide": the IDE closes its own runs when its runner sheet closes. */
    val origin: String = "panel",
    /** Remote runs: the tmux session on the server the command runs in. */
    val tmuxName: String? = null
)

/**
 * Owns every run, independent of any screen: the Run panel, its minimized bubble and the IDE only show these. A run
 * keeps going while the panel is hidden; the app's foreground service (TermuxService) keeps the process alive.
 *
 * Local runs execute `bash -c` in the command's folder and end with the command, so the session's exit status is
 * the command's. Remote runs (server mode / a PC that allows a shell) type the command into their own tmux session
 * over SSH, print a marker with the exit code and exit.
 */
object RunManager {
    private const val TAG = "RunManager"
    private val EXIT_MARKER = Regex("""\[antigem-run exit (\d+)\]""")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _runs = MutableStateFlow<List<RunEntry>>(emptyList())
    val runs: StateFlow<List<RunEntry>> = _runs.asStateFlow()

    private val watchers = ConcurrentHashMap<String, Job>()
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()

    // A build keeps the CPU awake while it runs (the Run screen also keeps the screen on while it is in front)
    private var appContext: Context? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    init {
        scope.launch {
            _runs.collect { list -> syncWakeLock(list.any { it.status == RunStatus.RUNNING }) }
        }
    }

    private fun syncWakeLock(running: Boolean) {
        if (running && wakeLock?.isHeld != true) {
            val ctx = appContext ?: return
            runCatching {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AntiGem:run").apply {
                    setReferenceCounted(false)
                    acquire(3 * 60 * 60 * 1000L)
                }
            }.onFailure { Log.w(TAG, "wake lock: ${it.message}") }
        } else if (!running) {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            wakeLock = null
        }
    }

    fun find(id: String): RunEntry? = _runs.value.find { it.id == id }

    fun start(context: Context, name: String, command: String, folder: String, origin: String = "panel"): RunEntry {
        appContext = context.applicationContext
        val id = UUID.randomUUID().toString()
        val (session, tmux) = createSession(context.applicationContext, id, name, command, folder)
        val entry = RunEntry(id = id, name = name, command = command, folder = folder, session = session, origin = origin, tmuxName = tmux)
        _runs.update { it + entry }
        watch(entry)
        return entry
    }

    /** Runs the same command again in the same tab (stops the old process first if it still runs). */
    fun restart(context: Context, id: String) {
        val old = find(id) ?: return
        watchers.remove(id)?.cancel()
        if (old.status == RunStatus.RUNNING) kill(old)
        stopRequested.remove(id)
        val (session, tmux) = createSession(context.applicationContext, id, old.name, old.command, old.folder)
        val fresh = old.copy(
            session = session, tmuxName = tmux, status = RunStatus.RUNNING, exitCode = null,
            startedAt = System.currentTimeMillis(), finishedAt = null
        )
        _runs.update { list -> list.map { if (it.id == id) fresh else it } }
        watch(fresh)
    }

    /** Ctrl+C first (Flutter, Metro and Gradle stop cleanly on it); whatever still runs after 2.5 s is killed. */
    fun stop(id: String) {
        val entry = find(id) ?: return
        if (entry.status != RunStatus.RUNNING) return
        stopRequested.add(id)
        runCatching { entry.session.write("\u0003") }
        scope.launch {
            delay(2500)
            val now = find(id)
            if (now != null && now.session === entry.session && now.status == RunStatus.RUNNING) kill(now)
        }
    }

    /** Closes the tab: stops the process if it still runs and forgets the run. */
    fun remove(id: String) {
        val entry = find(id) ?: return
        watchers.remove(id)?.cancel()
        if (entry.status == RunStatus.RUNNING) kill(entry, markStopped = false)
        stopRequested.remove(id)
        _runs.update { list -> list.filterNot { it.id == id } }
    }

    /** Finished runs only (the running ones stay). */
    fun clearFinished() {
        _runs.value.filter { it.status != RunStatus.RUNNING }.forEach { remove(it.id) }
    }

    /** The last [maxLines] lines the run printed, for handing a failure to the chat. Call on the main thread. */
    fun recentOutput(id: String, maxLines: Int = 80): String {
        val entry = find(id) ?: return ""
        val text = runCatching { entry.session.terminalSession.emulator?.screen?.transcriptTextWithoutJoinedLines }.getOrNull().orEmpty()
        return text.lines().map { it.trimEnd() }.dropLastWhile { it.isBlank() }.takeLast(maxLines).joinToString("\n")
    }

    private fun watch(entry: RunEntry) {
        watchers[entry.id] = scope.launch {
            if (entry.tmuxName != null) {
                // The SSH side may not report the tmux session ending: also look for the exit marker on screen
                while (!entry.session.isExited.value) {
                    val code = markerOnScreen(entry.session)
                    if (code != null) {
                        finish(entry, code)
                        runCatching { entry.session.close() }
                        com.example.gemini.data.remote.RemoteServerManager.killTmuxSession(entry.tmuxName)
                        return@launch
                    }
                    delay(1500)
                }
                finish(entry, markerOnScreen(entry.session))
                com.example.gemini.data.remote.RemoteServerManager.killTmuxSession(entry.tmuxName)
            } else {
                entry.session.isExited.first { it }
                val code = runCatching { entry.session.terminalSession.let { if (!it.isRunning) it.exitStatus else null } }.getOrNull()
                finish(entry, code)
            }
        }
    }

    private fun finish(entry: RunEntry, code: Int?) {
        val status = when {
            entry.id in stopRequested -> RunStatus.STOPPED
            code == 0 -> RunStatus.SUCCEEDED
            else -> RunStatus.FAILED
        }
        _runs.update { list ->
            list.map {
                if (it.id == entry.id && it.session === entry.session && it.status == RunStatus.RUNNING) {
                    it.copy(status = status, exitCode = code, finishedAt = System.currentTimeMillis())
                } else it
            }
        }
    }

    private fun markerOnScreen(session: LocalPtySession): Int? = runCatching {
        val emulator = session.terminalSession.emulator ?: return null
        val screen = emulator.screen
        val top = -minOf(20, screen.activeTranscriptRows)
        val text = screen.getSelectedText(0, top, emulator.mColumns, emulator.mRows - 1)
        EXIT_MARKER.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }.getOrNull()

    private fun kill(entry: RunEntry, markStopped: Boolean = true) {
        if (entry.tmuxName != null) {
            runCatching { entry.session.close() }
            com.example.gemini.data.remote.RemoteServerManager.killTmuxSession(entry.tmuxName)
        } else {
            killTree(entry.session)
        }
        if (markStopped) {
            stopRequested.add(entry.id)
            finish(entry, null)
        }
    }

    /**
     * LocalPtySession.close() only kills the shell, so anything the command started (Gradle, Metro, servers) would keep
     * running. The process tree is read from /proc before the shell dies, since its children are re-parented after.
     */
    fun killTree(session: LocalPtySession) {
        val rootPid = runCatching { session.terminalSession.pid }.getOrDefault(0)
        if (rootPid > 0) {
            val children = mutableMapOf<Int, MutableList<Int>>()
            java.io.File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                val stat = runCatching { java.io.File(dir, "stat").readText() }.getOrNull() ?: return@forEach
                val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return@forEach
                children.getOrPut(ppid) { mutableListOf() }.add(pid)
            }
            val queue = ArrayDeque(listOf(rootPid))
            while (queue.isNotEmpty()) {
                children[queue.removeFirst()]?.forEach { pid ->
                    runCatching { android.os.Process.sendSignal(pid, 9) }
                    queue.addLast(pid)
                }
            }
        }
        session.close()
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun createSession(context: Context, id: String, name: String, command: String, folder: String): Pair<LocalPtySession, String?> {
        val dir = folder.takeIf { it.isNotBlank() }
        val header = "printf '\\033[1;32m▶ %s\\033[0m\\n' ${quote(command)}"
        if (AuthPreferences.remoteShell) {
            val prefs = AuthPreferences(context)
            val (host, port, user, pass) = runBlocking(Dispatchers.IO) {
                listOf(
                    prefs.termuxSshHost.first(), prefs.termuxSshPort.first().toString(),
                    prefs.termuxSshUser.first(), prefs.termuxSshPass.first()
                )
            }
            val tmux = "antigem-run-" + id.take(8)
            val cd = if (dir != null) "cd ${quote(dir)} || exit 1; " else ""
            // Typed into the session's shell: the exit marker carries the code, then the shell (and the tmux session) ends
            val typed = "$cd$header; $command\n__rc=\$?; printf '\\n[antigem-run exit %d]\\n' \$__rc; exit \$__rc"
            Log.d(TAG, "remote run $name in $dir (tmux $tmux)")
            val session = LocalPtySession(
                id = "run_$id",
                initialTitle = name,
                context = context,
                isSsh = true,
                sshHost = host,
                sshPort = port.toIntOrNull() ?: 22,
                sshUser = user,
                sshPass = pass,
                tmuxSessionName = tmux,
                initialCommand = typed,
                showExitNotice = false
            )
            return session to tmux
        }
        val cd = if (dir != null) "cd ${quote(dir)} || exit 1\n" else ""
        Log.d(TAG, "run $name in $dir")
        val session = LocalPtySession(
            id = "run_$id",
            initialTitle = name,
            context = context,
            forceShell = "bash",
            initialWorkingDir = dir,
            initialCommand = "$cd$header\n$command",
            execShellAfterCommand = false,
            showExitNotice = false
        )
        return session to null
    }
}

/** The Run screen's state: whether it is in front, which run it shows, and text handed to the chat. */
object RunPanel {
    /** True while the Run screen is the one on display (MainActivity sets it). */
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Asks MainActivity to switch to the Run screen (chat header, IDE menu, the bubble). */
    private val _openRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _openRequests

    /** The run whose terminal the screen shows. */
    val selectedRunId = MutableStateFlow<String?>(null)

    /** A folder the screen should switch to when it opens (the IDE opens it on its project). */
    val requestedFolder = MutableStateFlow<String?>(null)

    /** Text for the chat's message box ("Send to chat"); the chat takes it and sets this back to null. */
    val chatHandoff = MutableStateFlow<String?>(null)

    /** Finished runs whose result the user has seen (the bubble stops showing them). */
    private val _seen = MutableStateFlow<Set<String>>(emptySet())
    val seen: StateFlow<Set<String>> = _seen.asStateFlow()

    fun show(runId: String? = null, folder: String? = null) {
        if (runId != null) selectedRunId.value = runId
        if (!folder.isNullOrBlank()) requestedFolder.value = folder
        _openRequests.tryEmit(Unit)
    }

    fun setVisible(visible: Boolean) {
        _visible.value = visible
        markSeen()
    }

    fun markSeen() {
        _seen.value = _seen.value + RunManager.runs.value.filter { it.status != RunStatus.RUNNING }.map(::key)
    }

    /** One run attempt (a restart is a new attempt with a new result). */
    fun key(run: RunEntry) = run.id + "@" + run.startedAt
}
