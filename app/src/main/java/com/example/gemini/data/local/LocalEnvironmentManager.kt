package com.example.gemini.data.local

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
import com.example.gemini.R
import com.example.gemini.data.preferences.AuthPreferences
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

sealed class BootstrapSource {
    object Auto : BootstrapSource()
    object BaseMinimum : BootstrapSource()
    data class DirectUrl(val url: String) : BootstrapSource()
    data class LocalZipUri(val uri: android.net.Uri) : BootstrapSource()
}

object LocalEnvironmentManager {

    private const val TAG = "LocalEnvironmentManager"
    private const val BOOTSTRAP_VERSION = "2026.08"
    // Minimum 10 MB required for a real Termux bootstrap archive
    private const val MIN_BOOTSTRAP_SIZE_BYTES = 10 * 1024 * 1024L

    const val HARDCODED_ROOTFS_URL = "https://github.com/Santoshkurmi/antigem/releases/download/rootfs-v1/bootrapz_2026-10-04.zip"
    const val HARDCODED_ROOTFS_TAG = "rootfs-v1"
    const val HARDCODED_ROOTFS_ASSET = "bootrapz_2026-10-04.zip"
    const val HARDCODED_ROOTFS_SIZE_BYTES = 350 * 1024 * 1024L

    const val UBUNTU_ROOTFS_URL = "https://github.com/termux/proot-distro/releases/download/v4.30.1/ubuntu-questing-aarch64-pd-v4.30.1.tar.xz"
    const val UBUNTU_ROOTFS_TAG = "v4.30.1"
    const val UBUNTU_ROOTFS_ASSET = "ubuntu-questing-aarch64-pd-v4.30.1.tar.xz"
    const val UBUNTU_ROOTFS_SIZE_BYTES = 35 * 1024 * 1024L

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
    fun getUbuntuRootDir(context: Context): File = File(context.filesDir, "ubuntu")
    fun isTermuxPackage(context: Context): Boolean = context.packageName == "com.termux"

    fun ensureProotExtracted(context: Context): String {
        val binDir = File(context.filesDir, "bin")
        val libDir = File(context.filesDir, "lib")
        binDir.mkdirs()
        libDir.mkdirs()

        val prootBin = File(binDir, "proot")
        val loaderBin = File(binDir, "loader")
        val tallocLib = File(libDir, "libtalloc.so.2")
        val tallocBin = File(binDir, "libtalloc.so.2")

        try {
            if (!prootBin.exists() || prootBin.length() < 10000L) {
                context.assets.open("bin/proot").use { input ->
                    FileOutputStream(prootBin).use { output ->
                        input.copyTo(output)
                    }
                }
                prootBin.setExecutable(true, false)
                prootBin.setReadable(true, false)
                try { Os.chmod(prootBin.absolutePath, 493) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting proot from assets: ${e.message}")
        }

        try {
            if (!loaderBin.exists() || loaderBin.length() < 1000L) {
                context.assets.open("bin/loader").use { input ->
                    FileOutputStream(loaderBin).use { output ->
                        input.copyTo(output)
                    }
                }
                loaderBin.setExecutable(true, false)
                loaderBin.setReadable(true, false)
                try { Os.chmod(loaderBin.absolutePath, 493) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting loader from assets: ${e.message}")
        }

        try {
            if (!tallocLib.exists() || tallocLib.length() < 1000L) {
                val assetStream = try {
                    context.assets.open("lib/libtalloc.so.2")
                } catch (_: Exception) {
                    context.assets.open("bin/libtalloc.so.2")
                }
                assetStream.use { input ->
                    FileOutputStream(tallocLib).use { output ->
                        input.copyTo(output)
                    }
                }
                tallocLib.setReadable(true, false)
                try { Os.chmod(tallocLib.absolutePath, 493) } catch (_: Exception) {}
            }
            if (!tallocBin.exists() || tallocBin.length() < 1000L) {
                if (tallocLib.exists()) {
                    try {
                        tallocLib.copyTo(tallocBin, overwrite = true)
                        tallocBin.setReadable(true, false)
                        try { Os.chmod(tallocBin.absolutePath, 493) } catch (_: Exception) {}
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting talloc from assets: ${e.message}")
        }

        if (prootBin.exists() && prootBin.length() > 0L) {
            prootBin.setExecutable(true, false)
            prootBin.setReadable(true, false)
            try { Os.chmod(prootBin.absolutePath, 493) } catch (_: Exception) {}
            return prootBin.absolutePath
        }

        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib.absolutePath
        }

        return prootBin.absolutePath
    }

    fun getProotBinaryPath(context: Context): String = ensureProotExtracted(context)

    fun getProotLoaderPath(context: Context): String {
        ensureProotExtracted(context)
        return File(File(context.filesDir, "bin"), "loader").absolutePath
    }

    fun setupFakeSysdata(context: Context): List<Pair<File, String>> {
        val sysdataDir = File(context.filesDir, "sysdata").apply { mkdirs() }
        val emptyDir = File(sysdataDir, "sys_empty").apply { mkdirs() }

        val entries = listOf(
            Triple("loadavg", "/proc/loadavg", "0.12 0.07 0.02 2/165 765\n"),
            Triple("stat", "/proc/stat", "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\nintr 127541\nctxt 140223\nbtime 1680020856\nprocesses 772\nprocs_running 2\nprocs_blocked 0\nsoftirq 75663\n"),
            Triple("uptime", "/proc/uptime", "124.08 932.80\n"),
            Triple("version", "/proc/version", "Linux version 6.17.0-PRoot-Distro (proot@termux) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) #1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\n"),
            Triple("vmstat", "/proc/vmstat", "nr_free_pages 1743136\nnr_inactive_anon 179281\nnr_active_anon 7183\nnr_inactive_file 22858\nnr_active_file 51328\npgpgin 890508\npgpgout 0\npswpin 0\npswpout 0\npgfault 176973\n"),
            Triple("sysctl_entry_cap_last_cap", "/proc/sys/kernel/cap_last_cap", "40\n"),
            Triple("sysctl_inotify_max_user_watches", "/proc/sys/fs/inotify/max_user_watches", "4096\n"),
            Triple("sysctl_kernel_overflowuid", "/proc/sys/kernel/overflowuid", "65534\n"),
            Triple("sysctl_kernel_overflowgid", "/proc/sys/kernel/overflowgid", "65534\n"),
            Triple("fips_enabled", "/proc/sys/crypto/fips_enabled", "0\n")
        )

        val bindings = mutableListOf<Pair<File, String>>()
        if (File("/sys/fs/selinux").exists()) {
            bindings.add(emptyDir to "/sys/fs/selinux")
        }

        for ((name, guestPath, content) in entries) {
            val file = File(sysdataDir, name)
            if (!file.exists() || file.length() == 0L) {
                file.writeText(content)
            }
            var readable = false
            try {
                val realFile = File(guestPath)
                if (realFile.exists() && realFile.canRead()) {
                    FileInputStream(realFile).use { it.read() }
                    readable = true
                }
            } catch (_: Exception) {
                readable = false
            }
            if (!readable || guestPath.contains("fips_enabled")) {
                bindings.add(file to guestPath)
            }
        }
        return bindings
    }

    fun setupProotDistroUbuntuFixups(context: Context, ubuntuDir: File) {
        val resolvConf = File(ubuntuDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")

        val hosts = File(ubuntuDir, "etc/hosts")
        hosts.parentFile?.mkdirs()
        hosts.writeText(
            "# IPv4.\n" +
            "127.0.0.1   localhost.localdomain localhost\n\n" +
            "# IPv6.\n" +
            "::1         localhost.localdomain localhost ip6-localhost ip6-loopback\n" +
            "fe00::0     ip6-localnet\n" +
            "ff00::0     ip6-mcastprefix\n" +
            "ff02::1     ip6-allnodes\n" +
            "ff02::2     ip6-allrouters\n" +
            "ff02::3     ip6-allhosts\n"
        )

        // 1. Policy rc.d: exits 101 to prevent systemd service invocation during dpkg install/uninstall (fixes Error 100)
        val sbinDir = File(ubuntuDir, "usr/sbin").apply { mkdirs() }
        val policyRcd = File(sbinDir, "policy-rc.d")
        policyRcd.writeText("#!/bin/sh\nexit 101\n")
        policyRcd.setExecutable(true, false)
        policyRcd.setReadable(true, false)
        try { Os.chmod(policyRcd.absolutePath, 493) } catch (_: Exception) {}

        // 2. APT sandbox root user configuration and cleanup desktop/interactive hooks
        val aptConfDir = File(ubuntuDir, "etc/apt/apt.conf.d").apply { mkdirs() }
        File(aptConfDir, "01sandbox").writeText("APT::Sandbox::User \"root\";\nDir::Bin::methods \"/usr/lib/apt/methods\";\n")
        File(aptConfDir, "02no-recommends").writeText("APT::Install-Recommends \"0\";\nAPT::Install-Suggests \"0\";\n")
        File(aptConfDir, "20packagekit").delete()
        File(aptConfDir, "70debconf").delete()

        // 3. Register Android UID/GID in /etc/passwd and /etc/group (matches proot-distro helpers/rootfs.py)
        try {
            val uid = android.os.Process.myUid()
            val etcDir = File(ubuntuDir, "etc").apply { mkdirs() }
            val passwd = File(etcDir, "passwd")
            val shadow = File(etcDir, "shadow")
            val group = File(etcDir, "group")
            val gshadow = File(etcDir, "gshadow")

            val appUser = "aid_app"
            if (passwd.exists() && !passwd.readText().contains(appUser)) {
                passwd.appendText("$appUser:x:$uid:$uid:AndroidApp:/:/sbin/nologin\n")
            }
            if (shadow.exists() && !shadow.readText().contains(appUser)) {
                shadow.appendText("$appUser:*:18446:0:99999:7:::\n")
            }
            val groupText = if (group.exists()) group.readText() else ""
            if (!groupText.contains(appUser)) {
                group.appendText("$appUser:x:$uid:root,$appUser\n")
            }
            val gshadowText = if (gshadow.exists()) gshadow.readText() else ""
            if (!gshadowText.contains(appUser)) {
                gshadow.appendText("$appUser:*::root,$appUser\n")
            }

            // Standard Android supplementary network GIDs from proot-distro
            val supplementaryGids = listOf(
                "aid_inet" to 3003,
                "aid_net_raw" to 3004,
                "aid_admin" to 3005
            )
            for ((gname, gid) in supplementaryGids) {
                if (!groupText.contains(gname)) {
                    group.appendText("$gname:x:$gid:root,$appUser\n")
                }
                if (!gshadowText.contains(gname)) {
                    gshadow.appendText("$gname:*::root,$appUser\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Non-fatal error registering Android UIDs: ${e.message}")
        }

        File(ubuntuDir, "tmp").apply {
            mkdirs()
            try { Os.chmod(absolutePath, 511) } catch (_: Exception) {}
        }
        File(ubuntuDir, "dev").mkdirs()
        File(ubuntuDir, "proc").mkdirs()
        File(ubuntuDir, "sys").mkdirs()
        File(ubuntuDir, "root").mkdirs()
        val rootBashrc = File(File(ubuntuDir, "root"), ".bashrc")
        if (!rootBashrc.exists() || !rootBashrc.readText().contains("/usr/local/sbin")) {
            rootBashrc.appendText("\nexport PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH\n")
        }
        // Ubuntu's /etc/zsh/zshrc compinit does ~15k fpath stats under proot (~30s first start)
        val rootZshenv = File(File(ubuntuDir, "root"), ".zshenv")
        if (!rootZshenv.exists()) {
            rootZshenv.writeText("skip_global_compinit=1\n")
        }

        val profileD = File(File(ubuntuDir, "etc"), "profile.d").apply { mkdirs() }
        val termuxProfile = File(profileD, "termux-profile.sh")
        termuxProfile.writeText(
            "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH\"\n" +
            "export HOME=\"/root\"\n" +
            "export USER=\"root\"\n" +
            "export LOGNAME=\"root\"\n" +
            "export TERM=\"xterm-256color\"\n" +
            "export COLORTERM=\"truecolor\"\n" +
            "export LANG=\"C.UTF-8\"\n"
        )
        try { Os.chmod(termuxProfile.absolutePath, 420) } catch (_: Exception) {}

        ensureRootfsCompatibilityLinks(ubuntuDir)
    }

    fun ensureRootfsCompatibilityLinks(ubuntuDir: File): Boolean {
        if (!ubuntuDir.isDirectory) return false
        val links = mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "sbin" to "usr/sbin"
        )
        return runCatching {
            links.forEach { (name, destination) ->
                val link = File(ubuntuDir, name)
                val path = link.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    val currentTarget = java.nio.file.Files.readSymbolicLink(path).toString()
                    if (currentTarget != destination) {
                        java.nio.file.Files.delete(path)
                        Os.symlink(destination, link.absolutePath)
                    }
                } else if (!link.exists()) {
                    Os.symlink(destination, link.absolutePath)
                }
            }
            File(ubuntuDir, "usr/bin/env").setExecutable(true, false)
            File(ubuntuDir, "usr/bin/bash").setExecutable(true, false)
            File(ubuntuDir, "bin/bash").exists() || File(ubuntuDir, "usr/bin/bash").exists()
        }.onFailure {
            Log.e(TAG, "Failed ensuring Ubuntu compatibility links: ${it.message}")
        }.getOrDefault(false)
    }

    fun isUbuntuInstalled(context: Context): Boolean {
        val ubuntuDir = getUbuntuRootDir(context)
        val bash = File(ubuntuDir, "bin/bash")
        val sh = File(ubuntuDir, "bin/sh")
        val usrBash = File(ubuntuDir, "usr/bin/bash")
        return ubuntuDir.exists() && (bash.exists() || sh.exists() || usrBash.exists())
    }

    fun isInstalled(context: Context): Boolean {
        if (!isTermuxPackage(context)) {
            return isUbuntuInstalled(context)
        }
        val binDir = getBinDir(context)
        val homeDir = getHomeDir(context)
        return (binDir.exists() && binDir.isDirectory && (File(binDir, "sh").exists() || File(binDir, "dash").exists() || File(binDir, "bash").exists() || File(binDir, "busybox").exists())) &&
                homeDir.exists()
    }

    fun getInstallPath(context: Context): String {
        return if (!isTermuxPackage(context)) {
            getUbuntuRootDir(context).absolutePath
        } else {
            getPrefixDir(context).absolutePath
        }
    }

    fun getFormattedDiskSpace(context: Context): String {
        var totalBytes = 0L
        if (!isTermuxPackage(context)) {
            val ubuntu = getUbuntuRootDir(context)
            val home = getHomeDir(context)
            if (ubuntu.exists()) totalBytes += calculateDirectorySize(ubuntu)
            if (home.exists()) totalBytes += calculateDirectorySize(home)
        } else {
            val prefix = getPrefixDir(context)
            val home = getHomeDir(context)
            if (prefix.exists()) totalBytes += calculateDirectorySize(prefix)
            if (home.exists()) totalBytes += calculateDirectorySize(home)
        }
        return formatFileSize(totalBytes)
    }

    fun ensureTermuxApiDispatcher(context: Context) {
        try {
            val libexecDir = File(getPrefixDir(context), "libexec")
            if (!libexecDir.exists()) libexecDir.mkdirs()
            val dispatcherFile = File(libexecDir, "termux-api")
            val scriptContent = """
                #!/data/data/com.termux/files/usr/bin/bash
                METHOD="${'$'}1"
                shift

                EXTRA_ARGS=()
                if [ ! -t 0 ]; then
                    INPUT=${'$'}(timeout 0.1 cat 2>/dev/null || true)
                    if [ -n "${'$'}INPUT" ]; then
                        EXTRA_ARGS=(--es text "${'$'}INPUT" --es message "${'$'}INPUT" --es content "${'$'}INPUT")
                    fi
                fi

                TMP_DIR="${'$'}{TMPDIR:-/data/data/com.termux/files/usr/tmp}"
                mkdir -p "${'$'}TMP_DIR"
                TMP_OUT="${'$'}TMP_DIR/api_out.${'$'}${'$'}"

                am broadcast --user 0 \
                    -a "com.termux.api" \
                    -n "com.termux/com.example.gemini.data.receiver.TermuxApiReceiver" \
                    --es "api_method" "${'$'}METHOD" \
                    --es "socket_output" "${'$'}TMP_OUT" \
                    "${'$'}{EXTRA_ARGS[@]}" \
                    "${'$'}@" > /dev/null 2>&1

                case "${'$'}METHOD" in
                    Toast|toast|Vibrate|vibrate|Torch|torch|TextToSpeech|tts|tts-speak|Volume|volume|NotificationRemove|notification-remove)
                        ;;
                    *)
                        for i in 1 2 3 4 5 6 7 8 9 10; do
                            if [ -s "${'$'}TMP_OUT" ]; then
                                cat "${'$'}TMP_OUT"
                                echo ""
                                break
                            fi
                            sleep 0.05
                        done
                        ;;
                esac

                rm -f "${'$'}TMP_OUT"
            """.trimIndent()

            dispatcherFile.writeText(scriptContent)
            dispatcherFile.setExecutable(true, false)
            dispatcherFile.setReadable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed ensuring termux-api dispatcher: ${e.message}")
        }
    }

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
        val arch = getBootstrapArch()
        val packageName = context.packageName
        val isTermux = isTermuxPackage(context)
        val info = if (isTermux) {
            DiscoveredPackageInfo(
                url = HARDCODED_ROOTFS_URL,
                releaseTag = HARDCODED_ROOTFS_TAG,
                assetName = HARDCODED_ROOTFS_ASSET,
                arch = arch,
                sizeBytes = HARDCODED_ROOTFS_SIZE_BYTES,
                sizeFormatted = "~350 MB",
                packageName = packageName
            )
        } else {
            DiscoveredPackageInfo(
                url = UBUNTU_ROOTFS_URL,
                releaseTag = UBUNTU_ROOTFS_TAG,
                assetName = UBUNTU_ROOTFS_ASSET,
                arch = "arm64",
                sizeBytes = UBUNTU_ROOTFS_SIZE_BYTES,
                sizeFormatted = "~29 MB",
                packageName = packageName
            )
        }
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
        source: BootstrapSource = BootstrapSource.Auto
    ): Boolean = withContext(Dispatchers.IO) {
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
                    val isTermux = isTermuxPackage(appContext)
                    val targetUrl = if (isTermux) HARDCODED_ROOTFS_URL else UBUNTU_ROOTFS_URL
                    val targetAsset = if (isTermux) HARDCODED_ROOTFS_ASSET else UBUNTU_ROOTFS_ASSET
                    val targetSizeBytes = if (isTermux) HARDCODED_ROOTFS_SIZE_BYTES else UBUNTU_ROOTFS_SIZE_BYTES
                    val titlePrefix = if (isTermux) "AntiGem Rootfs" else "Ubuntu Rootfs"

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
                            val isInnerBootstrapZip = if (isTermuxPackage(appContext)) {
                                (name.endsWith(".zip") && name.contains("bootstrap")) || (name.endsWith(".zip") && !name.contains("__MACOSX"))
                            } else {
                                name.endsWith(".zip") && !name.contains("/")
                            }
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
            if (!isTermuxPackage(appContext)) {
                val ubuntuDir = getUbuntuRootDir(appContext)
                if (ubuntuDir.exists()) {
                    ubuntuDir.deleteRecursively()
                }
                ubuntuDir.mkdirs()
            } else {
                if (prefixDir.exists()) {
                    prefixDir.deleteRecursively()
                }
                prefixDir.mkdirs()
                binDir.mkdirs()
                libDir.mkdirs()
                etcDir.mkdirs()
                tmpDir.mkdirs()
            }

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
                val isUbuntuMode = !isTermuxPackage(appContext)
                val destDir = if (isUbuntuMode) getUbuntuRootDir(appContext) else prefixDir
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
                            val isHomePath = cleanPath.startsWith("home/") || cleanPath.contains("/files/home/")
                            val targetFile = if (isHomePath) {
                                val relHome = cleanPath
                                    .replaceFirst(Regex("^.*?files/home/"), "")
                                    .removePrefix("home/")
                                    .removePrefix("./")
                                if (relHome.isBlank()) null else File(homeDir, relHome)
                            } else if (isUbuntuMode) {
                                val relUbuntu = ubuntuLegacyRelativePath(cleanPath)
                                if (relUbuntu.isBlank() || relUbuntu == "/") null else File(destDir, relUbuntu)
                            } else {
                                val entryName = cleanPath
                                    .replaceFirst(Regex("^.*?files/usr/"), "")
                                    .replaceFirst(Regex("^.*?files/"), "")
                                    .removePrefix("usr/")
                                    .removePrefix("./")
                                if (entryName.isBlank() || entryName == "/") null else File(prefixDir, entryName)
                            }

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
                                    val dirMode = if (isUbuntuMode && entry.mode != 0) {
                                        (entry.mode and 511) or 448
                                    } else if (entry.mode != 0) (entry.mode and 511) or 493 else 493
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
                                        if (isUbuntuMode && unixMode != 0) {
                                            // Exact mode from the rootfs tarball (e.g. /etc/shadow 0640)
                                            try { Os.chmod(targetFile.absolutePath, unixMode) } catch (_: Exception) {}
                                        } else if (isExecutableDir || isExecutablePath || !targetFile.name.contains(".")) {
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
                val isUbuntuMode = !isTermuxPackage(appContext)
                val destDir = if (isUbuntuMode) getUbuntuRootDir(appContext) else prefixDir
                log("Extracting rootfs files from ZIP into ${destDir.absolutePath}...")
                val symlinksFromTxt = mutableListOf<Pair<String, String>>()
                CommonsZipFile(archiveToExtract).use { zip ->
                    val entriesList = zip.entries.toList()
                    val totalEntries = entriesList.size
                    // Rootfs-relative layout (backup_proot_ubuntu): no "ubuntu/" or host ".../files/ubuntu/" prefix
                    val isRootfsRelativeZip = isUbuntuMode && entriesList.none { e ->
                        val n = e.name.removePrefix("./")
                        n.startsWith("ubuntu/") || ubuntuHostPrefixRegex.containsMatchIn(n)
                    }
                    if (isUbuntuMode) log("Ubuntu ZIP layout: ${if (isRootfsRelativeZip) "rootfs-relative" else "ubuntu/ prefixed"}")

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
                        val isHomePath = !isRootfsRelativeZip && (cleanPath.startsWith("home/") || cleanPath.contains("/files/home/"))
                        val targetFile = if (isRootfsRelativeZip) {
                            if (cleanPath.isBlank() || cleanPath == "/") null else File(destDir, cleanPath)
                        } else if (isHomePath) {
                            val relHome = cleanPath
                                .replaceFirst(Regex("^.*?files/home/"), "")
                                .removePrefix("home/")
                                .removePrefix("./")
                            if (relHome.isBlank()) null else File(homeDir, relHome)
                        } else if (isUbuntuMode) {
                            val relUbuntu = ubuntuLegacyRelativePath(cleanPath)
                            if (relUbuntu.isBlank() || relUbuntu == "/") null else File(destDir, relUbuntu)
                        } else {
                            val entryName = cleanPath
                                .replaceFirst(Regex("^.*?files/usr/"), "")
                                .replaceFirst(Regex("^.*?files/"), "")
                                .removePrefix("usr/")
                                .removePrefix("./")
                            if (entryName.isBlank() || entryName == "/") null else File(prefixDir, entryName)
                        }

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
                                    Os.chmod(targetFile.absolutePath, if (isUbuntuMode) dirMode or 448 else dirMode)
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
                                            Os.chmod(targetFile.absolutePath, if (isUbuntuMode) unixMode and 511 else unixMode)
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
                                val isHome = cleanRel.startsWith("home/") || cleanRel.contains("/files/home/")
                                val linkFile = if (isHome) {
                                    val relHome = cleanRel
                                        .replaceFirst(Regex("^.*?files/home/"), "")
                                        .removePrefix("home/")
                                        .removePrefix("./")
                                    if (relHome.isBlank()) null else File(homeDir, relHome)
                                } else if (isUbuntuMode) {
                                    val relUbuntu = ubuntuLegacyRelativePath(cleanRel)
                                    if (relUbuntu.isBlank() || relUbuntu == "/") null else File(destDir, relUbuntu)
                                } else {
                                    val relUsr = cleanRel
                                        .replaceFirst(Regex("^.*?files/usr/"), "")
                                        .replaceFirst(Regex("^.*?files/"), "")
                                        .removePrefix("usr/")
                                        .removePrefix("./")
                                    if (relUsr.isBlank() || relUsr == "/") null else File(prefixDir, relUsr)
                                }

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

            // Clean up downloaded zip
            tempZipFile.delete()

            // STEP 3: Configure Shell Profiles & Environment
            log("Configuring shell profiles (.bashrc) and directory permissions...")
            _installerState.value = LocalInstallerState.Configuring(
                stepDescription = "Configuring shell profiles, paths, and environment...",
                progressFraction = 0.90f
            )
            updateInstallNotification(appContext, "Configuring Linux Environment (90%)", "Configuring paths, permissions, and shell profiles...", 90, force = true)
            
            if (!isTermuxPackage(appContext)) {
                val ubuntuDir = getUbuntuRootDir(appContext)
                setupProotDistroUbuntuFixups(appContext, ubuntuDir)
                log("Finalizing initial dpkg package triggers...")
                executeCommand(
                    command = "DEBIAN_FRONTEND=noninteractive dpkg --configure -a",
                    context = appContext
                )
            } else {
                configureEnvironmentFiles(appContext, prefixDir, binDir, etcDir, homeDir, projectsDir)
            }

            // STEP 4: Strict Verification
            _installerState.value = LocalInstallerState.Verifying(
                testName = "Verifying Linux environment and shell execution..."
            )
            updateInstallNotification(appContext, "Verifying Installation (95%)", "Testing shell environment...", 95, force = true)
            delay(200)

            val checkDir = if (!isTermuxPackage(appContext)) getUbuntuRootDir(appContext) else prefixDir
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

            // Save installation state in preferences
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
            LocalTerminalManager.autoLaunchServerIfReady(appContext)
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

    private val ubuntuHostPrefixRegex = Regex("^(?:.*/)?files/ubuntu/")

    private fun ubuntuLegacyRelativePath(cleanPath: String): String =
        cleanPath
            .replaceFirst(ubuntuHostPrefixRegex, "")
            .replaceFirst(Regex("^ubuntu-[^/]+/"), "")
            .removePrefix("ubuntu/")
            .removePrefix("./")

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
        val assetName = "glibc-min-arm64.tar"
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
            BufferedInputStream(inStream).use { rawStream ->
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
            true
        } catch (e: Exception) {
            val err = "❌ Failed extracting glibc-min asset: ${e.localizedMessage ?: e.message}"
            log(err)
            Log.e(TAG, err, e)
            false
        }
    }

    fun ensureGlibcEnvironment(context: Context): Boolean {
        val prefixDir = getPrefixDir(context)
        val glibcDir = File(prefixDir, "glibc")
        val glibcLib = File(glibcDir, "lib")
        val ldLinux = File(glibcLib, "ld-linux-aarch64.so.1")

        if (!ldLinux.exists()) {
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
                """# Gemini Local Linux Environment
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
PS1='\[\033[01;32m\]gemini\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
""".trimIndent()
            )
            try { Os.chmod(bashrc.absolutePath, 420) } catch (_: Exception) {} // 0644
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
    }

    suspend fun executeCommand(
        command: String,
        context: Context? = null,
        workingDir: String? = null,
        customEnv: Map<String, String>? = null,
        timeoutSeconds: Long = 60
    ): LocalCommandResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val env = mutableMapOf<String, String>()

        if (context != null && !isTermuxPackage(context) && isUbuntuInstalled(context)) {
            val prootBin = getProotBinaryPath(context)
            val ubuntuDir = getUbuntuRootDir(context)
            val homeDir = getHomeDir(context)
            val prootTmpDir = File(context.filesDir, "tmp").apply {
                mkdirs()
                try { Os.chmod(absolutePath, 511) } catch (_: Exception) {}
            }
            val shmDir = File(context.filesDir, "shm").apply {
                mkdirs()
                try { Os.chmod(absolutePath, 511) } catch (_: Exception) {}
            }
            val sysdataBindings = setupFakeSysdata(context)
            val cmdList = mutableListOf(
                prootBin,
                "--kill-on-exit",
                "--link2symlink",
                "--sysvipc",
                "--kernel-release=\\Linux\\localhost\\6.1.0\\2026.08\\aarch64\\localdomain\\-1\\",
                "-L",
                "--change-id=0:0",
                "--rootfs=${ubuntuDir.absolutePath}",
                "--cwd=/root",
                "--bind=/dev",
                "--bind=/proc",
                "--bind=/sys",
                "--bind=/dev/urandom:/dev/random",
                "--bind=${shmDir.absolutePath}:/dev/shm",
                "--bind=${prootTmpDir.absolutePath}:/tmp"
            )
            for ((fakeFile, guestPath) in sysdataBindings) {
                cmdList.add("--bind=${fakeFile.absolutePath}:$guestPath")
            }
            listOf(
                "/apex", "/odm", "/product", "/system", "/system_ext", "/vendor",
                "/plat_property_contexts", "/property_contexts"
            ).forEach { hostPath ->
                if (File(hostPath).exists()) {
                    val destInRootfs = File(ubuntuDir, hostPath.removePrefix("/"))
                    if (File(hostPath).isDirectory) {
                        destInRootfs.mkdirs()
                    } else {
                        destInRootfs.parentFile?.mkdirs()
                        if (!destInRootfs.exists()) {
                            try { destInRootfs.createNewFile() } catch (_: Exception) {}
                        }
                    }
                    cmdList.add("-b")
                    cmdList.add(hostPath)
                }
            }
            if (File("/storage").exists() && File("/storage").canRead()) {
                cmdList.add("-b")
                cmdList.add("/storage")
            }
            if (File("/storage/emulated/0").exists()) {
                cmdList.add("-b")
                cmdList.add("/storage/emulated/0:/sdcard")
            } else if (File("/sdcard").exists()) {
                cmdList.add("-b")
                cmdList.add("/sdcard")
            }
            cmdList.addAll(
                listOf(
                    "-b", "${homeDir.absolutePath}:/root/workspace",
                    "-w", "/root",
                    "/bin/bash", "-c", command
                )
            )
            val processBuilder = ProcessBuilder(cmdList)
            processBuilder.environment()["HOME"] = "/root"
            processBuilder.environment()["USER"] = "root"
            processBuilder.environment()["TERM"] = "xterm-256color"
            processBuilder.environment()["LANG"] = "C.UTF-8"
            processBuilder.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            processBuilder.environment()["TMPDIR"] = "/tmp"
            processBuilder.environment()["DEBIAN_FRONTEND"] = "noninteractive"
            // processBuilder.environment()["PROOT_NO_SECCOMP"] = "1"
            processBuilder.environment()["PROOT_LOADER"] = getProotLoaderPath(context)
            processBuilder.environment()["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${context.filesDir.absolutePath}/lib:${context.filesDir.absolutePath}/bin"
            if (customEnv != null) {
                processBuilder.environment().putAll(customEnv)
            }
            processBuilder.redirectErrorStream(true)
            return@withContext try {
                val process = processBuilder.start()
                val outputBuilder = StringBuilder()
                val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    outputBuilder.append(line).append("\n")
                }
                val exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                val durationMs = System.currentTimeMillis() - startTime
                if (!exited) {
                    process.destroyForcibly()
                    outputBuilder.append("\n[Process timed out after ${timeoutSeconds}s]")
                    LocalCommandResult(exitCode = 124, output = outputBuilder.toString().trim(), durationMs = durationMs)
                } else {
                    LocalCommandResult(exitCode = process.exitValue(), output = outputBuilder.toString().trim(), durationMs = durationMs)
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
            com.example.gemini.data.remote.AgyBridgeService.instance.notifyLocalStopped()
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
            File("/data/data/com.termux/files/usr/bin/bash").exists() -> "/data/data/com.termux/files/usr/bin/bash"
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
