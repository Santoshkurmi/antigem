package ai.antigem.data.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import ai.antigem.data.local.LocalCommandResult
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.local.chroot.ChrootSetup
import ai.antigem.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.concurrent.thread

/**
 * How AntiGem gets shell-level access to this phone, picked in Settings → ADB Connect:
 *  - ADB: the terminal's adb, connected over Wireless debugging ([AdbConnectManager]).
 *  - Shizuku: commands run through Shizuku as the shell user, no pairing ([ShizukuShell]).
 *  - Root: commands run through the phone's su ([RootShell]).
 *
 * The picked mode is what Android Automation uses (keys, installs, `android_adb_shell`…). In chat every mode that is
 * set up can be used: `adb` in the terminal, `rish` for Shizuku, `su` for root (`android-su` in the Ubuntu chroot,
 * whose own su is Ubuntu's). `rish` and `android-su` reach the app through its local server ([serveExec]).
 */
object DeviceAccess {
    private const val TAG = "DeviceAccess"
    private const val PREFS = "adb_connect"
    /** Output kept for a tool result (a base64 screenshot of a big screen fits). */
    private const val MAX_OUTPUT = 24 * 1024 * 1024
    private const val MARKER = "Written by AntiGem"

    enum class Mode(val label: String, val key: String) {
        ADB("ADB", "adb"), SHIZUKU("Shizuku", "shizuku"), ROOT("Root", "root");

        companion object {
            fun of(key: String?): Mode? = entries.firstOrNull { it.key == key }
        }
    }

    /** One running command: its input, its output (stdout and stderr together), its exit code and a way to stop it. */
    interface RunningCommand {
        val input: OutputStream
        val output: InputStream
        fun waitFor(): Int
        fun kill()
    }

    private val _mode = MutableStateFlow(Mode.ADB)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private lateinit var appContext: Context
    private val prefs get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _mode.value = Mode.of(prefs.getString("device_access_mode", null)) ?: Mode.ADB
        ShizukuShell.init(appContext)
        RootShell.init(appContext)
    }

    fun setMode(mode: Mode) {
        prefs.edit().putString("device_access_mode", mode.key).apply()
        _mode.value = mode
    }

    // ---------------- running commands ----------------

    /** Why [mode] can't run commands right now, or null when it can (ADB connects first when it was paired). */
    suspend fun notReadyMessage(mode: Mode = _mode.value): String? = when (mode) {
        Mode.ADB -> if (AdbConnectManager.ensureConnected() != null) null else ADB_NOT_READY
        Mode.SHIZUKU -> ShizukuShell.notReadyMessage()
        Mode.ROOT -> {
            if (RootShell.state.value.check == RootShell.Check.UNKNOWN) RootShell.check()
            RootShell.notReadyMessage()
        }
    }

    const val ADB_NOT_READY = "ADB is not connected. Ask the user to open AntiGem → Settings → ADB Connect and connect " +
        "(pair once, with Wireless debugging on), or to pick Shizuku or Root there."

    /** A short description of what the picked mode is, for status output. */
    fun describe(): String = when (_mode.value) {
        Mode.ADB -> AdbConnectManager.state.value.connectedDevice?.let { "ADB (${it.serial})" } ?: "ADB (not connected)"
        Mode.SHIZUKU -> ShizukuShell.status.value.let { if (it.ready) "Shizuku (as ${it.userLabel})" else "Shizuku (not ready)" }
        Mode.ROOT -> if (RootShell.state.value.check == RootShell.Check.GRANTED) "Root (su)" else "Root (not granted)"
    }

    /**
     * Runs [command] (sh) on this phone through [mode]: as the shell user (ADB, Shizuku) or root. The output is stdout
     * and stderr together. When the mode isn't ready, the result has exit code -1 and says why.
     */
    suspend fun shell(command: String, seconds: Int = 30, mode: Mode = _mode.value): LocalCommandResult {
        val started = System.currentTimeMillis()
        if (mode == Mode.ADB) {
            val device = AdbConnectManager.ensureConnected() ?: return LocalCommandResult(-1, ADB_NOT_READY, 0)
            return AdbConnectManager.deviceShell(device, command, seconds)
        }
        notReadyMessage(mode)?.let { return LocalCommandResult(-1, it, 0) }
        val out = CappedOutput(MAX_OUTPUT)
        val code = try {
            run(mode, command, null, null, out, seconds * 1000L)
        } catch (e: Exception) {
            return LocalCommandResult(-1, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - started)
        }
        return LocalCommandResult(code, out.text(), System.currentTimeMillis() - started)
    }

    /**
     * Runs [command] through Shizuku or root, in [cwd] when that folder exists for the user it runs as (else
     * /data/local/tmp), with [stdin] as its input and its output written (and flushed) to [output] as it comes.
     * Blocks until it ends; [onStart] gets a way to stop it early. Returns its exit code (124 after [timeoutMs]).
     */
    suspend fun run(
        mode: Mode,
        command: String,
        cwd: String?,
        stdin: InputStream?,
        output: OutputStream,
        timeoutMs: Long,
        onStart: ((stop: () -> Unit) -> Unit)? = null
    ): Int {
        val script = buildString {
            cwd?.takeIf { it.startsWith("/") }?.let { append("cd ${quote(it)} 2>/dev/null || ") }
            append("cd /data/local/tmp 2>/dev/null\n")
            append(command)
        }
        val cmd = when (mode) {
            Mode.SHIZUKU -> ShizukuShell.start(script)
            Mode.ROOT -> RootShell.start(script)
            Mode.ADB -> throw IllegalArgumentException("ADB commands go through adb")
        }
        return withContext(Dispatchers.IO) {
            val timedOut = AtomicBoolean(false)
            val stopped = AtomicBoolean(false)
            val stop = { if (stopped.compareAndSet(false, true)) cmd.kill() }
            onStart?.invoke(stop)
            val timer = Timer(true)
            timer.schedule(timeoutMs) { timedOut.set(true); stop() }
            thread(name = "device-access-stdin", isDaemon = true) {
                try {
                    stdin?.copyTo(cmd.input)
                } catch (_: Exception) {
                } finally {
                    runCatching { cmd.input.close() }
                }
            }
            var waited = false
            try {
                cmd.output.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        try {
                            output.write(buf, 0, n)
                            output.flush()
                        } catch (e: IOException) {
                            // whoever wanted the output is gone: stop the command
                            stop()
                            throw e
                        }
                    }
                }
                val code = cmd.waitFor()
                waited = true
                if (timedOut.get()) 124 else code
            } finally {
                timer.cancel()
                // cut short: still collect the exit status, so its pipe / process is released
                if (!waited) thread(name = "device-access-reap", isDaemon = true) { runCatching { cmd.waitFor() } }
            }
        }
    }

    /** A PNG screenshot through [mode] (Android 10 and older, where accessibility can't take one), or null. */
    suspend fun screencapPng(mode: Mode = _mode.value): ByteArray? {
        if (mode == Mode.ADB) {
            val device = AdbConnectManager.ensureConnected() ?: return null
            val b64 = AdbConnectManager.runAdb(device, "exec-out screencap -p | base64 | tr -d '\\n'", 30).output.trim()
            return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        }
        if (notReadyMessage(mode) != null) return null
        // Shizuku's and su's pipes carry bytes as they are: the PNG comes through without base64
        val out = ByteArrayOutputStream()
        val code = runCatching { run(mode, "screencap -p", null, null, out, 30_000L) }.getOrNull()
        return out.toByteArray().takeIf { code == 0 && it.isNotEmpty() }
    }

    /**
     * Installs the APK at [path]: absolute, or relative to the terminal's home (in the Ubuntu chroot: a path there).
     * ADB uses `adb install`; Shizuku and root pass the file to `pm install -S` on its input, since the shell user
     * can't read the app's files.
     */
    suspend fun installApk(path: String, mode: Mode = _mode.value): LocalCommandResult {
        if (mode == Mode.ADB) {
            val device = AdbConnectManager.ensureConnected() ?: return LocalCommandResult(-1, ADB_NOT_READY, 0)
            return AdbConnectManager.runAdb(device, "install -r ${quote(path)}", 240)
        }
        notReadyMessage(mode)?.let { return LocalCommandResult(-1, it, 0) }
        if (AuthPreferences.currentRemoteMode) {
            return LocalCommandResult(-1, "In server mode the APK is on the server, not on this phone. Use ADB mode " +
                "(the server's adb installs it) or copy the APK to the phone first.", 0)
        }
        val started = System.currentTimeMillis()
        val (file, temporary) = localFile(path) ?: return LocalCommandResult(-1, "No file at $path", 0)
        try {
            val out = CappedOutput(MAX_OUTPUT)
            val code = file.inputStream().use { input ->
                run(mode, "pm install -r -S ${file.length()}", null, input, out, 240_000L)
            }
            return LocalCommandResult(code, out.text(), System.currentTimeMillis() - started)
        } finally {
            if (temporary) file.delete()
        }
    }

    /** [path] as a file the app can read; in the Ubuntu chroot it is copied out first (true = a temporary copy). */
    private suspend fun localFile(path: String): Pair<File, Boolean>? = withContext(Dispatchers.IO) {
        val chroot = ChrootSetup.install()
        if (chroot != null) {
            val dest = File(appContext.cacheDir, "device-access/install-${System.nanoTime()}.apk")
            dest.parentFile?.mkdirs()
            // relative paths are the chroot user's: start in their home
            val r = ChrootManager.streamAsRoot(appContext, chroot, "cd ~${chroot.user} 2>/dev/null; exec cat -- ${quote(path)}") { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            if (r.ok && dest.length() > 0) dest to true else { dest.delete(); null }
        } else {
            val f = if (path.startsWith("/")) File(path) else File(LocalEnvironmentManager.getHomeDir(appContext), path)
            if (f.isFile) f to false else null
        }
    }

    // ---------------- rish / android-su (the app's local server) ----------------

    /** Exit codes of finished `rish` / `android-su` runs, by the id the client sent, until it fetches them. */
    private val exits = ConcurrentHashMap<String, Pair<Long, String>>()
    private val EXEC_ID = Regex("^[A-Za-z0-9_-]{1,80}$")

    private fun rememberExit(id: String, code: Int, message: String? = null) {
        val now = System.currentTimeMillis()
        exits.entries.removeIf { now - it.value.first > 10 * 60_000L }
        exits[id] = now to (if (message.isNullOrBlank()) "$code" else "$code\n$message")
    }

    /**
     * POST /api/device/exec (already authorized by the server's X-Device-Id check): runs X-Exec-Command (base64) through
     * X-Exec-Mode (shizuku or root) in X-Exec-Cwd (base64), with the request body as its input, and streams its output
     * back as the response. The client then gets the exit code from /api/device/exit?id=<X-Exec-Id>.
     */
    suspend fun serveExec(headers: Map<String, String>, body: ByteArray, clientInput: InputStream, out: OutputStream) {
        val id = headers["x-exec-id"].orEmpty()
        if (!EXEC_ID.matches(id)) return plain(out, 400, "Missing or invalid X-Exec-Id")
        val mode = when (headers["x-exec-mode"]) {
            "shizuku" -> Mode.SHIZUKU
            "root" -> Mode.ROOT
            else -> return plain(out, 400, "X-Exec-Mode must be shizuku or root").also { rememberExit(id, 2, "X-Exec-Mode must be shizuku or root") }
        }
        val command = decode(headers["x-exec-command"])
        if (command.isNullOrBlank()) return plain(out, 400, "No command").also { rememberExit(id, 2, "no command given") }
        notReadyMessage(mode)?.let { msg ->
            rememberExit(id, 1, msg)
            return plain(out, 409, msg)
        }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        out.flush()
        val finished = AtomicBoolean(false)
        val code = try {
            run(mode, command, decode(headers["x-exec-cwd"]), ByteArrayInputStream(body), out, 24 * 3600_000L) { stop ->
                // the client sends nothing after its request: end of stream means it closed (Ctrl-C, killed)
                thread(name = "device-exec-watch", isDaemon = true) {
                    while (!finished.get()) {
                        val b = try { clientInput.read() } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { -1 }
                        if (b < 0) { if (!finished.get()) stop(); break }
                    }
                }
            }
        } catch (e: Exception) {
            rememberExit(id, 1, e.message ?: e.javaClass.simpleName)
            return
        } finally {
            finished.set(true)
        }
        rememberExit(id, code)
    }

    /** GET /api/device/exit?id=…: the exit code (first line) and an error message, if any. */
    fun serveExit(query: String, out: OutputStream) {
        val id = query.split('&').firstOrNull { it.startsWith("id=") }?.removePrefix("id=").orEmpty()
        val status = exits.remove(id)?.second
        if (status == null) plain(out, 404, "unknown run (AntiGem may have restarted)") else plain(out, 200, status)
    }

    private fun decode(b64: String?): String? =
        b64?.takeIf { it.isNotBlank() }?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() }

    private fun plain(out: OutputStream, status: Int, text: String) {
        val bytes = text.toByteArray()
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error" }
        out.write(("HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    // ---------------- terminal commands ----------------

    private fun token(): String = ai.antigem.data.remote.LocalServerAuth.key(appContext)

    private fun port(): Int = ai.antigem.data.remote.AndroidLocalBridgeServer.instance.boundPort

    private fun configText(): String =
        "# AntiGem's local server, for rish and android-su. $MARKER on every start; edits are overwritten.\n" +
            "PORT=${port()}\nTOKEN=${token()}\n"

    /**
     * Writes `rish` into AntiGem's own environment ($PREFIX/bin) and its settings file (~/.config/antigem/device-access).
     * A `rish` not written by AntiGem (e.g. Shizuku's own, exported there) is left alone. Cheap: only writes what changed.
     */
    fun ensureTerminalCommands(context: Context) {
        if (!::appContext.isInitialized) init(context)
        if (AuthPreferences(context).isChrootModeSync()) return
        try {
            val bin = LocalEnvironmentManager.getBinDir(context)
            if (!bin.isDirectory) return
            val prefix = LocalEnvironmentManager.getPrefixDir(context).absolutePath
            writeIfChanged(File(LocalEnvironmentManager.getHomeDir(context), ".config/antigem/device-access"), configText(), executable = false)
            val rish = File(bin, "rish")
            if (!rish.exists() || rish.readText().contains(MARKER)) writeIfChanged(rish, clientScript("rish", Mode.SHIZUKU, "$prefix/bin/bash"), executable = true)
        } catch (e: Exception) {
            Log.w(TAG, "Writing rish failed: ${e.message}")
        }
    }

    @Volatile private var chrootWritten: String? = null

    /**
     * Writes `rish` and `android-su` into the Ubuntu chroot (/usr/local/bin) and the settings file into its user's home.
     * Called when the chroot starts; skipped when this app process already wrote the same files.
     */
    suspend fun ensureChrootCommands(context: Context) {
        if (!::appContext.isInitialized) init(context)
        val install = ChrootSetup.install() ?: return
        val rish = clientScript("rish", Mode.SHIZUKU, "/bin/bash")
        val su = clientScript("android-su", Mode.ROOT, "/bin/bash")
        val config = configText()
        val key = "${install.path}|${install.user}|${rish.hashCode()}|${su.hashCode()}|${config.hashCode()}"
        if (chrootWritten == key) return
        // commands as root (a rish that isn't AntiGem's stays), the settings file as the user (never through their links as root)
        val b = { s: String -> Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP) }
        val rootScript = "set -e\n" +
            "w() { if [ -e \"\$1\" ] && ! grep -q '$MARKER' \"\$1\"; then return 0; fi; " +
            "printf '%s' \"\$2\" | base64 -d > \"\$1.tmp\"; chmod 755 \"\$1.tmp\"; mv -f \"\$1.tmp\" \"\$1\"; }\n" +
            "mkdir -p /usr/local/bin\n" +
            "w /usr/local/bin/rish '${b(rish)}'\n" +
            "w /usr/local/bin/android-su '${b(su)}'\n"
        val userScript = "set -e; umask 077; h=\"\${HOME:-\$(getent passwd \"\$(id -un)\" | cut -d: -f6)}\"; " +
            "mkdir -p \"\$h/.config/antigem\"; " +
            "printf '%s' '${b(config)}' | base64 -d > \"\$h/.config/antigem/device-access.tmp\"; " +
            "mv -f \"\$h/.config/antigem/device-access.tmp\" \"\$h/.config/antigem/device-access\""
        val r1 = ChrootSetup.asRoot(context, install, rootScript)
        val r2 = ChrootSetup.asUser(context, install, userScript)
        if (r1.ok && r2.ok) chrootWritten = key
        else Log.w(TAG, "Writing rish / android-su into the chroot failed: ${r1.message()} / ${r2.message()}")
    }

    private fun writeIfChanged(file: File, text: String, executable: Boolean) {
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        if (executable) tmp.setExecutable(true, false)
        // only the app's own user reads it: it holds the local server's key
        if (!executable) { tmp.setReadable(false, false); tmp.setReadable(true, true) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** The `rish` / `android-su` client: a bash script that sends the command to the app's local server with curl. */
    private fun clientScript(name: String, mode: Mode, bash: String): String {
        val who = if (mode == Mode.ROOT) "root, through the phone's su" else "the shell user, through Shizuku"
        val prompt = if (mode == Mode.ROOT) "android-su# " else "rish\$ "
        val d = "$"
        return """
            |#!$bash
            |# $name: runs commands on this Android phone as $who.
            |# $MARKER (Settings > ADB Connect); edits are overwritten. AntiGem must be running.
            |NAME=$name
            |MODE=${mode.key}
            |CONF="${d}HOME/.config/antigem/device-access"
            |if [ ! -r "${d}CONF" ]; then
            |    echo "${d}NAME: ${d}CONF is missing. Open AntiGem once (it writes this file), then try again." >&2
            |    exit 1
            |fi
            |. "${d}CONF"
            |if [ -z "${d}PORT" ] || [ -z "${d}TOKEN" ]; then
            |    echo "${d}NAME: ${d}CONF is incomplete. Open AntiGem once, then try again." >&2
            |    exit 1
            |fi
            |if ! command -v curl >/dev/null 2>&1; then
            |    echo "${d}NAME needs curl: install it (pkg install curl, or apt install curl in Ubuntu)." >&2
            |    exit 1
            |fi
            |
            |usage() {
            |    cat <<USAGE
            |Usage: ${d}NAME -c 'command'     run a command (sh syntax) on the phone as $who
            |       ${d}NAME program [args]   run a program with these arguments
            |       ${d}NAME                  interactive: each line runs as its own command (cd is remembered)
            |Output is stdout and stderr together; the exit code is the command's. Data piped in, or a file
            |redirected with <, becomes the command's input. Commands start in the current folder when the
            |phone has it (e.g. /sdcard/...), else in /data/local/tmp.
            |USAGE
            |}
            |
            |b64() { printf '%s' "${d}1" | base64 | tr -d '\n'; }
            |
            |# Whether to pass our input on: a file redirected with <, or a pipe that has data (or has ended) within a
            |# second. A pipe that stays open with nothing in it (some tools run commands that way) is not waited for.
            |has_input() {
            |    [ -f /dev/stdin ] && return 0
            |    [ -p /dev/stdin ] || return 1
            |    local i
            |    for i in 1 2 3 4 5 6 7 8 9 10; do
            |        read -t 0 && return 0
            |        sleep 0.1
            |    done
            |    return 1
            |}
            |
            |# ${d}1 = command, ${d}2 = folder to start in
            |run() {
            |    local id="${d}{RANDOM}${d}{RANDOM}${d}${d}${d}(date +%s%N 2>/dev/null)"
            |    local input=/dev/null
            |    if has_input; then input=/dev/stdin; fi
            |    curl -s -N --fail -X POST -H "Expect:" \
            |        -H "X-Device-Id: ${d}TOKEN" -H "X-Exec-Id: ${d}id" -H "X-Exec-Mode: ${d}MODE" \
            |        -H "X-Exec-Command: ${d}(b64 "${d}1")" -H "X-Exec-Cwd: ${d}(b64 "${d}2")" \
            |        -H "Content-Type: application/octet-stream" --data-binary "@${d}input" \
            |        "http://127.0.0.1:${d}PORT/api/device/exec"
            |    local rc=${d}?
            |    if [ ${d}rc -eq 7 ]; then
            |        echo "${d}NAME: can't reach AntiGem on port ${d}PORT. Is AntiGem running?" >&2
            |        return 1
            |    fi
            |    local status
            |    status=${d}(curl -sS -H "X-Device-Id: ${d}TOKEN" "http://127.0.0.1:${d}PORT/api/device/exit?id=${d}id" 2>/dev/null)
            |    local code="${d}{status%%${d}'\n'*}"
            |    if [ "${d}status" != "${d}code" ]; then echo "${d}NAME: ${d}{status#*${d}'\n'}" >&2; fi
            |    case "${d}code" in
            |        ''|*[!0-9]*) [ -z "${d}status" ] && echo "${d}NAME: no exit code from AntiGem (curl exit ${d}rc)" >&2; return 1 ;;
            |    esac
            |    return "${d}code"
            |}
            |
            |case "${d}1" in
            |    -h|--help) usage; exit 0 ;;
            |    -c)
            |        if [ ${d}# -lt 2 ]; then usage >&2; exit 2; fi
            |        run "${d}2" "${d}PWD"; exit ${d}? ;;
            |    -*) echo "${d}NAME: unknown option ${d}1" >&2; usage >&2; exit 2 ;;
            |esac
            |if [ ${d}# -gt 0 ]; then
            |    run "${d}(printf '%q ' "${d}@")" "${d}PWD"; exit ${d}?
            |fi
            |if [ ! -t 0 ]; then
            |    # a script piped in: run it as one command
            |    if ! has_input; then usage >&2; exit 2; fi
            |    script=${d}(cat)
            |    run "${d}script" "${d}PWD" < /dev/null; exit ${d}?
            |fi
            |cwd="${d}PWD"
            |echo "${d}NAME: commands run on the phone as $who. Each line is its own command; cd is remembered. Ctrl-D quits."
            |while IFS= read -r -e -p "$prompt" line; do
            |    case "${d}line" in *[![:space:]]*) ;; *) continue ;; esac
            |    if [[ "${d}line" =~ ^[[:space:]]*cd([[:space:]]+(.*))?[[:space:]]*${d} ]]; then
            |        target="${d}{BASH_REMATCH[2]:-/data/local/tmp}"
            |        new=${d}(run "cd ${d}target && pwd" "${d}cwd" < /dev/null) && [ -n "${d}new" ] && cwd="${d}new"
            |        continue
            |    fi
            |    run "${d}line" "${d}cwd" < /dev/null
            |done
            |echo
            |""".trimMargin()
    }

    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** Keeps at most [max] bytes of output and says when the rest was cut. */
    private class CappedOutput(private val max: Int) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        private var cut = false
        override fun write(b: Int) { if (buf.size() < max) buf.write(b) else cut = true }
        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = max - buf.size()
            if (room > 0) buf.write(b, off, minOf(room, len))
            if (len > room) cut = true
        }
        fun text(): String = buf.toString("UTF-8") + if (cut) "\n…(output cut at ${max / (1024 * 1024)} MB)" else ""
    }
}
