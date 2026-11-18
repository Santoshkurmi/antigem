package com.example.gemini.data.x11

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.example.gemini.BuildConfig
import com.example.gemini.data.local.LocalCommandResult
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.updater.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Settings → X11 Desktop: sets up Termux:X11 (the app that shows the desktop, its companion
 * termux package with the `termux-x11` command, and a desktop such as XFCE) and starts/stops it.
 *
 * It uses AntiGem's own Termux:X11 build (github.com/Santoshkurmi/termux-x11), package ai.antigem.x11, signed
 * with AntiGem's key. That app joins AntiGem's UID (sharedUserId ai.antigem) and process, so Android keeps the
 * desktop at full speed while it is on screen. Its companion package is antigem-termux-x11 from the same
 * release; the official termux-x11-nightly's loader only starts the official Termux:X11, so it is replaced when
 * found. The fork keeps its Java names (com.termux.x11.*): its intent actions below are those.
 */
object X11DesktopManager {
    private const val TAG = "X11Desktop"
    private const val PREFS = "x11_desktop"

    /** AntiGem's Termux:X11: this app's package name + ".x11". */
    const val X11_PACKAGE = "${BuildConfig.APPLICATION_ID}.x11"
    const val COMPANION_ANTIGEM = "antigem-termux-x11"
    const val COMPANION_OFFICIAL = "termux-x11-nightly"

    private const val FORK_REPO = "Santoshkurmi/termux-x11"
    const val FORK_RELEASES_PAGE = "https://github.com/$FORK_REPO/releases/latest"
    private const val FORK_APK_URL = "https://github.com/$FORK_REPO/releases/latest/download/antigem-termux-x11-sharedUid.apk"
    private const val FORK_RELEASE_API = "https://api.github.com/repos/$FORK_REPO/releases/latest"

    private const val DISPLAY = ":0"
    private const val LOG_FILE = "antigem-x11.log"
    private const val KEEP_CONFIGS = "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"

    /**
     * Brings every installed package up to date before new ones are installed. Termux packages don't state the versions
     * they need, so a new package next to older libraries can fail to run (dbus 1.16 couldn't start with the base
     * system's older libexpat, and the desktop with it).
     */
    private const val UPGRADE = "apt-get full-upgrade -y $KEEP_CONFIGS"

    /** How long Start waits for the X server to listen on display :0. */
    private const val START_TIMEOUT_MS = 20_000L

    /** After that, how long the desktop must keep running: when its session fails, termux-x11 stops within about a second. */
    private const val SETTLE_MS = 3_000L

    /** Lines of the X server's log that mean it stopped because something failed. */
    private val FAILURE_LINE = Regex(
        "exited, status=[1-9]|killed by signal|CANNOT LINK EXECUTABLE|Fatal server error|XKB_CONFIG_ROOT is not set|TMPDIR is not set"
    )

    /** Desktops offered in the picker: the x11-repo package to install, and the command that starts a session. */
    enum class Desktop(val label: String, val packages: String, val sessionBinary: String) {
        XFCE("XFCE", "xfce4", "xfce4-session"),
        LXQT("LXQt", "lxqt", "startlxqt");

        val startCommand: String get() = "dbus-launch --exit-with-session $sessionBinary"
    }

    enum class Browser(val label: String, val packageName: String, val binaryName: String) {
        CHROMIUM("Chromium", "chromium", "chromium"),
        FIREFOX("Firefox", "firefox", "firefox")
    }

    /** What is running right now, so the screen shows progress and the log in that step. */
    enum class Task { APP, COMPANION, DESKTOP, BROWSER, START, CHILD_LIMIT }

    data class AppInfo(
        val versionName: String?,
        /** Shares AntiGem's UID (and so its process): AntiGem's own build on the Shared UID build. */
        val sameProcess: Boolean,
        /** Signed with AntiGem's key (AntiGem's build); otherwise the official one. */
        val antigemBuild: Boolean
    )

    data class State(
        val ready: Boolean = false,
        val bootstrapInstalled: Boolean = false,
        /** Null when Termux:X11 is not installed. */
        val app: AppInfo? = null,
        /** The installed companion package (antigem-termux-x11 or termux-x11-nightly), or null. */
        val companion: String? = null,
        val dbusInstalled: Boolean = false,
        val installedDesktops: Set<Desktop> = emptySet(),
        val desktop: Desktop = Desktop.XFCE,
        val installedBrowsers: Set<Browser> = emptySet(),
        val browser: Browser = Browser.CHROMIUM,
        val running: Boolean = false,
        val task: Task? = null,
        val progress: String? = null,
        val log: List<String> = emptyList(),
        val logTask: Task? = null,
        val message: String? = null,
        val isError: Boolean = false
    ) {
        /** The companion package whose loader starts AntiGem's Termux:X11. */
        val neededCompanion: String get() = COMPANION_ANTIGEM

        /** Only AntiGem's Termux:X11, sharing AntiGem's UID, runs inside AntiGem. */
        val appOk: Boolean get() = app?.sameProcess == true
        val companionOk: Boolean get() = companion == neededCompanion
        val desktopOk: Boolean get() = desktop in installedDesktops && dbusInstalled
        val canStart: Boolean get() = appOk && companionOk && desktopOk
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private var job: Job? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val saved = prefs.getString("desktop", null)
        val savedBrowser = prefs.getString("browser", null)
        _state.update {
            it.copy(
                desktop = Desktop.entries.firstOrNull { d -> d.name == saved } ?: Desktop.XFCE,
                browser = Browser.entries.firstOrNull { b -> b.name == savedBrowser } ?: Browser.CHROMIUM
            )
        }
    }

    fun setDesktop(desktop: Desktop) {
        prefs.edit().putString("desktop", desktop.name).apply()
        _state.update { it.copy(desktop = desktop) }
    }

    fun setBrowser(browser: Browser) {
        prefs.edit().putString("browser", browser.name).apply()
        _state.update { it.copy(browser = browser) }
    }

    fun clearMessage() = _state.update { it.copy(message = null, isError = false) }

    private fun report(message: String, isError: Boolean = false) =
        _state.update { it.copy(message = message, isError = isError) }

    // ---------------- state ----------------

    /** Re-reads everything from the phone: cheap (package manager, a few files and /proc), safe to poll. */
    fun refresh() = scope.launch { refreshNow() }

    /** Set while [stop] stops the desktop, so that isn't reported as the desktop stopping by itself. */
    @Volatile
    private var stopRequested = false

    private fun refreshNow() {
        val ctx = appContext
        val prefix = LocalEnvironmentManager.getPrefixDir(ctx)
        val bin = LocalEnvironmentManager.getBinDir(ctx)
        val dpkgInfo = File(prefix, "var/lib/dpkg/info")
        val companion = listOf(COMPANION_ANTIGEM, COMPANION_OFFICIAL).firstOrNull { File(dpkgInfo, "$it.list").exists() }
            ?.takeIf { File(bin, "termux-x11").exists() }
        val running = xServerPids().isNotEmpty()
        val previous = _state.getAndUpdate {
            it.copy(
                ready = true,
                bootstrapInstalled = LocalEnvironmentManager.isInstalled(ctx),
                app = readAppInfo(ctx),
                companion = companion,
                dbusInstalled = File(bin, "dbus-launch").exists(),
                installedDesktops = Desktop.entries.filter { d -> File(bin, d.sessionBinary).exists() }.toSet(),
                installedBrowsers = Browser.entries.filter { b -> File(bin, b.binaryName).exists() }.toSet(),
                running = running
            )
        }
        // It stopped without the Stop button (crashed, logged out, closed in Termux:X11). A running Start reports its own.
        if (previous.running && !running && !stopRequested && job?.isActive != true) reportStopped()
    }

    /** The desktop stopped by itself: say so, and why when the end of the X server's log shows an error. */
    private fun reportStopped() {
        val tail = xLogTail()
        val failed = tail.any { FAILURE_LINE.containsMatchIn(it) }
        _state.update {
            it.copy(
                logTask = Task.START,
                log = if (tail.isEmpty()) emptyList() else listOf("End of $LOG_FILE:") + tail,
                message = if (failed) "The desktop stopped because of an error. ${reasonHint(tail)}" else "The desktop has stopped.",
                isError = failed
            )
        }
    }

    /** The last lines of the X server's log (it grows while a desktop runs, so only its end is read). */
    private fun xLogTail(maxLines: Int = 20): List<String> {
        val file = File(LocalEnvironmentManager.getTmpDir(appContext), LOG_FILE)
        val text = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val from = (raf.length() - 16 * 1024).coerceAtLeast(0)
                val bytes = ByteArray((raf.length() - from).toInt())
                raf.seek(from)
                raf.readFully(bytes)
                // starting mid-file: the first line is cut, drop it
                String(bytes, Charsets.UTF_8).lines().let { if (from > 0) it.drop(1) else it }
            }
        }.getOrDefault(emptyList())
        return text.map { it.trimEnd() }.filter { it.isNotEmpty() }.takeLast(maxLines)
    }

    /** What to do about the errors we know, from the end of the X server's log. */
    private fun reasonHint(tail: List<String>): String = when {
        tail.any { "CANNOT LINK EXECUTABLE" in it } ->
            "A program could not load a library, which usually means some packages are out of date. " +
                "Run \"pkg upgrade\" in the terminal, then start again."
        tail.any { "XKB_CONFIG_ROOT is not set" in it } ->
            "This Termux:X11 build can't find the keyboard files. Update the Termux:X11 app (step 1), then start again."
        tail.isEmpty() -> "Its log ($LOG_FILE) is empty."
        else -> "The reason is at the end of the log in step 4."
    }

    private fun readAppInfo(ctx: Context): AppInfo? {
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageInfo(X11_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return AppInfo(
            versionName = info.versionName,
            sameProcess = info.applicationInfo?.uid == Process.myUid(),
            antigemBuild = pm.checkSignatures(ctx.packageName, X11_PACKAGE) == PackageManager.SIGNATURE_MATCH
        )
    }

    /**
     * The X server's processes. `termux-x11` starts it with app_process --nice-name="termux-x11 ai.antigem.x11 …",
     * and it runs under AntiGem's UID (it is a child of the terminal), so /proc shows it to us.
     */
    private fun xServerPids(): List<Int> = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@mapNotNull null
        val argv0 = String(cmdline, Charsets.UTF_8).substringBefore('\u0000')
        pid.takeIf { argv0.startsWith("termux-x11 ") }
    }

    // ---------------- step 1: the Termux:X11 app ----------------

    /** Downloads AntiGem's Termux:X11 build and opens the system installer. */
    fun installApp() = runTask(Task.APP) {
        val url = FORK_APK_URL
        appendLog("Downloading AntiGem's Termux:X11 build…")
        appendLog(url)
        val dir = File(appContext.externalCacheDir ?: appContext.cacheDir, "x11").apply { mkdirs() }
        val apk = File(dir, "termux-x11.apk")
        download(url, apk) { pct -> _state.update { it.copy(progress = "Downloading Termux:X11… $pct") } }
        appendLog("✓ Downloaded ${apk.length() / 1024} KB")
        val opened = withContext(Dispatchers.Main) { AppUpdateManager(appContext).installApk(apk) }
        if (opened) {
            appendLog("Opened the installer. Confirm the install there.")
        } else {
            report("Allow AntiGem to install apps (the settings page that just opened), then tap Install again.", isError = true)
        }
    }

    /** Opens the system uninstall dialog for Termux:X11 (needed to switch between the official and AntiGem builds). */
    fun uninstallApp(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$X11_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { report("Could not open the uninstall dialog: ${it.message}", isError = true) }
    }

    // ---------------- step 2: the companion package (termux-x11 command) ----------------

    fun installCompanion() = runTask(Task.COMPANION) {
        val enableRepo = "apt-get update && apt-get install -y $KEEP_CONFIGS x11-repo && apt-get update"
        val deb = File(LocalEnvironmentManager.getTmpDir(appContext), "$COMPANION_ANTIGEM.deb")
        appendLog("Finding the latest $COMPANION_ANTIGEM package…")
        val url = latestForkDebUrl()
        appendLog(url)
        download(url, deb) { pct -> _state.update { it.copy(progress = "Downloading $COMPANION_ANTIGEM… $pct") } }
        appendLog("✓ Downloaded ${deb.length() / 1024} KB")
        // A path (not a name) makes apt install the file and pull its dependency (xkeyboard-config) from x11-repo;
        // it also removes termux-x11-nightly, which it conflicts with
        val command = "$enableRepo && apt-get install -y $KEEP_CONFIGS '${deb.absolutePath}'; s=\$?; rm -f '${deb.absolutePath}'; exit \$s"
        shellTask("Installing $COMPANION_ANTIGEM…", command, 1800)
    }

    // ---------------- step 3: a desktop ----------------

    fun installDesktop() = runTask(Task.DESKTOP) {
        val desktop = _state.value.desktop
        shellTask(
            "Updating installed packages, then installing ${desktop.label}… (a large download, this can take a while)",
            "apt-get update && apt-get install -y $KEEP_CONFIGS x11-repo && apt-get update && " +
                "$UPGRADE && apt-get install -y $KEEP_CONFIGS dbus ${desktop.packages}",
            3600
        )
    }

    // ---------------- browser ----------------

    fun installBrowser() = runTask(Task.BROWSER) {
        val browser = _state.value.browser
        shellTask(
            "Updating installed packages, then installing ${browser.label}…",
            "apt-get update && apt-get install -y $KEEP_CONFIGS tur-repo && apt-get update && " +
                "$UPGRADE && apt-get install -y $KEEP_CONFIGS ${browser.packageName}",
            3600
        )
    }

    // ---------------- step 4: start / open / stop ----------------

    /**
     * Starts the X server with the chosen desktop (unless it is already running) and opens Termux:X11 once the desktop is
     * really up: the server listens on display :0 and keeps running for [SETTLE_MS]. (Its process exists long before
     * that, and when the desktop session fails, termux-x11 stops the server too.) Otherwise the end of its log says why.
     */
    fun start(context: Context) = runTask(Task.START) {
        if (xServerPids().isEmpty()) {
            stopRequested = false
            val tmp = LocalEnvironmentManager.getTmpDir(appContext)
            // Left behind when the X server was killed: it would refuse to start ("Server is already active for display 0")
            File(tmp, ".X0-lock").delete()
            val socket = File(tmp, ".X11-unix/X0")
            socket.delete()
            val desktop = _state.value.desktop
            val log = File(tmp, LOG_FILE)
            _state.update { it.copy(progress = "Starting the X server…") }
            // nohup + redirects: the command returns at once and the X server outlives it
            val command = "nohup termux-x11 $DISPLAY -xstartup \"${desktop.startCommand}\" " +
                "> '${log.absolutePath}' 2>&1 < /dev/null &"
            appendLog("$ $command")
            shell(command, 30)

            // 1. the X server comes up and listens on display :0 (its socket appears)
            val startedAt = System.currentTimeMillis()
            var seen = false
            var listening = false
            while (System.currentTimeMillis() - startedAt < START_TIMEOUT_MS) {
                delay(250)
                val alive = xServerPids().isNotEmpty()
                if (alive) seen = true
                if (alive && socket.exists()) { listening = true; break }
                if (!alive && (seen || System.currentTimeMillis() - startedAt > 5_000)) break
            }
            if (!listening) {
                failStart(desktop, seen)
                return@runTask
            }
            appendLog("✓ X server listening on display $DISPLAY")

            // 2. the desktop session starts; if it exits, the X server stops with it
            _state.update { it.copy(progress = "Starting ${desktop.label}…") }
            val settleUntil = System.currentTimeMillis() + SETTLE_MS
            while (System.currentTimeMillis() < settleUntil) {
                delay(250)
                if (xServerPids().isEmpty()) {
                    failStart(desktop, seen = true)
                    return@runTask
                }
            }
            appendLog("✓ ${desktop.label} running on display $DISPLAY")
        }
        refreshNow()
        withContext(Dispatchers.Main) { open(context) }
    }

    /** Start failed: the end of the X server's log goes into step 4's log, and the message says what happened. */
    private fun failStart(desktop: Desktop, seen: Boolean) {
        val tail = xLogTail()
        if (tail.isNotEmpty()) {
            appendLog("End of $LOG_FILE:")
            tail.forEach(::appendLog)
        }
        // termux-x11 prints "<pid> exited, status=<n>" when the desktop session ends
        val sessionEnded = tail.any { Regex("^\\d+ (exited, status=|killed by signal)").containsMatchIn(it) }
        val what = when {
            !seen -> "The X server did not start."
            sessionEnded -> "${desktop.label} stopped right after starting, and the X server with it."
            else -> "The X server stopped right after starting."
        }
        refreshNow()
        report("$what ${reasonHint(tail)}", isError = true)
    }

    fun open(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(X11_PACKAGE)
        if (intent == null) {
            report("Termux:X11 is not installed.", isError = true)
            return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { report("Could not open Termux:X11: ${it.message}", isError = true) }
    }

    /** Closes the Termux:X11 window and stops the X server; the desktop's programs exit with it. */
    fun stop(context: Context) = scope.launch {
        stopRequested = true
        try {
            runCatching {
                // the fork's own action name (its Java code keeps com.termux.x11), sent to its package ai.antigem.x11
                context.sendBroadcast(Intent("com.termux.x11.ACTION_STOP").setPackage(X11_PACKAGE))
            }
            // SIGTERM (not SIGKILL) so the X server removes its lock file and socket
            xServerPids().forEach { pid -> runCatching { Os.kill(pid, OsConstants.SIGTERM) } }
            for (attempt in 0 until 12) {
                if (xServerPids().isEmpty()) break
                delay(250)
            }
            xServerPids().forEach { pid -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
            refreshNow()
            report("Desktop stopped.")
        } finally {
            stopRequested = false
        }
    }

    // ---------------- optional: Android's child process limit ----------------

    /**
     * Turns off Android 12+'s limit on processes started by apps (it kills them past ~32 in total,
     * and a desktop starts many), through the terminal's adb connected to this phone.
     */
    fun disableChildProcessLimit(serial: String) = runTask(Task.CHILD_LIMIT) {
        val inner = "/system/bin/device_config set_sync_disabled_for_tests persistent; " +
            "/system/bin/device_config put activity_manager max_phantom_processes 2147483647; " +
            "/system/bin/settings put global settings_enable_monitor_phantom_procs false; " +
            "echo max_phantom_processes=\$(/system/bin/device_config get activity_manager max_phantom_processes); " +
            "echo settings_enable_monitor_phantom_procs=\$(/system/bin/settings get global settings_enable_monitor_phantom_procs)"
        shellTask("Changing the limit over adb…", "adb -s '$serial' shell '$inner'", 60)
    }

    // ---------------- helpers ----------------

    private fun runTask(task: Task, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.update { it.copy(task = task, logTask = task, log = emptyList(), progress = null, message = null, isError = false) }
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$task failed", e)
                appendLog("✗ ${e.message ?: e.javaClass.simpleName}")
                report(e.message ?: "Failed", isError = true)
            } finally {
                refreshNow()
                _state.update { it.copy(task = null, progress = null) }
            }
        }
    }

    /** Runs [command] in the terminal, streaming its output into the log; fails the task on a non-zero exit. */
    private suspend fun shellTask(progress: String, command: String, timeoutSeconds: Long) {
        _state.update { it.copy(progress = progress) }
        appendLog("$ $command")
        val result = shell(command, timeoutSeconds) { line -> if (line.isNotBlank()) appendLog(line) }
        if (result.exitCode != 0) {
            report("Failed (exit ${result.exitCode}). Check the log, or run \"pkg upgrade\" in the terminal and try again.", isError = true)
        } else {
            appendLog("✓ Done")
        }
    }

    private suspend fun shell(command: String, timeoutSeconds: Long, onLine: ((String) -> Unit)? = null): LocalCommandResult =
        LocalEnvironmentManager.executeCommand(
            "( $command ) < /dev/null", appContext, timeoutSeconds = timeoutSeconds,
            customEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"), onOutputLine = onLine
        )

    private fun appendLog(line: String) =
        _state.update { it.copy(log = (it.log + line).takeLast(400)) }

    private fun userAgent() = "AntiGem/${BuildConfig.VERSION_NAME}"

    /** The companion .deb of the fork's latest release (its file name carries the version). */
    private suspend fun latestForkDebUrl(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(FORK_RELEASE_API)
            .header("User-Agent", userAgent())
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Could not read the latest release (HTTP ${response.code}).")
            val assets = JSONObject(response.body?.string().orEmpty()).optJSONArray("assets")
                ?: throw IllegalStateException("The latest release has no files.")
            (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").let { n -> n.startsWith(COMPANION_ANTIGEM) && n.endsWith("-all.deb") } }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("The latest release has no $COMPANION_ANTIGEM .deb file.")
        }
    }

    private suspend fun download(url: String, target: File, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", userAgent()).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Download failed (HTTP ${response.code}).")
            val body = response.body ?: throw IllegalStateException("Download failed (empty response).")
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, target.name + ".part")
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(if (pct >= 0) "$pct%" else "${done / 1024} KB")
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        }
    }
}
