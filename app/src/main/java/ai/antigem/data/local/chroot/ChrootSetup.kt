package ai.antigem.data.local.chroot

import ai.antigem.data.preferences.AuthPreferences
import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File

/**
 * The plugin setups (REA, Android development) and adb in AntiGem's Ubuntu chroot.
 *
 * In AntiGem's own environment (Termux) their scripts run in the terminal and install into a proot-distro container.
 * When the chroot is this phone's runtime, the agents run in it, so the tools must be there: the same scripts run
 * inside the chroot as root with ANTIGEM_RUNTIME=chroot and install into the chroot itself (tools in /opt/antigem,
 * commands in /usr/local/bin, the user's settings in their home). The app cannot read the chroot's files (SELinux keeps
 * apps out of /data/local), so it copies the setup files in through root, asks the scripts what is installed
 * (`status`), and runs commands as the chroot's user for everything that belongs to them (adb, its keys).
 */
object ChrootSetup {
    /** Where the scripts put the tools (shared by every user of the chroot). */
    const val OPT = "/opt/antigem"

    /** Where the setup files of [name] (antigem-rea, antigem-android) go in the chroot. */
    fun shareDir(name: String) = "$OPT/share/$name"

    /**
     * The chroot the plugins run in: the phone's runtime when it is the Ubuntu chroot, null in AntiGem's own
     * environment and in server mode.
     */
    fun install(): ChrootInstall? =
        // the active install alone is not enough: it stays selected when the user switches back to AntiGem's environment
        if (AuthPreferences.currentRemoteMode || !ChrootManager.isActiveNow()) null else ChrootManager.activeInstallNow()

    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun execArgs(install: ChrootInstall, asUser: Boolean, script: String): List<String> =
        listOf("exec", "--root", install.path) +
            (if (asUser) listOf("--user", install.user) else listOf("--as-root")) +
            listOf("--term", "dumb", "--cmd", ChrootManager.b64(script))

    /**
     * Copies [files] of [localDir] (the downloaded setup files) into [destDir] of the chroot: a tar stream through
     * root. Files that [executable] names get mode 755, the others 644.
     */
    suspend fun copyIn(
        context: Context,
        install: ChrootInstall,
        localDir: File,
        files: List<String>,
        destDir: String,
        executable: (String) -> Boolean
    ): ChrootManager.SuResult {
        val present = files.filter { File(localDir, it).isFile }
        val script = "set -e; mkdir -p ${quote(destDir)}; tar -xf - -C ${quote(destDir)} --no-same-owner; " +
            "chmod 755 $OPT $OPT/share ${quote(destDir)}"
        return ChrootManager.runCtl(context, execArgs(install, asUser = false, script = script), stdin = { out ->
            TarArchiveOutputStream(out).use { tar ->
                for (name in present) {
                    val f = File(localDir, name)
                    val entry = TarArchiveEntry(name)
                    entry.size = f.length()
                    entry.mode = if (executable(name)) "100755".toInt(8) else "100644".toInt(8)
                    tar.putArchiveEntry(entry)
                    f.inputStream().use { it.copyTo(tar) }
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        })
    }

    /**
     * Runs `bash <script> <args>` in [dir] of the chroot as root (ANTIGEM_RUNTIME=chroot, ANTIGEM_USER = the chroot's
     * user), its output (stdout and stderr) going to [onLine]. It runs in its own session, whose id is written to
     * <dir>/.task.pid for [stopScript].
     */
    suspend fun runScript(
        context: Context,
        install: ChrootInstall,
        dir: String,
        script: String,
        args: List<String>,
        timeoutMs: Long,
        onLine: (String) -> Unit
    ): ChrootManager.SuResult {
        val argText = args.joinToString(" ") { quote(it) }
        // the pid file goes when the script ends: a stale one must never point Stop at a process that reused the id
        val command = "cd ${quote(dir)} || exit 1\n" +
            "setsid -w /usr/bin/env ANTIGEM_RUNTIME=chroot ANTIGEM_USER=${quote(install.user)} " +
            "/bin/bash -c 'echo \$\$ > .task.pid; exec /bin/bash ./${script} \"\$@\"' _ $argText < /dev/null 2>&1\n" +
            "rc=\$?; rm -f .task.pid; exit \$rc"
        return ChrootManager.runCtl(context, execArgs(install, asUser = false, script = command), onLine = onLine, timeoutMs = timeoutMs)
    }

    /** Stops what [runScript] started in [dir]: its whole session (apt, downloads, builds). */
    suspend fun stopScript(context: Context, install: ChrootInstall, dir: String): ChrootManager.SuResult {
        val pid = quote("$dir/.task.pid")
        // bash: its kill takes a process group (-pgid); root commands otherwise run in Ubuntu's /bin/sh (dash). Only a
        // process that still is a setup script (its command line names the .sh) is stopped.
        val bash = "p=\$(cat $pid 2>/dev/null); [ -n \"\$p\" ] || exit 0; " +
            "tr '\\0' ' ' < /proc/\$p/cmdline 2>/dev/null | grep -q -- '-setup\\.sh' || { rm -f $pid; exit 0; }; " +
            "kill -TERM -- -\$p 2>/dev/null; sleep 2; kill -KILL -- -\$p 2>/dev/null; rm -f $pid; true"
        return ChrootManager.runCtl(context, execArgs(install, asUser = false, script = "exec /bin/bash -c ${quote(bash)}"), timeoutMs = 30_000L)
    }

    /**
     * `bash <script> status` in [dir] of the chroot: its key=value lines, or null when the script is not in the chroot
     * (the setup files were never copied there) or the chroot could not be reached.
     */
    suspend fun status(context: Context, install: ChrootInstall, dir: String, script: String): Map<String, String>? {
        val command = "cd ${quote(dir)} 2>/dev/null && [ -f ./$script ] || exit 3\n" +
            "ANTIGEM_RUNTIME=chroot ANTIGEM_USER=${quote(install.user)} exec /bin/bash ./$script status 2>/dev/null"
        val r = ChrootManager.runCtl(context, execArgs(install, asUser = false, script = command), timeoutMs = 30_000L)
        if (!r.ok) return null
        return r.out.mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
        }.toMap()
    }

    /** Runs [command] (bash) as the chroot's user, in their home; [stdin] is written to its input when given. */
    suspend fun asUser(
        context: Context,
        install: ChrootInstall,
        command: String,
        stdin: ByteArray? = null,
        timeoutMs: Long = 60_000L,
        onLine: ((String) -> Unit)? = null
    ): ChrootManager.SuResult =
        ChrootManager.runCtl(
            context, execArgs(install, asUser = true, script = command),
            stdin = stdin?.let { bytes -> { out -> out.write(bytes) } },
            onLine = onLine, timeoutMs = timeoutMs
        )

    /** Runs [command] (bash) as root in the chroot. */
    suspend fun asRoot(
        context: Context,
        install: ChrootInstall,
        command: String,
        timeoutMs: Long = 60_000L,
        onLine: ((String) -> Unit)? = null
    ): ChrootManager.SuResult =
        ChrootManager.runCtl(context, execArgs(install, asUser = false, script = command), onLine = onLine, timeoutMs = timeoutMs)
}
