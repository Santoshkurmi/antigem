package ai.antigem.data.local

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import ai.antigem.R
import ai.antigem.data.preferences.AuthPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class LocalCommandResult(
    val exitCode: Int,
    val output: String,
    val durationMs: Long
)

data class DeviceHardwareInfo(
    val arch: String,
    val vaBits: Int?, // 39, 48, or null if unknown (N/A)
    val hasAtomics: Boolean?, // true, false, or null if unknown (N/A)
    val supportedAbis: List<String>
)

/** Where the full rootfs zip is downloaded from (the same file on both). */
enum class RootfsMirror(val label: String) {
    GITHUB("GitHub"),
    HUGGING_FACE("Hugging Face")
}

sealed class BootstrapSource {
    /** The full rootfs from [mirror]. `BootstrapSource.Auto` on its own is the companion: GitHub. */
    open class Auto(val mirror: RootfsMirror = RootfsMirror.GITHUB) : BootstrapSource() {
        companion object Default : Auto()
    }
    object BaseMinimum : BootstrapSource()
    data class DirectUrl(val url: String) : BootstrapSource()
    data class LocalZipUri(val uri: android.net.Uri) : BootstrapSource()
}

object LocalEnvironmentManager {

    private const val TAG = "LocalEnvironmentManager"
    private const val BOOTSTRAP_VERSION = "2026.08"
    // Minimum 10 MB required for a real Termux bootstrap archive
    private const val MIN_BOOTSTRAP_SIZE_BYTES = 10 * 1024 * 1024L

    /** The full rootfs is per CPU: arm64 (GitHub + Hugging Face) and x86_64 (GitHub only for now), both in rootfs-v2. */
    private val IS_X86_64: Boolean = ai.antigem.BuildConfig.TARGET_ABI == "x86_64"
    const val HARDCODED_ROOTFS_TAG = "rootfs-v2"
    val HARDCODED_ROOTFS_ASSET: String = if (IS_X86_64) "bootrapz_x86_64_2026-11-20.zip" else "bootrapz_2026-11-19.zip"
    val HARDCODED_ROOTFS_URL: String = "https://github.com/Santoshkurmi/antigem/releases/download/$HARDCODED_ROOTFS_TAG/$HARDCODED_ROOTFS_ASSET"
    val HARDCODED_ROOTFS_SIZE_BYTES: Long = if (IS_X86_64) 419_473_812L else 400_054_861L
    /** The download size the installers show for "Full environment". */
    val ROOTFS_SIZE_LABEL: String = if (IS_X86_64) "~400 MB" else "~350 MB"
    /** The mirrors that host this CPU's rootfs: the x86_64 one is only on GitHub. */
    val ROOTFS_MIRRORS: List<RootfsMirror> = if (IS_X86_64) listOf(RootfsMirror.GITHUB) else RootfsMirror.values().toList()
    const val HUGGINGFACE_ROOTFS_URL = "https://huggingface.co/datasets/santoshkurmi/rootfs-v2/resolve/main/bootrapz_2026-11-19.zip"
    // pages where the user can download the same zip by hand (then install it with "From a ZIP file")
    const val GITHUB_ROOTFS_PAGE = "https://github.com/Santoshkurmi/antigem/releases/tag/rootfs-v2"
    const val HUGGINGFACE_ROOTFS_PAGE = "https://huggingface.co/datasets/santoshkurmi/rootfs-v2/tree/main"

    // a mirror without this CPU's rootfs (Hugging Face on x86_64) falls back to GitHub
    private fun useHuggingFace(mirror: RootfsMirror) = mirror == RootfsMirror.HUGGING_FACE && mirror in ROOTFS_MIRRORS

    fun rootfsUrl(mirror: RootfsMirror): String = if (useHuggingFace(mirror)) HUGGINGFACE_ROOTFS_URL else HARDCODED_ROOTFS_URL

    fun rootfsPage(mirror: RootfsMirror): String = if (useHuggingFace(mirror)) HUGGINGFACE_ROOTFS_PAGE else GITHUB_ROOTFS_PAGE

    private const val NOTIFICATION_CHANNEL_ID = "antigem_bootstrap_install"
    private const val NOTIFICATION_ID = 4096
    @Volatile
    private var lastNotificationTime = 0L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val _installerState = MutableStateFlow<LocalInstallerState>(LocalInstallerState.Idle)
    val installerState: StateFlow<LocalInstallerState> = _installerState.asStateFlow()

    private val _installerLogs = MutableStateFlow<List<String>>(emptyList())
    val installerLogs: StateFlow<List<String>> = _installerLogs.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun setupInstallNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Rootfs Installation",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress while downloading and installing the Linux rootfs"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun updateInstallNotification(
        context: Context,
        title: String,
        content: String,
        progress: Int, // 0..100, -1 for indeterminate, -2 to cancel
        ongoing: Boolean = true,
        force: Boolean = false
    ) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            if (progress == -2) {
                notificationManager.cancel(NOTIFICATION_ID)
                return
            }

            val now = System.currentTimeMillis()
            if (!force && now - lastNotificationTime < 1000) {
                return
            }
            lastNotificationTime = now

            setupInstallNotificationChannel(context)

            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (progress in 0..100) {
                builder.setProgress(100, progress, false)
            } else if (progress == -1) {
                builder.setProgress(0, 0, true)
            }

            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating install notification: ${e.message}")
        }
    }

    fun log(message: String) {
        Log.d(TAG, message)
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _installerLogs.value = (_installerLogs.value + "[$time] $message").takeLast(600)
    }

    fun clearLogs() {
        _installerLogs.value = emptyList()
    }

    fun getPrefixDir(context: Context): File = File(context.filesDir, "usr")
    fun getBinDir(context: Context): File = File(getPrefixDir(context), "bin")
    fun getLibDir(context: Context): File = File(getPrefixDir(context), "lib")
    fun getEtcDir(context: Context): File = File(getPrefixDir(context), "etc")
    fun getTmpDir(context: Context): File = File(getPrefixDir(context), "tmp")
    fun getHomeDir(context: Context): File = File(context.filesDir, "home")
    fun getProjectsDir(context: Context): File = File(getHomeDir(context), "projects")
    /** The phone's runtime is installed: the Ubuntu chroot when it is the chosen one, else the Termux environment. */
    fun isInstalled(context: Context): Boolean {
        if (AuthPreferences(context).isChrootModeSync()) {
            return ai.antigem.data.local.chroot.ChrootManager.activeInstall(context) != null
        }
        return isTermuxInstalled(context)
    }

    /** The Termux environment (bootstrap / rootfs in the app's files) is installed, whichever runtime is chosen. */
    fun isTermuxInstalled(context: Context): Boolean {
        val binDir = getBinDir(context)
        val homeDir = getHomeDir(context)
        return (binDir.exists() && binDir.isDirectory && (File(binDir, "sh").exists() || File(binDir, "dash").exists() || File(binDir, "bash").exists() || File(binDir, "busybox").exists())) &&
                homeDir.exists()
    }

    fun getInstallPath(context: Context): String = getPrefixDir(context).absolutePath

    fun getFormattedDiskSpace(context: Context): String {
        var totalBytes = 0L
        val prefix = getPrefixDir(context)
        val home = getHomeDir(context)
        if (prefix.exists()) totalBytes += calculateDirectorySize(prefix)
        if (home.exists()) totalBytes += calculateDirectorySize(home)
        return formatFileSize(totalBytes)
    }

    /** The app's Claude Code launcher: `$PREFIX/bin/claude` (the bridge is started with `--claude-bin` pointing here). */
    fun getClaudeWrapperFile(context: Context): File = File(getBinDir(context), "claude")

    /**
     * Writes the Claude Code launcher. The official binary is built for glibc, so it runs through the bundled glibc
     * loader. Claude's own installer and updater only manage `~/.local/share/claude/versions/<version>` and the
     * `~/.local/bin/claude` link, never `$PREFIX/bin`, so updates cannot break this launcher: it always starts the
     * newest version found. `~/.local/bin/claude` (first on the terminal PATH) is pointed back at the launcher whenever
     * an update replaced it. Called on every server start and new terminal; only rewrites what changed.
     */
    fun ensureClaudeWrapper(context: Context) {
        val authPrefs = AuthPreferences(context)
        if (authPrefs.isChrootModeSync()) return
        try {
            val prefix = getPrefixDir(context).absolutePath
            val home = getHomeDir(context).absolutePath
            val bin = getBinDir(context)
            if (!bin.exists()) return
            // x86_64: the seccomp shim (GLIBC_SIGSYS_SHIM); LD_AUDIT also reaches glibc programs Claude starts
            val shimLine = GLIBC_SIGSYS_SHIM?.let {
                "# x86_64: rewrites the syscalls Android blocks there for glibc programs (scripts/x86_64-glibc)\n" +
                    "export LD_AUDIT=\"${'$'}GLIBC_LIB/$it\"\n"
            } ?: ""
            val script = """
                |#!$prefix/bin/bash
                |# Claude Code launcher written by AntiGem on every start (edits are overwritten).
                |# The official Claude binary needs glibc: start the newest installed version through the bundled loader.
                |# Claude's updater only changes ~/.local/share/claude/versions and ~/.local/bin/claude, never this file.
                |VERSIONS_DIR="$home/.local/share/claude/versions"
                |GLIBC_LIB="$prefix/glibc/lib"
                |LOADER="${'$'}GLIBC_LIB/$GLIBC_LOADER"
                |TARGET=""
                |if [ -d "${'$'}VERSIONS_DIR" ]; then
                |    for v in ${'$'}(ls -1 "${'$'}VERSIONS_DIR" 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+${'$'}' | sort -V -r); do
                |        if [ -f "${'$'}VERSIONS_DIR/${'$'}v" ] && [ -s "${'$'}VERSIONS_DIR/${'$'}v" ]; then
                |            TARGET="${'$'}VERSIONS_DIR/${'$'}v"
                |            break
                |        fi
                |    done
                |fi
                |if [ -z "${'$'}TARGET" ]; then
                |    for f in "$prefix/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe" "$prefix/lib/node_modules/@anthropic-ai/claude-code/bin/claude"; do
                |        if [ -f "${'$'}f" ]; then
                |            TARGET="${'$'}f"
                |            break
                |        fi
                |    done
                |fi
                |if [ -z "${'$'}TARGET" ]; then
                |    echo "Claude Code is not installed. Install it in AntiGem: Settings > Claude Code > Account & CLI." >&2
                |    exit 127
                |fi
                |if [ ! -x "${'$'}LOADER" ]; then
                |    echo "The glibc runtime is missing (${'$'}LOADER). Reopen AntiGem to restore it." >&2
                |    exit 127
                |fi
                |# termux-exec is a bionic library: it must not be preloaded into the glibc process
                |unset LD_PRELOAD
                |${shimLine}exec "${'$'}LOADER" --library-path "${'$'}GLIBC_LIB" "${'$'}TARGET" "${'$'}@"
                |""".trimMargin()
            val wrapper = getClaudeWrapperFile(context)
            if (!wrapper.exists() || isSymlink(wrapper) || wrapper.readText() != script) {
                if (isSymlink(wrapper)) wrapper.delete()
                val tmp = File(bin, ".claude.tmp")
                tmp.writeText(script)
                tmp.setExecutable(true, false)
                tmp.setReadable(true, false)
                if (!tmp.renameTo(wrapper)) {
                    wrapper.delete()
                    tmp.renameTo(wrapper)
                }
                wrapper.setExecutable(true, false)
            }
            // the terminal finds ~/.local/bin first: keep `claude` there pointing at the launcher
            val localBin = File(home, ".local/bin")
            localBin.mkdirs()
            val link = File(localBin, "claude")
            val pointsToWrapper = try {
                Os.readlink(link.absolutePath) == wrapper.absolutePath
            } catch (_: Exception) {
                false
            }
            if (!pointsToWrapper) {
                // delete() also removes a (possibly dangling) symlink left by Claude's updater
                link.delete()
                Os.symlink(wrapper.absolutePath, link.absolutePath)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Claude launcher setup failed: ${e.message}")
        }
    }

    const val PREF_TERMUX_API_PROVIDER = "termux_api_provider"
    /** The termux-* commands are handled by AntiGem itself (the default). */
    const val TERMUX_API_PROVIDER_BUILTIN = "builtin"
    /** The termux-* commands all go to AntiGem's Termux:API app ([termuxApiAppPackage]). */
    const val TERMUX_API_PROVIDER_EXTERNAL = "external"
    private const val PREF_TERMUX_API_PACKAGE_INSTALLED = "termux_api_package_auto_installed"

    /** One of the two at a time: [TERMUX_API_PROVIDER_EXTERNAL], or else [TERMUX_API_PROVIDER_BUILTIN]. */
    fun getTermuxApiProvider(context: Context): String {
        val saved = try {
            context.getSharedPreferences("anti_gem_terminal_prefs", Context.MODE_PRIVATE).getString(PREF_TERMUX_API_PROVIDER, null)
        } catch (_: Exception) {
            null
        }
        return if (saved == TERMUX_API_PROVIDER_EXTERNAL) TERMUX_API_PROVIDER_EXTERNAL else TERMUX_API_PROVIDER_BUILTIN
    }

    fun setTermuxApiProvider(context: Context, provider: String) {
        try {
            val prefs = context.getSharedPreferences("anti_gem_terminal_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_TERMUX_API_PROVIDER, provider).apply()
            ensureTermuxApiDispatcher(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set Termux API provider: ${e.message}")
        }
    }

    /** AntiGem's Termux:API companion app: this app's package name + ".api", sharing its UID (sharedUserId). */
    fun termuxApiAppPackage(context: Context): String = "${context.packageName}.api"

    fun isTermuxApiAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(termuxApiAppPackage(context), 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Termux's termux-api package: the termux-* commands (termux-toast, termux-location, …). */
    fun isTermuxApiPackageInstalled(context: Context): Boolean =
        File(getPrefixDir(context), "var/lib/dpkg/info/termux-api.list").isFile

    /** The package's real binary was overwritten by older AntiGem versions, which wrote their dispatcher through its symlink. */
    private fun isTermuxApiPackageDamaged(context: Context): Boolean {
        val broadcast = File(getPrefixDir(context), "libexec/termux-api-broadcast")
        return broadcast.isFile && try {
            broadcast.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte() }
        } catch (_: Exception) { false }
    }

    /**
     * Writes AntiGem's dispatcher at libexec/termux-api, which every termux-* command runs. The termux-api package's
     * own one there (a symlink to termux-api-broadcast, which sends to the companion app) is moved to
     * termux-api.distrib with dpkg-divert, so neither overwrites the other: package updates go to .distrib, and
     * nothing is ever written through the symlink. The script holds the mode and whether the app is installed, so it
     * is rewritten (only when that changed) at terminal and server start, when the mode changes, and when the Settings
     * page sees the app installed or removed.
     */
    fun ensureTermuxApiDispatcher(context: Context) {
        try {
            val prefixDir = getPrefixDir(context)
            if (!File(prefixDir, "bin").isDirectory) return
            val dispatcher = File(prefixDir, "libexec/termux-api")
            dispatcher.parentFile?.mkdirs()
            // not diverted yet (dpkg busy): the package's symlink stays this time rather than being written through
            if (!TermuxRelocation.divertTermuxApi(context) && isSymlink(dispatcher)) return
            val script = termuxApiDispatcherScript(
                context.packageName,
                external = getTermuxApiProvider(context) == TERMUX_API_PROVIDER_EXTERNAL,
                appInstalled = isTermuxApiAppInstalled(context)
            )
            TermuxRelocation.writeIfChanged(dispatcher, script, executable = true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed ensuring termux-api dispatcher: ${e.message}")
        }
    }

    /**
     * Installs the termux-api package (the termux-* commands) when it is missing, once: a user who removes it later
     * keeps it removed. Repairs it when older AntiGem versions damaged it. In the background; needs the network
     * (without it, tried again at the next start).
     */
    fun ensureTermuxApiPackage(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("anti_gem_terminal_prefs", Context.MODE_PRIVATE)
        val installed = isTermuxApiPackageInstalled(appContext)
        val damaged = installed && isTermuxApiPackageDamaged(appContext)
        if (installed && !damaged) {
            if (!prefs.getBoolean(PREF_TERMUX_API_PACKAGE_INSTALLED, false)) prefs.edit().putBoolean(PREF_TERMUX_API_PACKAGE_INSTALLED, true).apply()
            return
        }
        if (!installed && prefs.getBoolean(PREF_TERMUX_API_PACKAGE_INSTALLED, false)) return // removed by the user
        if (termuxApiPackageJob?.isActive == true) return
        termuxApiPackageJob = managerScope.launch {
            val keep = "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
            val command = "apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q $keep " +
                (if (damaged) "--reinstall " else "") + "termux-api"
            val result = executeCommand(command = command, context = appContext, timeoutSeconds = 600)
            if (result.exitCode == 0 && isTermuxApiPackageInstalled(appContext)) {
                prefs.edit().putBoolean(PREF_TERMUX_API_PACKAGE_INSTALLED, true).apply()
                ensureTermuxApiDispatcher(appContext)
            } else {
                Log.w(TAG, "Installing termux-api failed (exit ${result.exitCode}): ${result.output.takeLast(500)}")
            }
        }
    }

    private var termuxApiPackageJob: Job? = null

    /**
     * The dispatcher (see [ensureTermuxApiDispatcher]): every call goes to the one handler of the chosen mode.
     * [external]: AntiGem's Termux:API app ([appInstalled]: whether it is installed now); else AntiGem's receiver.
     */
    internal fun termuxApiDispatcherScript(pkg: String, external: Boolean, appInstalled: Boolean): String {
        val prefix = "/data/data/$pkg/files/usr"
        val apiApp = "$pkg.api" // termuxApiAppPackage
        val builtin = ai.antigem.data.receiver.TermuxApiReceiver.BUILT_IN_METHODS.joinToString("|")
        val settings = "Settings → Termux Support → Termux:API"
        val header = """
            |#!$prefix/bin/bash
            |# Written by AntiGem (edits are overwritten). The termux-* commands (termux-api package) run this with an API
            |# method. $settings chooses who handles them: AntiGem itself (built-in) or AntiGem's Termux:API app.
            |""".trimMargin()
        if (external) {
            val route = if (appInstalled) """
                |# Mode: AntiGem's Termux:API app ($apiApp). Every call goes to it, through the termux-api package's own
                |# binary (termux-api.distrib: moved there with dpkg-divert, so it and this file never overwrite each other).
                |exec '$prefix/libexec/termux-api.distrib' "${'$'}@"
                |""" else """
                |# Mode: AntiGem's Termux:API app, which isn't installed (the app checks when it writes this file).
                |echo "termux-api: AntiGem's Termux:API app ($apiApp) isn't installed. Install it, or choose Built-in in $settings." >&2
                |exit 1
                |"""
            return header + "\n" + route.trimMargin() + "\n"
        }
        val body = """
            |# Mode: built-in. Only the methods AntiGem's receiver handles (TermuxApiReceiver.BUILT_IN_METHODS).
            |METHOD="${'$'}1"
            |case "${'$'}METHOD" in
            |    $builtin) ;;
            |    *)
            |        echo "termux-api: ${'$'}METHOD isn't built into AntiGem. Install AntiGem's Termux:API app and choose it in $settings." >&2
            |        exit 1
            |        ;;
            |esac
            |shift
            |
            |EXTRA_ARGS=()
            |if [ ! -t 0 ]; then
            |    INPUT=${'$'}(timeout 0.1 cat 2>/dev/null || true)
            |    if [ -n "${'$'}INPUT" ]; then
            |        EXTRA_ARGS=(--es text "${'$'}INPUT" --es message "${'$'}INPUT" --es content "${'$'}INPUT")
            |    fi
            |fi
            |
            |TMP_DIR="${'$'}{TMPDIR:-$prefix/tmp}"
            |mkdir -p "${'$'}TMP_DIR"
            |TMP_OUT="${'$'}TMP_DIR/api_out.${'$'}${'$'}"
            |
            |am broadcast --user ${'$'}(( ${'$'}(id -u) / 100000 )) \
            |    -a "$pkg.api" \
            |    -n "$pkg/ai.antigem.data.receiver.TermuxApiReceiver" \
            |    --es "api_method" "${'$'}METHOD" \
            |    --es "socket_output" "${'$'}TMP_OUT" \
            |    "${'$'}{EXTRA_ARGS[@]}" \
            |    "${'$'}@" > /dev/null 2>&1
            |
            |# only these answer (BatteryStatus, Clipboard without "set"): the receiver writes the answer to TMP_OUT after am
            |# returns, so it is waited for (up to 5 s); an empty clipboard is an empty file
            |answer=0
            |case "${'$'}METHOD" in
            |    BatteryStatus) answer=1 ;;
            |    Clipboard) case " ${'$'}* " in *" set "*) ;; *) answer=1 ;; esac ;;
            |esac
            |if [ "${'$'}answer" = 1 ]; then
            |    for i in ${'$'}(seq 1 50); do
            |        if [ -e "${'$'}TMP_OUT" ]; then
            |            sleep 0.05
            |            cat "${'$'}TMP_OUT"
            |            echo ""
            |            break
            |        fi
            |        sleep 0.1
            |    done
            |fi
            |
            |rm -f "${'$'}TMP_OUT"
            |""".trimMargin()
        return header + "\n" + body + "\n"
    }

    /** A 32-bit-only phone (no 64-bit ABI): the local Linux environment cannot run on it. */
    fun isUnsupported32BitDevice(): Boolean = Build.SUPPORTED_64_BIT_ABIS.isNullOrEmpty()

    /** The bundled glibc's loader, for the CPU this APK was built for (Gradle `-Pabi`, see app/build.gradle.kts). */
    val GLIBC_LOADER: String =
        if (ai.antigem.BuildConfig.TARGET_ABI == "x86_64") "ld-linux-x86-64.so.2" else "ld-linux-aarch64.so.1"

    /**
     * x86_64 only, in the same bundle: Android's x86_64 seccomp filter blocks syscalls glibc programs use there (access,
     * mkdir, rename…; arm64 has none of them), and this library, loaded with LD_AUDIT, rewrites them
     * (scripts/x86_64-glibc). The bridge loads it into OpenCode and agy the same way.
     */
    val GLIBC_SIGSYS_SHIM: String? = if (ai.antigem.BuildConfig.TARGET_ABI == "x86_64") "libantigem_sigsys.so" else null

    fun getBootstrapArch(): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        for (abi in abis) {
            when {
                abi.startsWith("arm64") || abi.equals("aarch64", ignoreCase = true) -> return "aarch64"
                abi.startsWith("armeabi") || abi.equals("arm", ignoreCase = true) -> return "arm"
                abi.contains("x86_64") -> return "x86_64"
                abi.contains("x86") || abi.equals("i686", ignoreCase = true) -> return "i686"
            }
        }
        return "aarch64"
    }

    fun getDeviceHardwareInfo(): DeviceHardwareInfo {
        val arch = getBootstrapArch()
        val abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList()

        var vaBits: Int? = null
        try {
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists() && mapsFile.canRead()) {
                var maxAddress = 0L
                var foundAny = false
                mapsFile.forEachLine { line ->
                    val range = line.substringBefore(' ')
                    val endHex = range.substringAfter('-')
                    val endAddr = endHex.toLongOrNull(16)
                    if (endAddr != null) {
                        foundAny = true
                        if (endAddr > maxAddress) {
                            maxAddress = endAddr
                        }
                    }
                }
                if (foundAny && maxAddress > 0) {
                    vaBits = if (maxAddress > (1L shl 39)) 48 else 39
                }
            }
        } catch (_: Exception) {
            vaBits = null
        }

        var hasAtomics: Boolean? = null
        try {
            val cpuinfo = File("/proc/cpuinfo")
            if (cpuinfo.exists() && cpuinfo.canRead()) {
                val content = cpuinfo.readText()
                if (content.isNotBlank()) {
                    hasAtomics = content.contains("atomics", ignoreCase = true)
                }
            }
        } catch (_: Exception) {
            hasAtomics = null
        }

        return DeviceHardwareInfo(
            arch = arch,
            vaBits = vaBits,
            hasAtomics = hasAtomics,
            supportedAbis = abis
        )
    }



    fun calculateDirectorySize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) calculateDirectorySize(f) else f.length()
        }
        return size
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val df = DecimalFormat("#,##0.#")
        return "${df.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    fun resetState() {
        _installerState.value = LocalInstallerState.Idle
    }

    suspend fun discoverBootstrapPackage(context: Context): DiscoveredPackageInfo? = withContext(Dispatchers.IO) {
        val info = DiscoveredPackageInfo(
            url = HARDCODED_ROOTFS_URL,
            releaseTag = HARDCODED_ROOTFS_TAG,
            assetName = HARDCODED_ROOTFS_ASSET,
            arch = getBootstrapArch(),
            sizeBytes = HARDCODED_ROOTFS_SIZE_BYTES,
            sizeFormatted = ROOTFS_SIZE_LABEL,
            packageName = context.packageName
        )
        _installerState.value = LocalInstallerState.AwaitingConfirmation(info)
        info
    }

    private fun resolveBootstrapUrls(arch: String): List<String> {
        val urls = mutableListOf<String>()

        // 1. Query GitHub API for latest release assets dynamically
        try {
            val apiRequest = Request.Builder()
                .url("https://api.github.com/repos/termux/termux-packages/releases/latest")
                .header("User-Agent", "GeminiApp-BootstrapInstaller/1.0")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            httpClient.newCall(apiRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val json = JSONObject(bodyString)
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.optString("name", "")
                                val downloadUrl = asset.optString("browser_download_url", "")
                                if (name == "bootstrap-$arch.zip" && downloadUrl.isNotBlank()) {
                                    urls.add(downloadUrl)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query GitHub API latest release: ${e.message}")
        }

        // 2. Query GitHub API releases list if latest didn't yield assets
        if (urls.isEmpty()) {
            try {
                val apiRequest = Request.Builder()
                    .url("https://api.github.com/repos/termux/termux-packages/releases?per_page=5")
                    .header("User-Agent", "GeminiApp-BootstrapInstaller/1.0")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                httpClient.newCall(apiRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string()
                        if (!bodyString.isNullOrBlank()) {
                            val releases = JSONArray(bodyString)
                            for (r in 0 until releases.length()) {
                                val releaseObj = releases.getJSONObject(r)
                                val assets = releaseObj.optJSONArray("assets") ?: continue
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    val name = asset.optString("name", "")
                                    val downloadUrl = asset.optString("browser_download_url", "")
                                    if (name == "bootstrap-$arch.zip" && downloadUrl.isNotBlank() && !urls.contains(downloadUrl)) {
                                        urls.add(downloadUrl)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query GitHub API releases list: ${e.message}")
            }
        }

        // 3. Fallback known weekly release tags
        val fallbackTags = listOf(
            "bootstrap-2026.08.30-r1+apt.android-7",
            "bootstrap-2026.08.23-r1+apt.android-7",
            "bootstrap-2026.08.16-r1+apt.android-7",
            "bootstrap-2026.08.09-r1+apt.android-7",
            "bootstrap-2026.08.02-r1+apt.android-7",
            "bootstrap-2026.07.26-r1+apt.android-7"
        )
        for (tag in fallbackTags) {
            val encodedTag = tag.replace("+", "%2B")
            val fallbackUrl = "https://github.com/termux/termux-packages/releases/download/$encodedTag/bootstrap-$arch.zip"
            if (!urls.contains(fallbackUrl)) {
                urls.add(fallbackUrl)
            }
        }

        return urls
    }

    suspend fun installLocalEnvironment(
        context: Context,
        authPreferences: AuthPreferences,
        requestedSource: BootstrapSource = BootstrapSource.Auto
    ): Boolean = withContext(Dispatchers.IO) {
        val source = requestedSource
        val appContext = context.applicationContext
        val prefixDir = getPrefixDir(appContext)
        val binDir = getBinDir(appContext)
        val libDir = getLibDir(appContext)
        val etcDir = getEtcDir(appContext)
        val tmpDir = getTmpDir(appContext)
        val homeDir = getHomeDir(appContext)
        val projectsDir = getProjectsDir(appContext)

        val arch = getBootstrapArch()

        clearLogs()
        log("Starting installation: arch=$arch, source=${source.javaClass.simpleName}")

        // Ensure a clean slate by clearing any previous partial/broken install
        resetEnvironment(appContext)

        try {
            Log.d(TAG, "Starting local bootstrap installation (arch: $arch, source: $source) at ${prefixDir.absolutePath}...")
            updateInstallNotification(appContext, "Installing Linux Rootfs", "Starting installation...", -1, force = true)

            // Ensure base directories exist
            tmpDir.mkdirs()
            homeDir.mkdirs()
            projectsDir.mkdirs()

            val tempZipFile = File(appContext.cacheDir, "bootstrap-$arch-download.zip")
            if (tempZipFile.exists()) {
                tempZipFile.delete()
            }

            var downloadSucceeded = false
            var finalDownloadedSize = 0L

            when (source) {
                is BootstrapSource.Auto -> {
                    val targetUrl = rootfsUrl(source.mirror)
                    val targetAsset = "$HARDCODED_ROOTFS_ASSET · ${source.mirror.label}"
                    val targetSizeBytes = HARDCODED_ROOTFS_SIZE_BYTES
                    val titlePrefix = "AntiGem Rootfs (${source.mirror.label})"

                    _installerState.value = LocalInstallerState.Downloading(
                        bytesDownloaded = 0L,
                        totalBytes = targetSizeBytes,
                        progressFraction = 0.02f,
                        speedText = "Connecting...",
                        currentPackageName = targetAsset
                    )
                    updateInstallNotification(appContext, "Downloading $titlePrefix", "Connecting...", -1)

                    try {
                        val request = Request.Builder()
                            .url(targetUrl)
                            .header("User-Agent", "GeminiApp-LocalTerminal/${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"}")
                            .build()

                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                val errorMsg = "Download failed: HTTP ${response.code} from release server."
                                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                                _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                                return@withContext false
                            }

                            val body = response.body
                            if (body == null) {
                                val errorMsg = "Download failed: Empty response body."
                                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                                _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                                return@withContext false
                            }

                            val contentLength = body.contentLength()
                            val expectedTotal = if (contentLength > 0) contentLength else targetSizeBytes
                            var bytesReadTotal = 0L
                            val startTime = System.currentTimeMillis()

                            val buffer = ByteArray(64 * 1024)
                            FileOutputStream(tempZipFile).use { outStream ->
                                body.byteStream().use { inStream ->
                                    var read: Int
                                    var lastUpdate = System.currentTimeMillis()
                                    while (inStream.read(buffer).also { read = it } != -1) {
                                        outStream.write(buffer, 0, read)
                                        bytesReadTotal += read

                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdate > 120) {
                                            val elapsedSec = (now - startTime) / 1000.0
                                            val speed = if (elapsedSec > 0) bytesReadTotal / elapsedSec else 0.0
                                            val speedFormatted = "${formatFileSize(speed.toLong())}/s"
                                            val fraction = (bytesReadTotal.toFloat() / expectedTotal.toFloat()).coerceIn(0.05f, 0.95f)

                                            _installerState.value = LocalInstallerState.Downloading(
                                                bytesDownloaded = bytesReadTotal,
                                                totalBytes = expectedTotal,
                                                progressFraction = fraction,
                                                speedText = speedFormatted,
                                                currentPackageName = targetAsset
                                            )
                                            val pct = (fraction * 100).toInt()
                                            updateInstallNotification(appContext, "Downloading $titlePrefix ($pct%)", "$speedFormatted • $targetAsset", pct)
                                            lastUpdate = now
                                        }
                                    }
                                }
                            }

                            finalDownloadedSize = tempZipFile.length()
                            if (finalDownloadedSize >= MIN_BOOTSTRAP_SIZE_BYTES && validateArchiveIntegrity(tempZipFile)) {
                                downloadSucceeded = true
                            } else {
                                tempZipFile.delete()
                                val errorMsg = "Downloaded rootfs archive was incomplete or corrupted."
                                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                                _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                                return@withContext false
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Download attempt failed from $targetUrl: ${e.message}")
                        if (tempZipFile.exists()) tempZipFile.delete()
                        val errorMsg = "Download failed: ${e.message ?: "Network error"}"
                        updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                        _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                        return@withContext false
                    }
                }

                is BootstrapSource.BaseMinimum -> {
                    _installerState.value = LocalInstallerState.Downloading(
                        bytesDownloaded = 0L,
                        totalBytes = 35 * 1024 * 1024L,
                        progressFraction = 0.02f,
                        speedText = "Finding package...",
                        currentPackageName = "bootstrap-$arch.zip"
                    )
                    updateInstallNotification(appContext, "Downloading Linux Rootfs", "Finding bootstrap package...", -1)

                    val downloadUrls = resolveBootstrapUrls(arch)
                    Log.d(TAG, "Resolved ${downloadUrls.size} candidate bootstrap download URLs for $arch")

                    for ((index, url) in downloadUrls.withIndex()) {
                        try {
                            Log.d(TAG, "Trying download mirror [${index + 1}/${downloadUrls.size}]: $url")
                            _installerState.value = LocalInstallerState.Downloading(
                                bytesDownloaded = 0L,
                                totalBytes = 35 * 1024 * 1024L,
                                progressFraction = 0.05f,
                                speedText = "Connecting mirror ${index + 1}...",
                                currentPackageName = "bootstrap-$arch.zip"
                            )
                            updateInstallNotification(appContext, "Downloading Linux Rootfs", "Connecting mirror ${index + 1}...", -1)

                            val request = Request.Builder()
                                .url(url)
                                .header("User-Agent", "GeminiApp-LocalTerminal/${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"}")
                                .build()

                            httpClient.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    Log.w(TAG, "Mirror returned HTTP ${response.code}: $url")
                                    return@use
                                }

                                val body = response.body ?: return@use
                                val contentLength = body.contentLength()
                                if (contentLength in 1 until MIN_BOOTSTRAP_SIZE_BYTES) {
                                    Log.w(TAG, "Rejected URL $url due to content length: $contentLength bytes (< 10 MB)")
                                    return@use
                                }

                                val expectedTotal = if (contentLength > 0) contentLength else 35 * 1024 * 1024L
                                var bytesReadTotal = 0L
                                val startTime = System.currentTimeMillis()

                                val buffer = ByteArray(64 * 1024)
                                FileOutputStream(tempZipFile).use { outStream ->
                                    body.byteStream().use { inStream ->
                                        var read: Int
                                        var lastUpdate = System.currentTimeMillis()
                                        while (inStream.read(buffer).also { read = it } != -1) {
                                            outStream.write(buffer, 0, read)
                                            bytesReadTotal += read

                                            val now = System.currentTimeMillis()
                                            if (now - lastUpdate > 120) {
                                                val elapsedSec = (now - startTime) / 1000.0
                                                val speed = if (elapsedSec > 0) bytesReadTotal / elapsedSec else 0.0
                                                val speedFormatted = "${formatFileSize(speed.toLong())}/s"
                                                val fraction = (bytesReadTotal.toFloat() / expectedTotal.toFloat()).coerceIn(0.05f, 0.95f)

                                                _installerState.value = LocalInstallerState.Downloading(
                                                    bytesDownloaded = bytesReadTotal,
                                                    totalBytes = expectedTotal,
                                                    progressFraction = fraction,
                                                    speedText = speedFormatted,
                                                    currentPackageName = "bootstrap-$arch.zip"
                                                )
                                                val pct = (fraction * 100).toInt()
                                                updateInstallNotification(appContext, "Downloading Linux Rootfs ($pct%)", "$speedFormatted • bootstrap-$arch.zip", pct)
                                                lastUpdate = now
                                            }
                                        }
                                    }
                                }

                                finalDownloadedSize = tempZipFile.length()
                                if (finalDownloadedSize >= MIN_BOOTSTRAP_SIZE_BYTES && validateArchiveIntegrity(tempZipFile)) {
                                    downloadSucceeded = true
                                } else {
                                    tempZipFile.delete()
                                }
                            }

                            if (downloadSucceeded) break
                        } catch (e: Exception) {
                            Log.w(TAG, "Download attempt failed from $url: ${e.message}")
                            if (tempZipFile.exists()) tempZipFile.delete()
                        }
                    }
                }

                is BootstrapSource.DirectUrl -> {
                    val customUrl = source.url.trim()
                    if (customUrl.isBlank() || !customUrl.startsWith("http")) {
                        val errorMsg = "Invalid download URL. Please provide a valid http/https direct link."
                        updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                        _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                        return@withContext false
                    }

                    _installerState.value = LocalInstallerState.Downloading(
                        bytesDownloaded = 0L,
                        totalBytes = 35 * 1024 * 1024L,
                        progressFraction = 0.05f,
                        speedText = "Connecting direct URL...",
                        currentPackageName = customUrl.substringAfterLast("/").take(30)
                    )
                    updateInstallNotification(appContext, "Downloading Linux Rootfs", "Connecting direct URL...", -1)

                    try {
                        val request = Request.Builder()
                            .url(customUrl)
                            .header("User-Agent", "GeminiApp-LocalTerminal/${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"}")
                            .build()

                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                val errorMsg = "Server returned HTTP ${response.code} for: $customUrl"
                                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                                _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                                return@withContext false
                            }

                            val body = response.body
                            if (body == null) {
                                updateInstallNotification(appContext, "Installation Failed", "Empty response body", -2, ongoing = false, force = true)
                                _installerState.value = LocalInstallerState.Error(errorMessage = "Empty response body received from URL.", canRetry = true)
                                return@withContext false
                            }

                            val contentLength = body.contentLength()
                            val expectedTotal = if (contentLength > 0) contentLength else 35 * 1024 * 1024L
                            var bytesReadTotal = 0L
                            val startTime = System.currentTimeMillis()

                            val buffer = ByteArray(64 * 1024)
                            FileOutputStream(tempZipFile).use { outStream ->
                                body.byteStream().use { inStream ->
                                    var read: Int
                                    var lastUpdate = System.currentTimeMillis()
                                    while (inStream.read(buffer).also { read = it } != -1) {
                                        outStream.write(buffer, 0, read)
                                        bytesReadTotal += read

                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdate > 120) {
                                            val elapsedSec = (now - startTime) / 1000.0
                                            val speed = if (elapsedSec > 0) bytesReadTotal / elapsedSec else 0.0
                                            val speedFormatted = "${formatFileSize(speed.toLong())}/s"
                                            val fraction = (bytesReadTotal.toFloat() / expectedTotal.toFloat()).coerceIn(0.05f, 0.95f)

                                            _installerState.value = LocalInstallerState.Downloading(
                                                bytesDownloaded = bytesReadTotal,
                                                totalBytes = expectedTotal,
                                                progressFraction = fraction,
                                                speedText = speedFormatted,
                                                currentPackageName = customUrl.substringAfterLast("/").take(25)
                                            )
                                            val pct = (fraction * 100).toInt()
                                            updateInstallNotification(appContext, "Downloading Linux Rootfs ($pct%)", speedFormatted, pct)
                                            lastUpdate = now
                                        }
                                    }
                                }
                            }
                            finalDownloadedSize = tempZipFile.length()
                            if (finalDownloadedSize < MIN_BOOTSTRAP_SIZE_BYTES) {
                                val errorMsg = "Downloaded file from URL is only ${formatFileSize(finalDownloadedSize)} (< 10 MB required)."
                                tempZipFile.delete()
                                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                                _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                                return@withContext false
                            }
                            if (!validateArchiveIntegrity(tempZipFile)) {
                                val errorMsg = "Downloaded file is not a valid archive."
                                tempZipFile.delete()
                                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                                _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                                return@withContext false
                            }
                            downloadSucceeded = true
                        }
                    } catch (e: Exception) {
                        val errorMsg = "Direct download failed: ${e.localizedMessage ?: e.message}"
                        updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                        _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                        return@withContext false
                    }
                }

                is BootstrapSource.LocalZipUri -> {
                    _installerState.value = LocalInstallerState.Downloading(
                        bytesDownloaded = 0L,
                        totalBytes = 35 * 1024 * 1024L,
                        progressFraction = 0.1f,
                        speedText = "Importing file...",
                        currentPackageName = "Local Archive"
                    )
                    updateInstallNotification(appContext, "Importing Linux Rootfs", "Importing archive file...", -1, force = true)

                    try {
                        val inStream = appContext.contentResolver.openInputStream(source.uri)
                            ?: throw IOException("Could not open input stream from chosen file")

                        var bytesReadTotal = 0L
                        val buffer = ByteArray(64 * 1024)
                        FileOutputStream(tempZipFile).use { outStream ->
                            inStream.use { input ->
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    outStream.write(buffer, 0, read)
                                    bytesReadTotal += read
                                }
                            }
                        }
                        finalDownloadedSize = tempZipFile.length()
                        if (finalDownloadedSize < MIN_BOOTSTRAP_SIZE_BYTES) {
                            val errorMsg = "Selected file is only ${formatFileSize(finalDownloadedSize)} (< 10 MB minimum required)."
                            tempZipFile.delete()
                            updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                            _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                            return@withContext false
                        }
                        if (!validateArchiveIntegrity(tempZipFile)) {
                            val errorMsg = "The selected file (${formatFileSize(finalDownloadedSize)}) is not a valid or readable archive."
                            tempZipFile.delete()
                            updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                            _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                            return@withContext false
                        }
                        downloadSucceeded = true
                    } catch (e: Exception) {
                        val errorMsg = "Failed to import selected ZIP file: ${e.localizedMessage ?: e.message}"
                        updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                        _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                        return@withContext false
                    }
                }
            }

            if (!downloadSucceeded || !tempZipFile.exists() || tempZipFile.length() < MIN_BOOTSTRAP_SIZE_BYTES) {
                val errorMsg = "Bootstrap archive is missing or invalid (< 10 MB). Please check source and retry."
                Log.e(TAG, errorMsg)
                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                _installerState.value = LocalInstallerState.Error(
                    errorMessage = errorMsg,
                    canRetry = true
                )
                return@withContext false
            }

            // Check if this file is a GZIP/XZ tarball or a ZIP archive
            var innerZipFound: File? = null
            var isDebsOnlyArchive = false
            var isGzipTar = false
            var isXzTar = false

            val headerBytes = ByteArray(6)
            try {
                FileInputStream(tempZipFile).use { it.read(headerBytes) }
            } catch (_: Exception) {}

            if (headerBytes.size >= 2 && headerBytes[0] == 0x1f.toByte() && headerBytes[1] == 0x8b.toByte()) {
                isGzipTar = true
            } else if (headerBytes.size >= 2 && headerBytes[0] == 0xFD.toByte() && headerBytes[1] == 0x37.toByte()) {
                isXzTar = true
            } else {
                try {
                    java.util.zip.ZipFile(tempZipFile).use { outerZip ->
                        val entries = outerZip.entries()
                        var hasRootfsFiles = false
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name
                            // a bootstrap zip wrapped in another zip sits at its top level; a .zip deeper inside (a
                            // backup's home/projects/x.zip, a file of some package) is just a file of the rootfs
                            val isInnerBootstrapZip = name.endsWith(".zip") && !name.contains('/')
                            if (isInnerBootstrapZip) {
                                val innerFile = File(appContext.cacheDir, "inner_bootstrap.zip")
                                outerZip.getInputStream(entry).use { inStream ->
                                    FileOutputStream(innerFile).use { outStream ->
                                        inStream.copyTo(outStream)
                                    }
                                }
                                innerZipFound = innerFile
                                break
                            }
                            if (name.contains("bin/") || name.contains("usr/")) {
                                hasRootfsFiles = true
                            }
                            if (name.endsWith("debs.tar.gz") || name.endsWith(".deb")) {
                                isDebsOnlyArchive = true
                            }
                        }
                        if (!hasRootfsFiles && isDebsOnlyArchive && innerZipFound == null) {
                            isDebsOnlyArchive = true
                        } else {
                            isDebsOnlyArchive = false
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting outer ZIP structure: ${e.message}")
                }
            }

            val inner = innerZipFound
            val archiveToExtract = if (inner != null && inner.exists() && inner.length() > 0) {
                tempZipFile.delete()
                inner
            } else {
                tempZipFile
            }

            // STEP 2: Extract verified bootstrap archive
            val archiveFormatName = if (isXzTar) "XZ" else if (isGzipTar) "GZIP" else if (isDebsOnlyArchive) "DEB" else "ZIP"
            log("Archive ready: ${formatFileSize(archiveToExtract.length())}, format=$archiveFormatName")
            Log.d(TAG, "Extracting verified bootstrap archive (${formatFileSize(archiveToExtract.length())}, format=$archiveFormatName)...")
            _installerState.value = LocalInstallerState.Extracting(
                extractedFilesCount = 0,
                totalFilesEstimate = if (isDebsOnlyArchive) 155 else 1200,
                progressFraction = 0.05f,
                currentFileName = "Preparing directory structure..."
            )
            updateInstallNotification(appContext, "Extracting Linux Rootfs (0%)", "Preparing directory structure...", 0, force = true)

            // Reset target directory for a clean install
            if (prefixDir.exists()) {
                prefixDir.deleteRecursively()
            }
            prefixDir.mkdirs()
            binDir.mkdirs()
            libDir.mkdirs()
            etcDir.mkdirs()
            tmpDir.mkdirs()

            var extractedCount = 0

            if (isDebsOnlyArchive) {
                log("Unpacking DEB packages from archive into ${prefixDir.absolutePath}...")
                extractDebsArchive(archiveToExtract, prefixDir, appContext) { count, total, pkgName ->
                    val fraction = (count.toFloat() / total.toFloat()).coerceIn(0.05f, 0.95f)
                    _installerState.value = LocalInstallerState.Extracting(
                        extractedFilesCount = count,
                        totalFilesEstimate = total,
                        progressFraction = fraction,
                        currentFileName = "Unpacking package: $pkgName"
                    )
                    val pct = (fraction * 100).toInt()
                    updateInstallNotification(appContext, "Extracting Packages ($pct%)", "Unpacking $pkgName ($count/$total)", pct)
                }
            } else if (isGzipTar || isXzTar) {
                val destDir = prefixDir
                log("Extracting rootfs files from $archiveFormatName tarball into ${destDir.absolutePath}...")

                val rawStream = BufferedInputStream(FileInputStream(archiveToExtract))
                val compressedStream: InputStream = if (isXzTar) {
                    XZCompressorInputStream(rawStream)
                } else {
                    GzipCompressorInputStream(rawStream)
                }
                TarArchiveInputStream(compressedStream).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry
                    while (entry != null) {
                        val rawName = entry.name
                        val cleanPath = rawName.removePrefix("./").removePrefix("/")
                        if (cleanPath.isNotEmpty()) {
                            val targetFile = rootfsTarget(cleanPath, prefixDir, homeDir)

                            if (targetFile != null) {
                                targetFile.parentFile?.let { p ->
                                    if (!p.exists()) {
                                        p.mkdirs()
                                        try { Os.chmod(p.absolutePath, 493) } catch (_: Exception) {}
                                    }
                                }

                                if (entry.isSymbolicLink) {
                                    if (targetFile.exists() || isSymlink(targetFile)) {
                                        targetFile.setWritable(true, true)
                                        try { targetFile.delete() } catch (_: Exception) {}
                                    }
                                    val symlinkTarget = entry.linkName
                                    if (!symlinkTarget.isNullOrBlank()) {
                                        try {
                                            Os.symlink(symlinkTarget, targetFile.absolutePath)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed creating symlink $cleanPath -> $symlinkTarget: ${e.message}")
                                        }
                                    }
                                } else if (entry.isDirectory) {
                                    targetFile.mkdirs()
                                    val dirMode = if (entry.mode != 0) (entry.mode and 511) or 493 else 493
                                    try { Os.chmod(targetFile.absolutePath, dirMode) } catch (_: Exception) {}
                                } else {
                                    if (targetFile.exists() || isSymlink(targetFile)) {
                                        targetFile.setWritable(true, true)
                                        try { targetFile.delete() } catch (_: Exception) {}
                                    }
                                    try {
                                        FileOutputStream(targetFile).use { fos ->
                                            tarIn.copyTo(fos)
                                        }
                                        val unixMode = entry.mode and 511
                                        val isExecutableDir = targetFile.parentFile?.name in listOf("bin", "libexec", "applets", "sbin")
                                        val isExecutablePath = targetFile.absolutePath.contains("/bin/") || targetFile.absolutePath.contains("/libexec/")
                                        if (isExecutableDir || isExecutablePath || !targetFile.name.contains(".")) {
                                            targetFile.setExecutable(true, false)
                                            targetFile.setReadable(true, false)
                                            targetFile.setWritable(true, true)
                                            try { Os.chmod(targetFile.absolutePath, if (unixMode != 0) unixMode or 493 else 493) } catch (_: Exception) {}
                                        } else {
                                            targetFile.setReadable(true, false)
                                            try { Os.chmod(targetFile.absolutePath, if (unixMode != 0) unixMode or 420 else 420) } catch (_: Exception) {}
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error extracting $rawName to ${targetFile.absolutePath}: ${e.message}")
                                    }
                                }
                            }
                        }

                        extractedCount++
                        if (extractedCount % 50 == 0) {
                            val fraction = (extractedCount.toFloat() / (extractedCount + 300).toFloat()).coerceIn(0.05f, 0.95f)
                            _installerState.value = LocalInstallerState.Extracting(
                                extractedFilesCount = extractedCount,
                                totalFilesEstimate = extractedCount + 300,
                                progressFraction = fraction,
                                currentFileName = rawName
                            )
                            val pct = (fraction * 100).toInt()
                            updateInstallNotification(appContext, "Extracting Rootfs ($pct%)", rawName.substringAfterLast("/"), pct)
                        }
                        entry = tarIn.nextEntry
                    }
                }
            } else {
                val destDir = prefixDir
                log("Extracting rootfs files from ZIP into ${destDir.absolutePath}...")
                val symlinksFromTxt = mutableListOf<Pair<String, String>>()
                CommonsZipFile(archiveToExtract).use { zip ->
                    val entriesList = zip.entries.toList()
                    val totalEntries = entriesList.size

                    for (entry in entriesList) {
                        val rawName = entry.name

                        if (rawName == "SYMLINKS.txt" || rawName.endsWith("/SYMLINKS.txt")) {
                            try {
                                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                                    lines.forEach { line ->
                                        val trimmed = line.trim()
                                        if (trimmed.isNotEmpty()) {
                                            val parts = trimmed.split("←")
                                            if (parts.size == 2) {
                                                symlinksFromTxt.add(Pair(parts[0].trim(), parts[1].trim()))
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed reading SYMLINKS.txt: ${e.message}")
                            }
                            continue
                        }

                        val cleanPath = rawName.removePrefix("./")
                        val targetFile = rootfsTarget(cleanPath, prefixDir, homeDir)

                        if (targetFile != null) {
                            val unixMode = entry.unixMode

                            if (entry.isUnixSymlink) {
                                try {
                                    targetFile.parentFile?.let { p ->
                                        if (!p.exists()) {
                                            p.mkdirs()
                                            try { Os.chmod(p.absolutePath, 493) } catch (_: Exception) {}
                                        }
                                    }
                                    if (targetFile.exists() || isSymlink(targetFile)) {
                                        targetFile.setWritable(true, true)
                                        try { targetFile.delete() } catch (_: Exception) {}
                                    }
                                    val symlinkTarget = zip.getUnixSymlink(entry)
                                    if (!symlinkTarget.isNullOrBlank()) {
                                        Os.symlink(symlinkTarget, targetFile.absolutePath)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed creating embedded symlink for $rawName: ${e.message}")
                                }
                            } else if (entry.isDirectory) {
                                targetFile.mkdirs()
                                val dirMode = if (unixMode != 0) unixMode else 493 // 0755
                                try {
                                    Os.chmod(targetFile.absolutePath, dirMode)
                                } catch (e: Exception) {
                                    Log.w(TAG, "chmod failed on directory ${targetFile.name}: ${e.message}")
                                }
                            } else {
                                targetFile.parentFile?.let { p ->
                                    if (!p.exists()) {
                                        p.mkdirs()
                                        try { Os.chmod(p.absolutePath, 493) } catch (_: Exception) {}
                                    }
                                }

                                if (targetFile.exists() || isSymlink(targetFile)) {
                                    targetFile.setWritable(true, true)
                                    try {
                                        targetFile.delete()
                                    } catch (_: Exception) {}
                                }

                                try {
                                    zip.getInputStream(entry).use { inStream ->
                                        FileOutputStream(targetFile).use { fos ->
                                            inStream.copyTo(fos)
                                        }
                                    }

                                    // Apply exact Unix mode recorded in the ZIP header
                                    if (unixMode != 0) {
                                        try {
                                            Os.chmod(targetFile.absolutePath, unixMode)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "chmod failed on ${targetFile.name} (mode $unixMode): ${e.message}")
                                        }
                                    } else {
                                        // Fallback if ZIP had no Unix attributes
                                        val isExecutableDir = targetFile.parentFile?.name in listOf("bin", "libexec", "applets", "sbin")
                                        val isExecutablePath = targetFile.absolutePath.contains("/bin/") || targetFile.absolutePath.contains("/libexec/")
                                        if (isExecutableDir || isExecutablePath || !targetFile.name.contains(".")) {
                                            targetFile.setExecutable(true, false)
                                        }
                                        targetFile.setReadable(true, false)
                                        targetFile.setWritable(true, true)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Non-fatal error extracting ${rawName} to ${targetFile.absolutePath}: ${e.message}")
                                }
                            }
                        }

                        extractedCount++
                        if (extractedCount % 20 == 0 || extractedCount == totalEntries) {
                            val fraction = (extractedCount.toFloat() / totalEntries.toFloat()).coerceIn(0.05f, 0.95f)
                            _installerState.value = LocalInstallerState.Extracting(
                                extractedFilesCount = extractedCount,
                                totalFilesEstimate = totalEntries,
                                progressFraction = fraction,
                                currentFileName = rawName
                            )
                            val pct = (fraction * 100).toInt()
                            updateInstallNotification(appContext, "Extracting Rootfs ($pct%)", rawName.substringAfterLast("/"), pct)
                        }
                    }

                    // Apply all symlinks listed in SYMLINKS.txt
                    if (symlinksFromTxt.isNotEmpty()) {
                        log("Creating ${symlinksFromTxt.size} symlinks from SYMLINKS.txt...")
                        for ((target, linkRelPath) in symlinksFromTxt) {
                            try {
                                val cleanRel = linkRelPath.removePrefix("./")
                                val linkFile = rootfsTarget(cleanRel, prefixDir, homeDir)

                                if (linkFile != null) {
                                    linkFile.parentFile?.let { p ->
                                        if (!p.exists()) {
                                            p.mkdirs()
                                            try { Os.chmod(p.absolutePath, 493) } catch (_: Exception) {}
                                        }
                                    }
                                    if (linkFile.exists() || isSymlink(linkFile)) {
                                        linkFile.setWritable(true, true)
                                        try { linkFile.delete() } catch (_: Exception) {}
                                    }
                                    Os.symlink(target, linkFile.absolutePath)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error creating symlink for $linkRelPath -> $target: ${e.message}")
                            }
                        }
                    }
                }
            }

            // Termux files are built for com.termux: rewrite them for this app's package name (same length)
            TermuxRelocation.targetPackage(appContext)?.let { pkg ->
                log("Adapting Termux files for $pkg...")
                _installerState.value = LocalInstallerState.Configuring(
                    stepDescription = "Adapting Termux files for $pkg...",
                    progressFraction = 0.88f
                )
                updateInstallNotification(appContext, "Adapting Linux Environment", "Rewriting com.termux paths for $pkg...", -1, force = true)
                val onProgress: (Int) -> Unit = { n ->
                    _installerState.value = LocalInstallerState.Configuring(
                        stepDescription = "Adapting Termux files for $pkg ($n files checked)...",
                        progressFraction = 0.88f
                    )
                }
                val usr = TermuxRelocation.relocateTree(prefixDir, pkg, onProgress, releasePaths = true)
                // the home folder holds the user's own files: only paths there (see TermuxRelocation)
                val home = TermuxRelocation.relocateTree(homeDir, pkg, onProgress, pathsOnly = true, releasePaths = true)
                log("✓ Adapted ${usr.patchedFiles + home.patchedFiles} files and ${usr.links + home.links} symlinks (${usr.files + home.files} files checked)")
            }

            // Clean up downloaded zip
            tempZipFile.delete()

            // STEP 3: Configure Shell Profiles & Environment
            log("Configuring shell profiles (.bashrc) and directory permissions...")
            _installerState.value = LocalInstallerState.Configuring(
                stepDescription = "Configuring shell profiles, paths, and environment...",
                progressFraction = 0.90f
            )
            updateInstallNotification(appContext, "Configuring Linux Environment (90%)", "Configuring paths, permissions, and shell profiles...", 90, force = true)
            
            configureEnvironmentFiles(appContext, prefixDir, binDir, etcDir, homeDir, projectsDir)
            repairIncompletePackages(appContext)

            // STEP 4: Strict Verification
            _installerState.value = LocalInstallerState.Verifying(
                testName = "Verifying Linux environment and shell execution..."
            )
            updateInstallNotification(appContext, "Verifying Installation (95%)", "Testing shell environment...", 95, force = true)
            delay(200)

            val checkDir = prefixDir
            val installedSize = calculateDirectorySize(checkDir)
            val installedSizeStr = formatFileSize(installedSize)
            log("Total installed rootfs size: $installedSizeStr ($installedSize bytes)")

            if (installedSize < 5 * 1024 * 1024L) {
                val errorMsg = "Verification failed: installed package directory is too small ($installedSizeStr). Installation is incomplete."
                log("❌ $errorMsg")
                cleanupFailedInstall(appContext, authPreferences)
                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                _installerState.value = LocalInstallerState.Error(
                    errorMessage = errorMsg,
                    canRetry = true
                )
                return@withContext false
            }

            log("Running verification test command in shell...")
            val testRes = executeCommand(
                command = "echo 'GEMINI_LOCAL_TOOLS_OK' && pwd && which bash",
                context = appContext
            )
            log("Verification output: ${testRes.output.trim()}")

            if (!testRes.output.contains("GEMINI_LOCAL_TOOLS_OK")) {
                val errorMsg = "Shell verification test failed: ${testRes.output}"
                log("❌ $errorMsg")
                cleanupFailedInstall(appContext, authPreferences)
                updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                _installerState.value = LocalInstallerState.Error(
                    errorMessage = errorMsg,
                    canRetry = true
                )
                return@withContext false
            }

            // Save installation state in preferences; the environment just installed becomes the phone's runtime
            // (an Ubuntu chroot stays installed and can be chosen again in Settings → Where agents run).
            // Coming from the chroot: its bridge outlives the app, so it is stopped (and its terminals closed) first
            val wasChroot = authPreferences.isChrootModeSync()
            if (wasChroot) {
                log("Stopping the Ubuntu chroot's bridge: the agents move to AntiGem's built-in environment…")
                runCatching { LocalServerManager.stopServerCompletely() }
                runCatching { ai.antigem.data.local.chroot.ChrootManager.stopBridge(appContext) }
                runCatching { LocalTerminalManager.closeAll() }
            }
            authPreferences.setEnvironmentType(ai.antigem.data.local.chroot.ChrootManager.ENV_TERMUX)
            authPreferences.applyLocalRuntimeUrls(chroot = false)
            authPreferences.setLocalToolsInstalled(true, BOOTSTRAP_VERSION)
            authPreferences.setLocalToolsEnabled(true)
            authPreferences.setTerminalToolEnabled(true)

            _installerState.value = LocalInstallerState.Success(
                message = "Termux Linux environment verified and installed successfully!",
                prefixPath = prefixDir.absolutePath,
                totalDiskUsageFormatted = getFormattedDiskSpace(appContext)
            )
            updateInstallNotification(appContext, "Installation Complete", "Termux Linux environment is verified and ready!", 100, ongoing = false, force = true)
            log("✅ Local environment ready and verified! Total space: ${getFormattedDiskSpace(appContext)}")
            if (wasChroot && ai.antigem.data.agent.AgentPreferences.hasChosenSync(appContext)) {
                LocalServerManager.startServer(appContext, forceRestart = true)
            } else {
                LocalTerminalManager.autoLaunchServerIfReady(appContext)
            }
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to install local tools: ${e.message}", e)
            cleanupFailedInstall(appContext, authPreferences)
            updateInstallNotification(appContext, "Installation Failed", e.localizedMessage ?: "Installation error", -2, ongoing = false, force = true)
            _installerState.value = LocalInstallerState.Error(
                errorMessage = "Installation failed: ${e.localizedMessage ?: e.message}",
                canRetry = true
            )
            return@withContext false
        }
    }

    private suspend fun cleanupFailedInstall(context: Context, authPreferences: AuthPreferences) {
        try {
            log("🧹 Cleaning up incomplete/failed installation via uninstaller (resetEnvironment)...")
            resetEnvironment(context)
            authPreferences.setLocalToolsInstalled(false)
            authPreferences.setLocalToolsEnabled(false)
            authPreferences.setTerminalToolEnabled(false)
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup error: ${e.message}")
        }
    }

    private fun validateArchiveIntegrity(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_BOOTSTRAP_SIZE_BYTES) {
            Log.w(TAG, "File size check failed: ${file.length()} bytes (< 10 MB required)")
            return false
        }
        return try {
            val header = ByteArray(6)
            FileInputStream(file).use { it.read(header) }
            val isGzip = header.size >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
            val isXz = header.size >= 2 && header[0] == 0xFD.toByte() && header[1] == 0x37.toByte()
            if (isXz) {
                TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(FileInputStream(file)))).use { tarIn ->
                    tarIn.nextEntry != null
                }
            } else if (isGzip) {
                TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(file)))).use { tarIn ->
                    tarIn.nextEntry != null
                }
            } else {
                java.util.zip.ZipFile(file).use { zip ->
                    val count = zip.size()
                    Log.d(TAG, "validateArchiveIntegrity: ZIP contains $count entries")
                    count > 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Archive integrity error: ${e.message}", e)
            false
        }
    }

    /**
     * Packages the archive left incomplete: files dpkg lists for them are missing (backups made by older versions of
     * backup_bootstrap left out every cache/ folder, npm's own code among them). They are reinstalled with apt, which
     * also rewrites them for this app. The termux-api package (the termux-* commands) is installed with them when it
     * is missing. Needs the network; without it the install still finishes and the log names them. Deleted conffiles
     * (say etc/motd) are the user's choice and don't count.
     */
    private suspend fun repairIncompletePackages(context: Context) {
        val infoDir = File(getPrefixDir(context), "var/lib/dpkg/info")
        val lists = infoDir.listFiles { f -> f.name.endsWith(".list") } ?: return
        val incomplete = lists.mapNotNull { list ->
            val pkg = list.name.removeSuffix(".list")
            val conffiles = File(infoDir, "$pkg.conffiles").takeIf { it.isFile }?.readLines()?.toSet().orEmpty()
            val missing = try {
                list.readLines().count { path ->
                    path.isNotBlank() && path !in conffiles &&
                        runCatching { Os.lstat(path) }.isFailure
                }
            } catch (_: Exception) { 0 }
            if (missing > 0) pkg.substringBefore(':') to missing else null
        }
        val addTermuxApi = !isTermuxApiPackageInstalled(context) || isTermuxApiPackageDamaged(context)
        if (incomplete.isEmpty() && !addTermuxApi) return
        val names = (incomplete.map { it.first } + if (addTermuxApi) listOf("termux-api") else emptyList()).distinct().joinToString(" ")
        if (incomplete.isNotEmpty()) log("Packages with missing files: ${incomplete.joinToString { "${it.first} (${it.second})" }}. Reinstalling them...")
        if (addTermuxApi) log("Installing termux-api (the termux-* commands)...")
        _installerState.value = LocalInstallerState.Configuring(
            stepDescription = if (incomplete.isNotEmpty()) "Repairing ${incomplete.size} incomplete package(s)..." else "Installing termux-api...",
            progressFraction = 0.92f
        )
        updateInstallNotification(context, "Completing Linux Environment", "Installing $names", -1, force = true)
        val keep = "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
        val result = executeCommand(
            command = "apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q $keep --reinstall $names",
            context = context,
            timeoutSeconds = 900,
            onOutputLine = { line -> if (line.isNotBlank()) log(line) }
        )
        if (result.exitCode == 0) {
            log("✓ Installed: $names")
            if (addTermuxApi) {
                context.getSharedPreferences("anti_gem_terminal_prefs", Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_TERMUX_API_PACKAGE_INSTALLED, true).apply()
                ensureTermuxApiDispatcher(context)
            }
        }
        else log("⚠ Could not reinstall $names (exit ${result.exitCode}). Run in the terminal later: pkg install --reinstall $names")
    }

    /**
     * Where a rootfs or backup archive entry goes. Backups hold usr/… and home/…; the Termux bootstrap holds paths
     * relative to usr (bin/…, lib/…). A leading ".../files/" before usr/ or home/ (data/data/<pkg>/files/usr/…) is
     * dropped; a "files/" deeper in a path (a package's own files/ folder) is part of the path. Null: nothing to make.
     */
    internal fun rootfsTarget(entryPath: String, prefixDir: File, homeDir: File): File? {
        var p = entryPath.removePrefix("./").trimStart('/')
        if (!p.startsWith("usr/") && !p.startsWith("home/") && p != "usr" && p != "home") {
            p = p.replaceFirst(ROOTFS_FILES_PREFIX, "")
        }
        p = p.trimEnd('/')
        return when {
            p.isEmpty() || p == "usr" || p == "home" -> null
            p.startsWith("home/") -> File(homeDir, p.removePrefix("home/"))
            p.startsWith("usr/") -> File(prefixDir, p.removePrefix("usr/"))
            else -> File(prefixDir, p)
        }
    }

    /** "data/data/<pkg>/files/", "data/user/0/<pkg>/files/" or any other folder path ending in files/, before usr/ or home/. */
    private val ROOTFS_FILES_PREFIX = Regex("^(?:.*?/)?files/(?=(?:usr|home)(?:/|$))")

    private fun isSymlink(file: File): Boolean {
        return try {
            val canon = if (file.parent == null) file else File(file.parentFile?.canonicalFile, file.name)
            canon.canonicalFile != canon.absoluteFile
        } catch (_: Exception) {
            false
        }
    }

    private fun extractGlibcMinAsset(context: Context, prefixDir: File): Boolean {
        log("Checking for bundled minimal Glibc runtime...")
        // shipped xz-compressed: 4.3 MB -> 1.2 MB in the APK; one per CPU (app/src/abi/<abi>/assets)
        val assetName = "glibc-min-${ai.antigem.BuildConfig.TARGET_ABI}.tar.xz"
        val inStream = try {
            context.assets.open(assetName)
        } catch (e: Exception) {
            log("ℹ Minimal Glibc asset ($assetName) not found in APK: ${e.message}")
            return false
        }

        return try {
            val glibcDir = File(prefixDir, "glibc")
            glibcDir.mkdirs()
            File(glibcDir, "lib").mkdirs()
            File(glibcDir, "etc").mkdirs()

            var extractedFiles = 0
            org.tukaani.xz.XZInputStream(BufferedInputStream(inStream, 64 * 1024)).use { rawStream ->
                TarArchiveInputStream(rawStream).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry
                    while (entry != null) {
                        val rawName = entry.name
                        val entryName = rawName.removePrefix("glibc/").removePrefix("./glibc/").removePrefix("./")
                        if (entryName.isNotBlank() && entryName != "/") {
                            val targetFile = File(glibcDir, entryName)
                            if (entry.isDirectory) {
                                targetFile.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                targetFile.parentFile?.mkdirs()
                                if (targetFile.exists() || isSymlink(targetFile)) {
                                    targetFile.delete()
                                }
                                try {
                                    Os.symlink(entry.linkName, targetFile.absolutePath)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Symlink error for ${entry.name}: ${e.message}")
                                }
                            } else {
                                targetFile.parentFile?.mkdirs()
                                FileOutputStream(targetFile).use { fos ->
                                    tarIn.copyTo(fos)
                                }
                                targetFile.setExecutable(true, false)
                                targetFile.setReadable(true, false)
                                extractedFiles++
                            }
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
            log("✓ Successfully unpacked bundled Glibc runtime ($extractedFiles files) from $assetName into ${glibcDir.absolutePath}")
            TermuxRelocation.relocateIfNeeded(context, glibcDir)
            true
        } catch (e: Exception) {
            val err = "❌ Failed extracting glibc-min asset: ${e.localizedMessage ?: e.message}"
            log(err)
            Log.e(TAG, err, e)
            false
        }
    }

    fun ensureGlibcEnvironment(context: Context): Boolean {
        val authPrefs = AuthPreferences(context)
        if (authPrefs.isChrootModeSync()) return true
        val prefixDir = getPrefixDir(context)
        val glibcDir = File(prefixDir, "glibc")
        val glibcLib = File(glibcDir, "lib")
        val ldLinux = File(glibcLib, GLIBC_LOADER)

        // an x86_64 install made before the shim was in the bundle gets it here
        if (!ldLinux.exists() || GLIBC_SIGSYS_SHIM != null && !File(glibcLib, GLIBC_SIGSYS_SHIM).exists()) {
            extractGlibcMinAsset(context, prefixDir)
        }

        // Configure Glibc DNS & NSS resolver
        val glibcEtc = File(glibcDir, "etc")
        glibcEtc.mkdirs()
        val glibcNsswitch = File(glibcEtc, "nsswitch.conf")
        if (!glibcNsswitch.exists()) {
            glibcNsswitch.writeText("hosts: files dns\nnetworks: files\nprotocols: files\nservices: files\n")
        }

        val etcResolv = File(getEtcDir(context), "resolv.conf")
        val glibcResolv = File(glibcEtc, "resolv.conf")
        if (!glibcResolv.exists() && !isSymlink(glibcResolv)) {
            try {
                Os.symlink(etcResolv.absolutePath, glibcResolv.absolutePath)
            } catch (_: Exception) {
                glibcResolv.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
            }
        }

        val etcHosts = File(getEtcDir(context), "hosts")
        val glibcHosts = File(glibcEtc, "hosts")
        if (!glibcHosts.exists() && !isSymlink(glibcHosts)) {
            try {
                Os.symlink(etcHosts.absolutePath, glibcHosts.absolutePath)
            } catch (_: Exception) {
                glibcHosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
            }
        }

        if (glibcLib.exists()) {
            try {
                val nssDns = File(glibcLib, "libnss_dns.so")
                if (!nssDns.exists()) {
                    Os.symlink("libnss_dns.so.2", nssDns.absolutePath)
                }
                val nssFiles = File(glibcLib, "libnss_files.so")
                if (!nssFiles.exists()) {
                    Os.symlink("libnss_files.so.2", nssFiles.absolutePath)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Glibc lib symlink setup: ${e.message}")
            }
        }
        return ldLinux.exists()
    }

    private fun extractDebsArchive(
        archiveFile: File,
        prefixDir: File,
        appContext: Context,
        onProgress: (Int, Int, String) -> Unit
    ) {
        val cacheDir = File(appContext.cacheDir, "deb_extract_tmp")
        if (cacheDir.exists()) cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        try {
            var debsTarGzFile: File? = null

            // 1. Try to open as a ZIP archive
            var isZip = false
            try {
                java.util.zip.ZipFile(archiveFile).use { zip ->
                    isZip = true
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (name.endsWith("debs.tar.gz") || name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                            val target = File(cacheDir, "debs.tar.gz")
                            zip.getInputStream(entry).use { inStream ->
                                FileOutputStream(target).use { outStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            debsTarGzFile = target
                            log("Extracted debs.tar.gz from zip archive (${formatFileSize(target.length())})")
                            break
                        } else if (name.endsWith(".deb")) {
                            val target = File(cacheDir, name.substringAfterLast("/"))
                            zip.getInputStream(entry).use { inStream ->
                                FileOutputStream(target).use { outStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("Not a standard ZIP: ${e.message}")
            }

            if (!isZip) {
                debsTarGzFile = archiveFile
            }

            // 2. Unpack debs.tar.gz if found
            if (debsTarGzFile != null && debsTarGzFile.exists()) {
                try {
                    TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(debsTarGzFile)))).use { tarIn ->
                        var entry: TarArchiveEntry? = tarIn.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith(".deb")) {
                                val debName = entry.name.substringAfterLast("/")
                                val destDeb = File(cacheDir, debName)
                                FileOutputStream(destDeb).use { fos ->
                                    tarIn.copyTo(fos)
                                }
                            }
                            entry = tarIn.nextEntry
                        }
                    }
                } catch (e: Exception) {
                    log("Error unpacking debs.tar.gz: ${e.message}")
                }
            }

            val debFiles = cacheDir.listFiles { _, name -> name.endsWith(".deb") }?.toList() ?: emptyList()
            log("Found ${debFiles.size} deb packages in cache to extract into ${prefixDir.absolutePath}")

            val dpkgStatusDir = File(prefixDir, "var/lib/dpkg")
            dpkgStatusDir.mkdirs()
            File(dpkgStatusDir, "info").mkdirs()
            File(dpkgStatusDir, "updates").mkdirs()
            val dpkgStatusFile = File(dpkgStatusDir, "status")
            if (!dpkgStatusFile.exists()) dpkgStatusFile.createNewFile()
            val dpkgAvailFile = File(dpkgStatusDir, "available")
            if (!dpkgAvailFile.exists()) dpkgAvailFile.createNewFile()

            var extractedCount = 0
            val totalDebs = debFiles.size.coerceAtLeast(1)

            for (debFile in debFiles) {
                try {
                    val pkgName = debFile.name.substringBefore("_")
                    extractSingleDebFile(debFile, prefixDir, dpkgStatusFile, appContext)
                    log("✓ Extracted package ($extractedCount/$totalDebs): $pkgName")
                } catch (e: Exception) {
                    log("⚠ Failed extracting deb ${debFile.name}: ${e.message}")
                }
                extractedCount++
                onProgress(extractedCount, totalDebs, debFile.name.substringBefore("_"))
            }

        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun extractSingleDebFile(debFile: File, prefixDir: File, dpkgStatusFile: File, appContext: Context) {
        val tempDebDir = File(appContext.cacheDir, "deb_tmp_${Math.abs(debFile.name.hashCode())}")
        if (tempDebDir.exists()) tempDebDir.deleteRecursively()
        tempDebDir.mkdirs()
        try {
            var dataTarFile: File? = null
            var controlTarFile: File? = null

            ArArchiveInputStream(BufferedInputStream(FileInputStream(debFile))).use { arIn ->
                var arEntry: ArArchiveEntry? = arIn.nextEntry
                while (arEntry != null) {
                    val cleanName = arEntry.name.trim().removeSuffix("/")
                    if (cleanName.startsWith("data.tar")) {
                        val dest = File(tempDebDir, cleanName)
                        FileOutputStream(dest).use { fos -> arIn.copyTo(fos) }
                        dataTarFile = dest
                    } else if (cleanName.startsWith("control.tar")) {
                        val dest = File(tempDebDir, cleanName)
                        FileOutputStream(dest).use { fos -> arIn.copyTo(fos) }
                        controlTarFile = dest
                    }
                    arEntry = arIn.nextEntry
                }
            }

            // 1. Extract data.tar.* into prefixDir
            if (dataTarFile != null && dataTarFile.exists() && dataTarFile.length() > 0) {
                val fileName = dataTarFile.name
                val rawStream: InputStream = when {
                    fileName.endsWith(".xz") -> XZCompressorInputStream(BufferedInputStream(FileInputStream(dataTarFile)))
                    fileName.endsWith(".gz") || fileName.endsWith(".tgz") -> GzipCompressorInputStream(BufferedInputStream(FileInputStream(dataTarFile)))
                    else -> BufferedInputStream(FileInputStream(dataTarFile))
                }
                TarArchiveInputStream(rawStream).use { tarIn ->
                    var tarEntry: TarArchiveEntry? = tarIn.nextEntry
                    while (tarEntry != null) {
                        val rawTarName = tarEntry.name
                        val normalized = rawTarName
                            .removePrefix("./")
                            .replaceFirst(Regex("^data/data/[^/]+/files/usr/"), "")
                            .replaceFirst(Regex("^data/data/[^/]+/files/"), "")
                            .removePrefix("usr/")
                            .removePrefix("./")

                        if (normalized.isNotBlank() && normalized != "/") {
                            val targetFile = File(prefixDir, normalized)
                            if (tarEntry.isDirectory) {
                                targetFile.mkdirs()
                            } else if (tarEntry.isSymbolicLink) {
                                try {
                                    targetFile.parentFile?.mkdirs()
                                    if (targetFile.exists() || isSymlink(targetFile)) {
                                        targetFile.delete()
                                    }
                                    Os.symlink(tarEntry.linkName, targetFile.absolutePath)
                                } catch (e: Exception) {
                                    // Non-fatal symlink notice
                                }
                            } else {
                                targetFile.parentFile?.mkdirs()
                                FileOutputStream(targetFile).use { fos ->
                                    tarIn.copyTo(fos)
                                }
                                val isExec = targetFile.parentFile?.name in listOf("bin", "libexec", "applets", "sbin") ||
                                        targetFile.absolutePath.contains("/bin/") ||
                                        !targetFile.name.contains(".")
                                if (isExec) {
                                    targetFile.setExecutable(true, false)
                                    targetFile.setReadable(true, false)
                                }
                            }
                        }
                        tarEntry = tarIn.nextEntry
                    }
                }
            }

            // 2. Extract control.tar.* into dpkgStatusFile
            if (controlTarFile != null && controlTarFile.exists() && controlTarFile.length() > 0) {
                val fileName = controlTarFile.name
                val rawStream: InputStream = when {
                    fileName.endsWith(".xz") -> XZCompressorInputStream(BufferedInputStream(FileInputStream(controlTarFile)))
                    fileName.endsWith(".gz") || fileName.endsWith(".tgz") -> GzipCompressorInputStream(BufferedInputStream(FileInputStream(controlTarFile)))
                    else -> BufferedInputStream(FileInputStream(controlTarFile))
                }
                TarArchiveInputStream(rawStream).use { tarIn ->
                    var tarEntry: TarArchiveEntry? = tarIn.nextEntry
                    while (tarEntry != null) {
                        val cName = tarEntry.name.removePrefix("./")
                        if (cName == "control") {
                            val controlText = tarIn.bufferedReader(Charsets.UTF_8).readText()
                            if (controlText.isNotBlank()) {
                                FileOutputStream(dpkgStatusFile, true).bufferedWriter().use { writer ->
                                    writer.write(controlText.trim())
                                    writer.write("\nStatus: install ok installed\n\n")
                                }
                            }
                        }
                        tarEntry = tarIn.nextEntry
                    }
                }
            }
        } finally {
            tempDebDir.deleteRecursively()
        }
    }


    private fun configureEnvironmentFiles(
        context: Context,
        prefixDir: File,
        binDir: File,
        etcDir: File,
        homeDir: File,
        projectsDir: File
    ) {
        val dashFile = File(binDir, "dash")
        val bashFile = File(binDir, "bash")
        val shFile = File(binDir, "sh")

        // Ensure a working sh fallback exists if not present in archive
        if (!shFile.exists() || !shFile.canExecute()) {
            if (bashFile.exists() && bashFile.canExecute()) {
                try { Os.symlink("bash", shFile.absolutePath) } catch (_: Exception) {}
            } else if (dashFile.exists() && dashFile.canExecute()) {
                try { Os.symlink("dash", shFile.absolutePath) } catch (_: Exception) {}
            }
        }

        projectsDir.mkdirs()

        // Setup $HOME/.bashrc only if not provided by the archive
        val bashrc = File(homeDir, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """# AntiGem Local Linux Environment
export PREFIX="${prefixDir.absolutePath}"
export HOME="${homeDir.absolutePath}"
export PATH="${homeDir.absolutePath}/.local/bin:${binDir.absolutePath}:${binDir.absolutePath}/applets:/system/bin:/system/xbin"
export TMPDIR="${prefixDir.absolutePath}/tmp"
export TERM="xterm-256color"
export COLORTERM="truecolor"
export TERMUX_VERSION="0.118.0"
export TERMUX_MAIN_PACKAGE_NAME="${context.packageName}"
export TERMUX_APK_RELEASE="GITHUB"
export ANDROID_DATA="/data"
export ANDROID_ROOT="/system"
export LANG="en_US.UTF-8"
export LC_ALL="en_US.UTF-8"

alias ll='ls -la'
alias la='ls -A'
alias l='ls -CF'
alias cls='clear'
alias proj='cd ${projectsDir.absolutePath}'

# Termux styled color prompt
PS1='\[\033[01;32m\]antigem\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
""".trimIndent()
            )
            try { Os.chmod(bashrc.absolutePath, 420) } catch (_: Exception) {} // 0644
        } else {
            // the rootfs zip (a backup) brings the .bashrc of the app's old name: its prompt said "gemini". Only that
            // exact line changes; a prompt the user set stays
            try {
                val oldPrompt = """PS1='\[\033[01;32m\]gemini\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '"""
                val text = bashrc.readText()
                if (text.contains(oldPrompt)) {
                    bashrc.writeText(text.replace(oldPrompt, oldPrompt.replace("]gemini\\[", "]antigem\\[")))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not update the prompt in ${bashrc.absolutePath}: ${e.message}")
            }
        }

        val profile = File(homeDir, ".profile")
        if (!profile.exists()) {
            profile.writeText(
                """if [ -f "${bashrc.absolutePath}" ]; then
    . "${bashrc.absolutePath}"
fi
""".trimIndent()
            )
            try { Os.chmod(profile.absolutePath, 420) } catch (_: Exception) {} // 0644
        }

        // Setup $PREFIX/etc/profile only if not provided by archive
        val etcProfile = File(etcDir, "profile")
        if (!etcProfile.exists()) {
            etcProfile.writeText(
                """export PREFIX="${prefixDir.absolutePath}"
export HOME="${homeDir.absolutePath}"
export PATH="${homeDir.absolutePath}/.local/bin:${binDir.absolutePath}:${binDir.absolutePath}/applets:/system/bin:/system/xbin"
export TMPDIR="${prefixDir.absolutePath}/tmp"
export TERM="xterm-256color"
export COLORTERM="truecolor"
export TERMUX_VERSION="0.118.0"
export TERMUX_MAIN_PACKAGE_NAME="${context.packageName}"
export TERMUX_APK_RELEASE="GITHUB"
export ANDROID_DATA="/data"
export ANDROID_ROOT="/system"
export LANG="en_US.UTF-8"
export LC_ALL="en_US.UTF-8"
""".trimIndent()
            )
            try { Os.chmod(etcProfile.absolutePath, 420) } catch (_: Exception) {} // 0644
        }

        // Ensure standard DNS resolution files exist in $PREFIX/etc if missing
        val etcResolv = File(etcDir, "resolv.conf")
        if (!etcResolv.exists()) {
            etcResolv.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
            try { Os.chmod(etcResolv.absolutePath, 420) } catch (_: Exception) {} // 0644
        }
        val etcHosts = File(etcDir, "hosts")
        if (!etcHosts.exists()) {
            etcHosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
            try { Os.chmod(etcHosts.absolutePath, 420) } catch (_: Exception) {} // 0644
        }

        // Extract bundled minimal glibc runtime (1.7MB) if available
        extractGlibcMinAsset(context, prefixDir)

        // Configure Glibc DNS & NSS resolver if glibc directory is present
        val glibcDir = File(prefixDir, "glibc")
        val glibcEtc = File(glibcDir, "etc")
        glibcEtc.mkdirs()
        val glibcNsswitch = File(glibcEtc, "nsswitch.conf")
        glibcNsswitch.writeText("hosts: files dns\nnetworks: files\nprotocols: files\nservices: files\n")

        val glibcResolv = File(glibcEtc, "resolv.conf")
        if (!glibcResolv.exists() && !isSymlink(glibcResolv)) {
            try {
                android.system.Os.symlink(etcResolv.absolutePath, glibcResolv.absolutePath)
            } catch (_: Exception) {
                glibcResolv.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
            }
        }

        val glibcHosts = File(glibcEtc, "hosts")
        if (!glibcHosts.exists() && !isSymlink(glibcHosts)) {
            try {
                android.system.Os.symlink(etcHosts.absolutePath, glibcHosts.absolutePath)
            } catch (_: Exception) {
                glibcHosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
            }
        }

        val glibcLib = File(glibcDir, "lib")
        if (glibcLib.exists()) {
            try {
                val nssDns = File(glibcLib, "libnss_dns.so")
                if (!nssDns.exists()) {
                    android.system.Os.symlink("libnss_dns.so.2", nssDns.absolutePath)
                }
                val nssFiles = File(glibcLib, "libnss_files.so")
                if (!nssFiles.exists()) {
                    android.system.Os.symlink("libnss_files.so.2", nssFiles.absolutePath)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Glibc lib symlink setup: ${e.message}")
            }
        }

        // Ensure README in projects directory
        val readme = File(projectsDir, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                """# Projects Directory
This directory is your local workspace for coding and AI agent file operations.
All files created here persist inside the application.
""".trimIndent()
            )
        }

        // Generate fresh SSH host keys if OpenSSH (ssh-keygen) is installed
        val sshKeygen = File(binDir, "ssh-keygen")
        if (sshKeygen.exists()) {
            val sshDir = File(etcDir, "ssh")
            if (!sshDir.exists()) {
                sshDir.mkdirs()
                try { Os.chmod(sshDir.absolutePath, 493) } catch (_: Exception) {} // 0755
            }
            try {
                val pb = ProcessBuilder(sshKeygen.absolutePath, "-A")
                pb.environment()["PREFIX"] = prefixDir.absolutePath
                pb.environment()["HOME"] = homeDir.absolutePath
                pb.environment()["PATH"] = "${binDir.absolutePath}:/system/bin"
                val process = pb.start()
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                log("Generated fresh OpenSSH host keys via ssh-keygen -A")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to run ssh-keygen -A: ${e.message}")
            }
        }

        TermuxRelocation.ensurePackageHooks(context)
    }

    suspend fun executeCommand(
        command: String,
        context: Context? = null,
        workingDir: String? = null,
        customEnv: Map<String, String>? = null,
        timeoutSeconds: Long = 60,
        onOutputLine: ((String) -> Unit)? = null
    ): LocalCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val env = mutableMapOf<String, String>()

        var resolvedWorkingDir = workingDir
        if (context != null) {
            val prefix = getPrefixDir(context)
            val bin = getBinDir(context)
            val lib = getLibDir(context)
            val home = getHomeDir(context)
            val tmp = getTmpDir(context)

            env["PREFIX"] = prefix.absolutePath
            env["HOME"] = home.absolutePath
            env["PATH"] = "${home.absolutePath}/.local/bin:${bin.absolutePath}:${bin.absolutePath}/applets:/system/bin:/system/xbin"
            env["TMPDIR"] = tmp.absolutePath
            env["TERM"] = "xterm-256color"
            env["COLORTERM"] = "truecolor"
            env["TERMUX_VERSION"] = "0.118.0"
            env["TERMUX_MAIN_PACKAGE_NAME"] = context.packageName
            env["TERMUX_APK_RELEASE"] = "GITHUB"
            env["TERMUX_APP_PID"] = android.os.Process.myPid().toString()
            env["ANDROID_DATA"] = "/data"
            env["ANDROID_ROOT"] = "/system"
            env["LANG"] = "en_US.UTF-8"
            env["LC_ALL"] = "en_US.UTF-8"

            if (resolvedWorkingDir == null || !File(resolvedWorkingDir).exists()) {
                resolvedWorkingDir = home.absolutePath
            }
        }

        if (customEnv != null) {
            env.putAll(customEnv)
        }

        val shellBinary = if (context != null) {
            val localBash = File(getBinDir(context), "bash")
            val localDash = File(getBinDir(context), "dash")
            val localSh = File(getBinDir(context), "sh")
            when {
                localBash.exists() && localBash.canExecute() -> localBash.absolutePath
                localDash.exists() && localDash.canExecute() -> localDash.absolutePath
                localSh.exists() && localSh.canExecute() -> localSh.absolutePath
                localDash.exists() -> localDash.absolutePath
                localBash.exists() -> localBash.absolutePath
                localSh.exists() -> localSh.absolutePath
                else -> "/system/bin/sh"
            }
        } else {
            "/system/bin/sh"
        }

        val processBuilder = ProcessBuilder(shellBinary, "-c", command)
        if (resolvedWorkingDir != null && File(resolvedWorkingDir).exists()) {
            processBuilder.directory(File(resolvedWorkingDir))
        }

        val procEnv = processBuilder.environment()
        procEnv.putAll(env)
        processBuilder.redirectErrorStream(true)

        return@withContext try {
            val process = processBuilder.start()
            val outputBuilder = StringBuilder()

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                outputBuilder.append(line).append("\n")
                onOutputLine?.invoke(line!!)
            }

            val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            val durationMs = System.currentTimeMillis() - startTime

            if (!exited) {
                process.destroyForcibly()
                outputBuilder.append("\n[Process timed out after ${timeoutSeconds}s]")
                LocalCommandResult(exitCode = 124, output = outputBuilder.toString().trim(), durationMs = durationMs)
            } else {
                val exitCode = process.exitValue()
                LocalCommandResult(exitCode = exitCode, output = outputBuilder.toString().trim(), durationMs = durationMs)
            }
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "Command execution error: ${e.message}")
            LocalCommandResult(
                exitCode = 1,
                output = "Execution failed: ${e.localizedMessage ?: e.message}",
                durationMs = durationMs
            )
        }
    }

    fun clearAppDataAndReset(context: Context) {
        Log.d(TAG, "[Reset] Triggering Android OS application data wipe...")
        try {
            LocalServerManager.forceKillAll()
        } catch (_: Exception) {}
        // nothing of an Ubuntu chroot may run (or stay mounted) while Android deletes the app's data
        try {
            ai.antigem.data.local.chroot.ChrootManager.stopAllBlocking(context)
        } catch (_: Exception) {}

        try {
            LocalTerminalManager.closeAll()
        } catch (_: Exception) {}

        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val cleared = activityManager?.clearApplicationUserData() ?: false
            if (!cleared) {
                openAppInfoSettings(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearApplicationUserData failed, opening App Info settings: ${e.message}")
            openAppInfoSettings(context)
        }
    }

    fun openAppInfoSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening App Info settings: ${e.message}")
        }
    }

    suspend fun resetEnvironment(context: Context) = withContext(Dispatchers.IO) {
        Log.d(TAG, "[Reset] Stopping local services and resetting installer state...")
        try {
            LocalServerManager.forceKillAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping local server during reset: ${e.message}")
        }

        try {
            LocalTerminalManager.closeAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing terminal sessions during reset: ${e.message}")
        }

        LocalServerManager.resetAutoStartFlag()

        try {
            ai.antigem.data.remote.AgyBridgeService.instance.notifyLocalStopped()
        } catch (_: Exception) {}

        val appContext = context.applicationContext
        updateInstallNotification(appContext, "", "", -2, ongoing = false, force = true)
        _installerState.value = LocalInstallerState.Idle
        clearLogs()
        Log.d(TAG, "Local environment installer state cleared.")
    }

    suspend fun executeScriptLive(
        context: Context,
        scriptName: String,
        args: List<String> = emptyList(),
        onLogLine: (String) -> Unit
    ): LocalCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val binDir = getBinDir(context)
        val prefixDir = getPrefixDir(context)
        val homeDir = getHomeDir(context)
        val tmpDir = getTmpDir(context)

        // Find script binary
        var scriptFile = File(binDir, scriptName)
        if (!scriptFile.exists() || !scriptFile.canExecute()) {
            val usrBinFile = File(prefixDir, "bin/$scriptName")
            if (usrBinFile.exists() && usrBinFile.canExecute()) {
                scriptFile = usrBinFile
            }
        }

        if (!scriptFile.exists()) {
            val msg = "❌ Error: Script '$scriptName' not found at ${scriptFile.absolutePath}!"
            withContext(Dispatchers.Main) { onLogLine(msg) }
            return@withContext LocalCommandResult(1, msg, System.currentTimeMillis() - startTime)
        }
        scriptFile.setExecutable(true, false)

        val bashPath = when {
            File(binDir, "bash").exists() -> File(binDir, "bash").absolutePath
            File(prefixDir, "bin/bash").exists() -> File(prefixDir, "bin/bash").absolutePath
            File(binDir, "sh").exists() -> File(binDir, "sh").absolutePath
            else -> "sh"
        }

        val cmdList = mutableListOf(bashPath, scriptFile.absolutePath)
        cmdList.addAll(args)

        val pb = ProcessBuilder(cmdList)
        pb.directory(homeDir)
        val env = pb.environment()
        env["PREFIX"] = prefixDir.absolutePath
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["PATH"] = "${homeDir.absolutePath}/.local/bin:${binDir.absolutePath}:${prefixDir.absolutePath}/bin:/system/bin:/system/xbin"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"

        pb.redirectErrorStream(true)
        val process = pb.start()
        try {
            val outputBuilder = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                outputBuilder.appendLine(currentLine)
                withContext(Dispatchers.Main) {
                    onLogLine(currentLine)
                }
            }

            val exitCode = process.waitFor()
            LocalCommandResult(
                exitCode = exitCode,
                output = outputBuilder.toString(),
                durationMs = System.currentTimeMillis() - startTime
            )
        } finally {
            if (process.isAlive) {
                try {
                    process.destroyForcibly()
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun executeCommandLive(
        context: Context,
        command: String,
        onLogLine: (String) -> Unit
    ): LocalCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val binDir = getBinDir(context)
        val prefixDir = getPrefixDir(context)
        val homeDir = getHomeDir(context)
        val tmpDir = getTmpDir(context)

        val bashPath = when {
            File(binDir, "bash").exists() -> File(binDir, "bash").absolutePath
            File(prefixDir, "bin/bash").exists() -> File(prefixDir, "bin/bash").absolutePath
            File(binDir, "sh").exists() -> File(binDir, "sh").absolutePath
            else -> "sh"
        }

        val pb = ProcessBuilder(listOf(bashPath, "-c", command))
        pb.directory(homeDir)
        val env = pb.environment()
        env["PREFIX"] = prefixDir.absolutePath
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["PATH"] = "${homeDir.absolutePath}/.local/bin:${binDir.absolutePath}:${prefixDir.absolutePath}/bin:/system/bin:/system/xbin"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"

        pb.redirectErrorStream(true)
        val process = pb.start()
        try {
            val outputBuilder = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                outputBuilder.appendLine(currentLine)
                withContext(Dispatchers.Main) {
                    onLogLine(currentLine)
                }
            }

            val exitCode = process.waitFor()
            LocalCommandResult(
                exitCode = exitCode,
                output = outputBuilder.toString(),
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            val msg = "❌ Error executing command: ${e.message}"
            withContext(Dispatchers.Main) { onLogLine(msg) }
            LocalCommandResult(1, msg, System.currentTimeMillis() - startTime)
        } finally {
            if (process.isAlive) {
                try {
                    process.destroyForcibly()
                } catch (_: Exception) {}
            }
        }
    }

    fun launchInstall(
        context: Context,
        authPreferences: AuthPreferences,
        source: BootstrapSource = BootstrapSource.Auto
    ): Job {
        return managerScope.launch {
            installLocalEnvironment(context, authPreferences, source)
        }
    }

    fun launchDiscover(context: Context): Job {
        return managerScope.launch {
            discoverBootstrapPackage(context)
        }
    }

    fun launchReset(
        context: Context,
        onComplete: (() -> Unit)? = null
    ): Job {
        return managerScope.launch {
            resetEnvironment(context)
            onComplete?.invoke()
        }
    }
}
