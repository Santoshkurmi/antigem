package ai.antigem.data.homescreen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import ai.antigem.R
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalTerminalManager
import ai.antigem.data.local.chroot.ChrootInstall
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.data.local.chroot.ChrootSetup
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.run.RunManager
import ai.antigem.data.run.RunStatus
import ai.antigem.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import ai.antigem.i18n.L

/**
 * Scripts on the home screen, the way Termux:Widget does it: every file in `~/.shortcuts` (and its folders, 5 deep)
 * runs in a new terminal tab, files in a folder named `tasks` run in the background (as a Run, with a notification when
 * they end), `~/.shortcuts/icons/<name>.png` is the icon of `<name>`'s home-screen shortcut, and hidden files are left
 * out. A script runs with the interpreter its first line names (`#!/usr/bin/env python3` → python3), else bash.
 *
 * Where `~` is: AntiGem's own home (files/home), or in the Ubuntu chroot the chroot user's home (/home/<user>), read
 * and run inside the chroot as that user. The app can't read the rootfs itself, so the chroot's list is read through
 * root ([refresh]) and kept in files/home_screen/scripts_chroot.json for the widget.
 *
 * What may run: a file whose path is in `~/.shortcuts` (a symlink there may point anywhere in the home, so scripts kept
 * in `~/scripts` work without copies) and whose real file is in the app's own storage: AntiGem's home or its programs
 * (files/usr); in the chroot the user's home, /usr or /opt on the rootfs' own disk, outside folders shared from
 * Android. Shared storage is refused everywhere: any app with the storage permission could change a script there and
 * have AntiGem run it. Links come signed ([HomeLinks]), so no other app can ask for a script at all.
 */
object HomeScripts {
    /**
     * One script of ~/.shortcuts. [path] is where it is in ~/.shortcuts (a path inside the Ubuntu chroot when
     * [chroot]); [link]: a symlink there (to a script elsewhere), which Settings can remove without touching the script.
     */
    data class Script(val path: String, val background: Boolean, val label: String, val chroot: Boolean = false, val link: Boolean = false) {
        val name: String get() = path.substringAfterLast('/')
    }

    /**
     * What ~/.shortcuts holds. [home]: the home it is in (null: the chroot's list was not read yet); [error]: the
     * chroot could not be read.
     */
    data class Listing(
        val scripts: List<Script>,
        val chroot: Boolean,
        val hasFolder: Boolean,
        val home: String?,
        val error: String? = null
    ) {
        val read: Boolean get() = home != null || error != null
    }

    /** A folder's entries in the "add a script" picker. */
    data class Entry(val name: String, val dir: Boolean)
    data class Browse(val dir: String, val entries: List<Entry>, val error: String? = null)

    private const val CHANNEL = "home_scripts"
    private const val MAX_DEPTH = 5
    /** A symlink to a big folder (the home itself) in ~/.shortcuts must not make every widget redraw walk all of it. */
    private const val MAX_SCRIPTS = 500
    private const val MAX_VISITED = 5000
    private const val ICON_MAX_BYTES = 1024 * 1024
    private const val CACHE_FILE = "home_screen/scripts_chroot.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _changes = MutableStateFlow(0)
    /** Bumped when the list may have changed (the Scripts widget reads the folder again). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun touch() {
        _changes.value++
    }

    // ------------------------------------------------------------------ where

    /** The Ubuntu chroot when it is the phone's runtime (the terminals run in it), else null. */
    fun chrootInstall(context: Context): ChrootInstall? =
        if (ChrootManager.isActive(context)) ChrootManager.activeInstall(context) else null

    /** AntiGem's own ~/.shortcuts (the chroot's is inside the rootfs). */
    fun dir(context: Context): File = File(LocalEnvironmentManager.getHomeDir(context), ".shortcuts")

    /** "~/.shortcuts", or "Ubuntu · ~/.shortcuts" in the chroot. */
    fun whereLabel(context: Context): String =
        if (chrootInstall(context) != null) L.homescreen.scriptsWidget.ubuntuShortcuts else "~/.shortcuts"

    // ------------------------------------------------------------------ the list

    /** The scripts now: AntiGem's home is read at once; the chroot's list is the one [refresh] read last. */
    fun listing(context: Context): Listing {
        val install = chrootInstall(context)
        return if (install != null) chrootCached(context, install) else termuxListing(context)
    }

    fun list(context: Context): List<Script> = listing(context).scripts

    /** Reads the list again (in the chroot through root) and tells the widget and Settings. */
    suspend fun refresh(context: Context): Listing {
        val app = context.applicationContext
        val install = chrootInstall(app)
        if (install != null) withContext(Dispatchers.IO) { saveCache(app, install, readChroot(app, install)) }
        touch()
        return listing(app)
    }

    private fun termuxListing(context: Context): Listing {
        val home = LocalEnvironmentManager.getHomeDir(context)
        val root = File(home, ".shortcuts")
        if (!root.isDirectory) return Listing(emptyList(), chroot = false, hasFolder = false, home = home.absolutePath)
        val found = mutableListOf<String>()
        var visited = 0
        fun walk(d: File, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (f in d.listFiles().orEmpty()) {
                if (++visited > MAX_VISITED || found.size >= MAX_SCRIPTS) return
                if (f.name.startsWith(".")) continue
                if (depth == 0 && f.name == "icons") continue
                when {
                    f.isDirectory -> walk(f, depth + 1)
                    // broken links are neither; files outside the allowed places are left out, as they can't run
                    f.isFile && allowedReal(context, f) != null -> found += f.absolutePath
                }
            }
        }
        runCatching { walk(root, 0) }
        val prefix = root.absolutePath + "/"
        val scripts = found.sortedWith { a, b -> compareRel(a.removePrefix(prefix), b.removePrefix(prefix)) }
            .map { toScript(prefix, it, chroot = false, link = isLink(it)) }
        return Listing(scripts, chroot = false, hasFolder = true, home = home.absolutePath)
    }

    private fun toScript(prefix: String, path: String, chroot: Boolean, link: Boolean): Script {
        val parts = path.removePrefix(prefix).split('/')
        val name = parts.last()
        val parent = parts.getOrNull(parts.size - 2)
        // like Termux:Widget: a script in a folder named tasks runs in the background; "folder/name" below the top
        val label = if (parent == null || (parts.size == 2 && parent == "tasks")) name else "$parent/$name"
        return Script(path, background = parent == "tasks", label = label, chroot = chroot, link = link)
    }

    /** Files before folders at each level, then natural order (2 before 10), as in Termux:Widget. */
    private fun compareRel(a: String, b: String): Int {
        val x = a.split('/')
        val y = b.split('/')
        var i = 0
        while (i < x.size && i < y.size) {
            val xFile = i == x.size - 1
            val yFile = i == y.size - 1
            if (xFile != yFile) return if (xFile) -1 else 1
            val c = natural(x[i], y[i])
            if (c != 0) return c
            i++
        }
        return x.size - y.size
    }

    private fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun isLink(path: String): Boolean = runCatching { OsConstants.S_ISLNK(Os.lstat(path).st_mode) }.getOrDefault(false)

    // ------------------------------------------------------------------ what may run (AntiGem's home)

    /** The places a script's real file may be in: AntiGem's home and its programs (only the app can write there). */
    private fun allowedRoots(context: Context): List<String> =
        listOf(LocalEnvironmentManager.getHomeDir(context), LocalEnvironmentManager.getPrefixDir(context))
            .map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) + File.separator }

    /** The real file of [file] (symlinks followed) when it may run, else null. */
    private fun allowedReal(context: Context, file: File): File? {
        val real = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!real.isFile) return null
        return real.takeIf { r -> allowedRoots(context).any { r.path.startsWith(it) } }
    }

    /** [path] is a plain path inside ~/.shortcuts (no "." or ".." on the way). */
    private fun inShortcuts(root: String, path: String): Boolean =
        path.startsWith("$root/") && path.split('/').none { it == "." || it == ".." }

    /** The real file to run for a link of AntiGem's own ~/.shortcuts, or null after telling why not. */
    private fun termuxReal(context: Context, path: String): File? {
        val file = File(path)
        if (!inShortcuts(dir(context).absolutePath, path)) {
            toast(context, L.homescreen.homeScripts.onlyScriptsInShortcutsCanRun)
            return null
        }
        if (!file.exists()) {
            toast(context, L.homescreen.homeScripts.nameIsGoneRenamedOrDeleted(file.name))
            return null
        }
        return allowedReal(context, file) ?: run {
            toast(context, L.homescreen.homeScripts.nameIsNotRunItsRealFile(file.name))
            null
        }
    }

    /** The command for a script of AntiGem's home: a program as it is, else the interpreter of its first line, else bash. */
    fun command(real: File): String {
        val head = runCatching {
            real.inputStream().use { s ->
                val b = ByteArray(256)
                val n = s.read(b)
                if (n <= 0) ByteArray(0) else b.copyOf(n)
            }
        }.getOrDefault(ByteArray(0))
        if (head.size >= 4 && head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()) {
            return quote(real.absolutePath)
        }
        val first = String(head, Charsets.UTF_8).substringBefore('\n').trimEnd('\r')
        val interp = if (first.startsWith("#!")) {
            val parts = first.removePrefix("#!").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val exe = parts.firstOrNull()?.substringAfterLast('/').orEmpty()
            // "#!/usr/bin/env python3" → python3; "#!/bin/bash -e" → bash -e (Android has no /usr/bin/env)
            val chosen = if (exe == "env") parts.drop(1).filterNot { it.startsWith("-") } else listOf(exe) + parts.drop(1)
            chosen.joinToString(" ").ifBlank { "bash" }
        } else "bash"
        return "$interp ${quote(real.absolutePath)}"
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ the Ubuntu chroot

    /**
     * `ok <file> <shared folder>...` (POSIX sh, inside the chroot as its user): the real file of <file> is a file in the
     * home, /usr or /opt, on the rootfs' own disk (not /sdcard, /storage, USB drives: other filesystems) and not in a
     * folder shared from Android (same disk as /data, so named one by one). Sets r to the real file.
     */
    private const val SH_OK = """RD=${'$'}(stat -c %d /)
ok() {
  r=${'$'}(realpath -e -- "${'$'}1" 2>/dev/null) || return 1
  [ -f "${'$'}r" ] || return 1
  case "${'$'}r" in "${'$'}HOME"/*|/usr/*|/opt/*) ;; *) return 1;; esac
  [ "${'$'}(stat -c %d -- "${'$'}r" 2>/dev/null)" = "${'$'}RD" ] || return 1
  shift
  for b do case "${'$'}r" in "${'$'}b"|"${'$'}b"/*) return 1;; esac; done
  return 0
}
"""

    /** Lists ~/.shortcuts like Termux:Widget: `HOME\t<home>`, then `F\t<path>` (a file) or `L\t<path>` (a symlink). */
    private const val SH_LIST = """D="${'$'}HOME/.shortcuts"
printf 'HOME\t%s\n' "${'$'}HOME"
[ -d "${'$'}D" ] || { echo NODIR; exit 0; }
""" + SH_OK + """find -L "${'$'}D" -mindepth 1 -maxdepth 6 \( -name '.*' -o -path "${'$'}D/icons" \) -prune -o -type f -print 2>/dev/null | head -n $MAX_VISITED |
while IFS= read -r p; do
  ok "${'$'}p" "${'$'}@" || continue
  if [ -L "${'$'}p" ]; then t=L; else t=F; fi
  printf '%s\t%s\n' "${'$'}t" "${'$'}p"
done | head -n $MAX_SCRIPTS
"""

    /** Runs a script of ~/.shortcuts (`$1`) after checking it again inside the chroot: the app can't see the rootfs. */
    private const val SH_RUN = """f=${'$'}1; shift
case "${'$'}f" in "${'$'}HOME"/.shortcuts/*) ;; *) echo "AntiGem: only scripts in ~/.shortcuts can run: ${'$'}f" >&2; exit 126;; esac
case "${'$'}f/" in */../*|*/./*) echo "AntiGem: not a plain path: ${'$'}f" >&2; exit 126;; esac
""" + SH_OK + """if ! ok "${'$'}f" "${'$'}@"; then
  if [ ! -e "${'$'}f" ]; then echo "AntiGem: ${'$'}f is gone (renamed or deleted?)" >&2; exit 127; fi
  echo "AntiGem: not running ${'$'}f: its real file (${'$'}(realpath -- "${'$'}f" 2>/dev/null)) must be in your home, /usr or /opt, and not in a shared Android folder" >&2
  exit 126
fi
cd "${'$'}HOME" 2>/dev/null || cd /
m=${'$'}(head -c 4 -- "${'$'}r" 2>/dev/null | od -An -tx1 | tr -d ' \n')
if [ "${'$'}m" = 7f454c46 ]; then
  [ -x "${'$'}r" ] || { echo "AntiGem: ${'$'}r is a program without the execute bit (chmod +x it)" >&2; exit 126; }
  exec "${'$'}r"
fi
l=${'$'}(head -n 1 -- "${'$'}r" 2>/dev/null | tr -d '\r')
case "${'$'}l" in "#!"*) exec ${'$'}{l#??} "${'$'}r";; esac
exec bash "${'$'}r"
"""

    /** A shortcut's icon of ~/.shortcuts/icons, base64 (a file in the home, at most 1 MB). */
    private const val SH_ICON = """p=${'$'}1
case "${'$'}p" in "${'$'}HOME"/.shortcuts/icons/*) ;; *) exit 2;; esac
r=${'$'}(realpath -e -- "${'$'}p" 2>/dev/null) || exit 0
case "${'$'}r" in "${'$'}HOME"/*) ;; *) exit 0;; esac
[ -f "${'$'}r" ] && [ "${'$'}(stat -c %s -- "${'$'}r")" -le $ICON_MAX_BYTES ] && { printf 'ICON:'; base64 -w0 -- "${'$'}r"; echo; }
"""

    /** The picker: the folders and files of `$1` (in the home), `d\tname` / `f\tname`. */
    private const val SH_BROWSE = """d=${'$'}1
case "${'$'}d" in "${'$'}HOME"|"${'$'}HOME"/*) ;; *) echo OUTSIDE; exit 2;; esac
cd -- "${'$'}d" 2>/dev/null || { echo NODIR; exit 3; }
find -L . -mindepth 1 -maxdepth 1 \( -type d -printf 'd\t%f\n' \) -o \( -type f -printf 'f\t%f\n' \) 2>/dev/null
"""

    /** `$1` → a symlink `$2` in ~/.shortcuts, when `$1` may run (shared folders follow). */
    private const val SH_ADD = """t=${'$'}1; l=${'$'}2; shift 2
case "${'$'}l" in "${'$'}HOME"/.shortcuts/*) ;; *) exit 2;; esac
""" + SH_OK + """ok "${'$'}t" "${'$'}@" || { echo REFUSED; exit 4; }
if [ -e "${'$'}l" ] || [ -L "${'$'}l" ]; then echo EXISTS; exit 3; fi
mkdir -p -- "${'$'}{l%/*}" && ln -s -- "${'$'}t" "${'$'}l" && echo ADDED
"""

    /** Removes the symlink `$1` of ~/.shortcuts (never a real file). */
    private const val SH_REMOVE = """l=${'$'}1
case "${'$'}l" in "${'$'}HOME"/.shortcuts/*) ;; *) exit 2;; esac
[ -L "${'$'}l" ] && rm -f -- "${'$'}l" && echo REMOVED
"""

    private const val SH_FOLDERS = """mkdir -p "${'$'}HOME/.shortcuts/tasks" "${'$'}HOME/.shortcuts/icons" && chmod 700 "${'$'}HOME/.shortcuts" && echo MADE"""

    /** [script] run by the chroot's /bin/sh with [args] (the user's own shell may be zsh or fish). */
    private fun sh(script: String, args: List<String>): String =
        "/bin/sh -c " + quote(script) + " antigem" + args.joinToString("") { " " + quote(it) }

    /**
     * The folders mounted into the chroot (their paths inside), which scripts may not run from: the shared folders of
     * its settings, and whatever is mounted in it now (a folder taken out of the settings stays mounted until the
     * chroot restarts). Null when the chroot's state can't be read: then nothing runs. Only a folder of the app's own
     * storage reached without symlinks ([privateSource], e.g. AntiGem's home as ~/antigem_home) stays allowed.
     */
    private suspend fun sharedFolders(context: Context, install: ChrootInstall): List<String>? {
        val known = ChrootManager.status.value?.takeIf { it.path == install.path && it.error == null }
        val st = known ?: ChrootManager.refreshStatus(context, install)
        if (st == null || st.error != null || st.path != install.path) return null
        val private = st.config.binds.filter { privateSource(context, it.source) }.map { it.target.trimEnd('/') }.toSet()
        val configured = st.config.binds.map { it.target.trimEnd('/') }
        val root = install.path.trimEnd('/')
        val live = st.mounts.mapNotNull { m -> m.mountPoint.takeIf { it.startsWith("$root/") }?.removePrefix(root) }
        return (configured + live).map { it.trimEnd('/') }
            .filter { it.startsWith("/") && it != "/" && it !in private }
            .distinct()
    }

    /**
     * [source] (a shared folder's phone path) is a folder of the app's own storage with no symlink on the way below it.
     * Its name alone is not enough: root mounts it following symlinks, and a symlink there could lead to /data/media
     * (the phone's shared storage, on the same disk as the rootfs, so the disk check would not catch it).
     */
    private fun privateSource(context: Context, source: String): Boolean {
        val src = source.trimEnd('/')
        if (src.split('/').any { it == "." || it == ".." }) return false
        return runCatching {
            val dataDir = context.applicationInfo.dataDir.trimEnd('/')
            val roots = listOf(dataDir, File(dataDir).canonicalPath.trimEnd('/')).distinct()
            val root = roots.firstOrNull { src.startsWith("$it/") } ?: return false
            var p = root
            for (part in src.removePrefix("$root/").split('/').filter { it.isNotEmpty() }) {
                p = "$p/$part"
                if (OsConstants.S_ISLNK(Os.lstat(p).st_mode)) return false
            }
            OsConstants.S_ISDIR(Os.lstat(p).st_mode)
        }.getOrDefault(false)
    }

    private suspend fun readChroot(context: Context, install: ChrootInstall): Listing {
        val shared = sharedFolders(context, install)
            ?: return Listing(emptyList(), chroot = true, hasFolder = false, home = null, error = statusError())
        val r = ChrootSetup.asUser(context, install, sh(SH_LIST, shared), timeoutMs = 30_000L)
        val home = r.out.firstOrNull { it.startsWith("HOME\t") }?.substringAfter('\t')
        if (!r.ok || home == null) {
            return Listing(emptyList(), chroot = true, hasFolder = false, home = null, error = r.message())
        }
        val prefix = "$home/.shortcuts/"
        val scripts = r.out.mapNotNull { line ->
            val kind = line.substringBefore('\t', "")
            val path = line.substringAfter('\t', "")
            if ((kind == "F" || kind == "L") && path.startsWith(prefix)) path to (kind == "L") else null
        }.sortedWith { a, b -> compareRel(a.first.removePrefix(prefix), b.first.removePrefix(prefix)) }
            .map { (p, link) -> toScript(prefix, p, chroot = true, link = link) }
        return Listing(scripts, chroot = true, hasFolder = r.out.none { it == "NODIR" }, home = home)
    }

    private fun statusError(): String = ChrootManager.status.value?.error ?: "the chroot's settings could not be read"

    private fun cacheFile(context: Context) = File(context.applicationContext.filesDir, CACHE_FILE)

    @Volatile private var cache: Pair<String, Listing>? = null

    private fun saveCache(context: Context, install: ChrootInstall, l: Listing) {
        cache = install.path to l
        runCatching {
            val o = JSONObject()
                .put("install", install.path)
                .put("hasFolder", l.hasFolder)
                .put("home", l.home ?: JSONObject.NULL)
                .put("error", l.error ?: JSONObject.NULL)
                .put("scripts", JSONArray().apply {
                    l.scripts.forEach { s -> put(JSONObject().put("p", s.path).put("bg", s.background).put("label", s.label).put("link", s.link)) }
                })
            val f = cacheFile(context)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    private fun chrootCached(context: Context, install: ChrootInstall): Listing {
        cache?.let { (path, l) -> if (path == install.path) return l }
        val fresh = Listing(emptyList(), chroot = true, hasFolder = false, home = null)
        val l = runCatching {
            val o = JSONObject(cacheFile(context).readText())
            if (o.optString("install") != install.path) return fresh
            val arr = o.optJSONArray("scripts") ?: JSONArray()
            Listing(
                scripts = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    Script(s.getString("p"), s.optBoolean("bg"), s.optString("label"), chroot = true, link = s.optBoolean("link"))
                },
                chroot = true,
                hasFolder = o.optBoolean("hasFolder"),
                home = o.optString("home").takeIf { !o.isNull("home") && it.isNotBlank() },
                error = o.optString("error").takeIf { !o.isNull("error") && it.isNotBlank() }
            )
        }.getOrDefault(fresh)
        cache = install.path to l
        return l
    }

    // ------------------------------------------------------------------ running

    private fun remoteTerminal(): Boolean = AuthPreferences.remoteShell

    /** The script's runtime is the phone's runtime now, else says which one to switch to. */
    private fun sameRuntime(context: Context, target: HomeLinks.Target.Script): Boolean {
        val chrootNow = chrootInstall(context) != null
        if (target.chroot == chrootNow) return true
        toast(
            context,
            if (target.chroot) L.homescreen.homeScripts.thisShortcutRunsInTheUbuntuChroot
            else L.homescreen.homeScripts.thisShortcutRunsInAntigemsOwnTerminal
        )
        return false
    }

    /** A new terminal tab running the script; the terminal page comes up. */
    fun runInTerminal(activity: ComponentActivity, vm: ChatViewModel, target: HomeLinks.Target.Script) {
        val name = target.path.substringAfterLast('/')
        activity.lifecycleScope.launch {
            if (remoteTerminal() || vm.useSshTerminal.first()) {
                toast(activity, L.homescreen.homeScripts.scriptsRunInThisPhonesTerminal)
                return@launch
            }
            if (!sameRuntime(activity, target)) return@launch
            if (!LocalEnvironmentManager.isInstalled(activity)) {
                toast(activity, L.homescreen.homeScripts.setUpTheTerminalFirstOpen)
                return@launch
            }
            val install = chrootInstall(activity)
            val workDir: String?
            val command: String
            if (install != null) {
                val shared = sharedFolders(activity, install)
                if (shared == null) {
                    toast(activity, L.homescreen.homeScripts.couldNotReadTheUbuntuChroot(statusError()))
                    return@launch
                }
                // checked (and its interpreter picked) inside the chroot; the login shell stays open after it
                workDir = null
                command = sh(SH_RUN, listOf(target.path) + shared)
            } else {
                val real = termuxReal(activity, target.path) ?: return@launch
                workDir = LocalEnvironmentManager.getHomeDir(activity).absolutePath
                command = command(real)
            }
            if (!vm.isLocalToolsEnabled.first()) vm.setLocalToolsEnabled(true)
            // the terminal's own tabs first, so the new one is not replaced while they are restored
            LocalTerminalManager.getOrCreateOrRestoreSessions(activity)
            LocalTerminalManager.createNewSession(
                activity.applicationContext,
                workingDir = workDir,
                initialCommand = command,
                sessionTitle = name.substringBeforeLast('.').ifBlank { name }
            )
            vm.requestViewMode("TERMINAL")
        }
    }

    /** Runs the script without a terminal (on the Run page) and says when it is done. Main thread. */
    fun runInBackground(context: Context, target: HomeLinks.Target.Script) {
        val app = context.applicationContext
        val name = target.path.substringAfterLast('/')
        if (remoteTerminal()) {
            toast(app, L.homescreen.homeScripts.scriptsRunOnThisPhoneTurn)
            return
        }
        if (!sameRuntime(app, target)) return
        if (!LocalEnvironmentManager.isInstalled(app)) {
            toast(app, L.homescreen.homeScripts.setUpTheTerminalFirstOpen)
            return
        }
        scope.launch {
            val install = chrootInstall(app)
            val folder: String
            val command: String
            if (install != null) {
                val shared = sharedFolders(app, install)
                if (shared == null) {
                    toast(app, L.homescreen.homeScripts.couldNotReadTheUbuntuChroot(statusError()))
                    return@launch
                }
                folder = chrootCached(app, install).home ?: ""
                command = sh(SH_RUN, listOf(target.path) + shared)
            } else {
                val real = termuxReal(app, target.path) ?: return@launch
                folder = LocalEnvironmentManager.getHomeDir(app).absolutePath
                command = command(real)
            }
            // the app's service keeps the process (and the script) alive in the background
            runCatching { ai.antigem.data.service.AntiGemService.start(app) }
            val run = runCatching {
                RunManager.start(app, name = name, command = command, folder = folder, origin = "home")
            }.getOrElse {
                toast(app, L.homescreen.homeScripts.couldNotStartNameMessage(name, it.message))
                return@launch
            }
            toast(app, L.homescreen.homeScripts.runningName(name))
            val end = RunManager.runs.map { list -> list.firstOrNull { it.id == run.id } }
                .first { it == null || it.status != RunStatus.RUNNING } ?: return@launch
            notifyDone(app, end.id, name, end.status, end.exitCode)
        }
    }

    // ------------------------------------------------------------------ managing ~/.shortcuts

    /** Makes ~/.shortcuts with its tasks and icons folders. */
    suspend fun ensureFolders(context: Context): Boolean {
        val app = context.applicationContext
        val install = chrootInstall(app)
        val ok = withContext(Dispatchers.IO) {
            if (install != null) {
                ChrootSetup.asUser(app, install, SH_FOLDERS).out.any { it == "MADE" }
            } else runCatching {
                File(dir(app), "tasks").mkdirs()
                File(dir(app), "icons").mkdirs()
                dir(app).isDirectory
            }.getOrDefault(false)
        }
        refresh(app)
        return ok
    }

    /** The folders and files of [path] for the picker ([path] null: the home). */
    suspend fun browse(context: Context, path: String?): Browse = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val install = chrootInstall(app)
        if (install != null) {
            val home = chrootCached(app, install).home ?: refresh(app).home
                ?: return@withContext Browse(path ?: "", emptyList(), statusError())
            val d = path ?: home
            val r = ChrootSetup.asUser(app, install, sh(SH_BROWSE, listOf(d)), timeoutMs = 30_000L)
            if (!r.ok) return@withContext Browse(d, emptyList(), r.message())
            val entries = r.out.mapNotNull { line ->
                val kind = line.substringBefore('\t', "")
                val name = line.substringAfter('\t', "")
                if (name.isBlank() || (kind != "d" && kind != "f")) null else Entry(name, kind == "d")
            }
            Browse(d, sortEntries(entries))
        } else {
            val d = File(path ?: LocalEnvironmentManager.getHomeDir(app).absolutePath)
            val entries = d.listFiles().orEmpty().mapNotNull { f ->
                when {
                    f.isDirectory -> Entry(f.name, true)
                    f.isFile -> Entry(f.name, false)
                    else -> null
                }
            }
            Browse(d.absolutePath, sortEntries(entries))
        }
    }

    private fun sortEntries(list: List<Entry>) = list.sortedWith { a, b -> if (a.dir != b.dir) (if (a.dir) -1 else 1) else natural(a.name, b.name) }

    /** The home the picker starts in (and may not leave). */
    fun pickerHome(context: Context): String? {
        val install = chrootInstall(context)
        return if (install != null) chrootCached(context, install).home else LocalEnvironmentManager.getHomeDir(context).absolutePath
    }

    /** A name for a link in ~/.shortcuts: no "/", not hidden, not ".". */
    fun isGoodName(name: String): Boolean =
        name.isNotBlank() && name.length <= 100 && !name.startsWith(".") && name.none { it == '/' || it.code < 32 }

    /**
     * A symlink in ~/.shortcuts (or ~/.shortcuts/tasks when [background]) named [name] to the script [target] of the
     * home. Null when it was added, else why not.
     */
    suspend fun addLink(context: Context, target: String, name: String, background: Boolean): String? {
        val app = context.applicationContext
        if (!isGoodName(name)) return L.homescreen.settings.useANameWithoutThatDoesnt
        val install = chrootInstall(app)
        val result = withContext(Dispatchers.IO) {
            if (install != null) {
                val home = chrootCached(app, install).home ?: return@withContext statusError()
                val shared = sharedFolders(app, install) ?: return@withContext statusError()
                val link = "$home/.shortcuts/" + (if (background) "tasks/" else "") + name
                val r = ChrootSetup.asUser(app, install, sh(SH_ADD, listOf(target, link) + shared))
                when {
                    r.out.any { it == "ADDED" } -> null
                    r.out.any { it == "EXISTS" } -> L.homescreen.settings.thereIsAlreadyANameIn(name)
                    r.out.any { it == "REFUSED" } -> L.homescreen.settings.thatFileIsOnSharedStorage
                    else -> r.message()
                }
            } else {
                val t = File(target)
                if (allowedReal(app, t) == null) return@withContext L.homescreen.settings.thatFileIsOnSharedStorage
                val parent = if (background) File(dir(app), "tasks") else dir(app)
                val link = File(parent, name)
                if (link.exists() || isLink(link.absolutePath)) return@withContext L.homescreen.settings.thereIsAlreadyANameIn(name)
                runCatching {
                    parent.mkdirs()
                    Os.symlink(t.absolutePath, link.absolutePath)
                    null
                }.getOrElse { it.message ?: it.toString() }
            }
        }
        refresh(app)
        return result
    }

    /** Removes [script]'s symlink from ~/.shortcuts (the script it points to stays). */
    suspend fun removeLink(context: Context, script: Script): Boolean {
        if (!script.link) return false
        val app = context.applicationContext
        val install = chrootInstall(app)
        val ok = withContext(Dispatchers.IO) {
            if (install != null && script.chroot) {
                ChrootSetup.asUser(app, install, sh(SH_REMOVE, listOf(script.path))).out.any { it == "REMOVED" }
            } else if (install == null && !script.chroot) {
                inShortcuts(dir(app).absolutePath, script.path) && isLink(script.path) && File(script.path).delete()
            } else false
        }
        refresh(app)
        return ok
    }

    /**
     * The custom icon of [script]'s shortcut: ~/.shortcuts/icons/<name>.png, as in Termux:Widget (null: none, or not a
     * picture). In the chroot it is read as the chroot's user.
     */
    suspend fun icon(context: Context, script: Script): Bitmap? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val bytes: ByteArray? = runCatching {
            if (script.chroot) {
                val install = chrootInstall(app) ?: return@runCatching null
                val home = chrootCached(app, install).home ?: return@runCatching null
                val r = ChrootSetup.asUser(app, install, sh(SH_ICON, listOf("$home/.shortcuts/icons/${script.name}.png")))
                r.out.firstOrNull { it.startsWith("ICON:") }?.removePrefix("ICON:")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { Base64.decode(it, Base64.DEFAULT) }
            } else {
                val f = File(File(dir(app), "icons"), script.name + ".png")
                val real = runCatching { f.canonicalFile }.getOrNull()
                val home = LocalEnvironmentManager.getHomeDir(app).canonicalPath + File.separator
                if (real == null || !real.isFile || !real.path.startsWith(home) || real.length() > ICON_MAX_BYTES) null
                else real.readBytes()
            }
        }.getOrNull()
        bytes ?: return@withContext null
        // the size first: a small file can still hold a picture of 20000×20000 (1.6 GB once decoded)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 384) sample *= 2
        val bmp = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return@withContext null
        val max = 192
        if (bmp.width <= max && bmp.height <= max) bmp
        else {
            val s = max.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        }
    }

    // ------------------------------------------------------------------ the end of a background script

    private fun notifyDone(context: Context, runId: String, name: String, status: RunStatus, exit: Int?) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Home screen scripts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = L.homescreen.homeScripts.whenAScriptStartedFromThe
                    })
                }
            }
            val ok = status == RunStatus.SUCCEEDED
            val title = when (status) {
                RunStatus.SUCCEEDED -> "✓ $name finished"
                RunStatus.STOPPED -> L.homescreen.homeScripts.nameWasStopped(name)
                else -> "✗ $name failed" + (exit?.let { " (exit $it)" } ?: "")
            }
            val tail = runCatching { RunManager.recentOutput(runId, 4) }.getOrDefault("").trim()
            val open = PendingIntent.getActivity(
                context, runId.hashCode(), HomeLinks.intent(context, HomeLinks.Target.RunEntry(runId)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(if (ok) R.drawable.ic_home_check else R.drawable.ic_home_task)
                .setContentTitle(title)
                .setContentText(tail.lineSequence().lastOrNull { it.isNotBlank() } ?: L.homescreen.homeScripts.tapToSeeTheOutput)
                .setStyle(NotificationCompat.BigTextStyle().bigText(tail.ifBlank { L.homescreen.homeScripts.tapToSeeTheOutput }))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(runId.hashCode(), n)
        }
    }

    private fun toast(context: Context, msg: String) {
        val app = context.applicationContext
        // background callers (the widget's tap, a finished check) are not always on the main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
    }
}
