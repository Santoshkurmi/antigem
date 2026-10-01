package com.example.gemini.data.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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

sealed class BootstrapSource {
    object Auto : BootstrapSource()
    data class DirectUrl(val url: String) : BootstrapSource()
    data class LocalZipUri(val uri: android.net.Uri) : BootstrapSource()
}

object LocalEnvironmentManager {

    private const val TAG = "LocalEnvironmentManager"
    private const val BOOTSTRAP_VERSION = "2026.08"
    // Minimum 10 MB required for a real Termux bootstrap archive
    private const val MIN_BOOTSTRAP_SIZE_BYTES = 10 * 1024 * 1024L

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
    fun isTermuxPackage(context: Context): Boolean = context.packageName == "com.termux"

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

    fun isInstalled(context: Context): Boolean {
        val binDir = getBinDir(context)
        val homeDir = getHomeDir(context)
        return (binDir.exists() && binDir.isDirectory && (File(binDir, "sh").exists() || File(binDir, "dash").exists() || File(binDir, "bash").exists() || File(binDir, "busybox").exists())) &&
                homeDir.exists()
    }

    fun getInstallPath(context: Context): String {
        return getPrefixDir(context).absolutePath
    }

    fun getFormattedDiskSpace(context: Context): String {
        val prefix = getPrefixDir(context)
        val home = getHomeDir(context)
        var totalBytes = 0L
        if (prefix.exists()) totalBytes += calculateDirectorySize(prefix)
        if (home.exists()) totalBytes += calculateDirectorySize(home)
        return formatFileSize(totalBytes)
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
        _installerState.value = LocalInstallerState.Discovering("Finding latest verified Termux bootstrap release for $arch...")

        // 1. Query GitHub API for latest release
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
                        val tagName = json.optString("tag_name", "latest")
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.getJSONObject(i)
                                val name = asset.optString("name", "")
                                val downloadUrl = asset.optString("browser_download_url", "")
                                val size = asset.optLong("size", 33 * 1024 * 1024L)
                                if (name == "bootstrap-$arch.zip" && downloadUrl.isNotBlank()) {
                                    val info = DiscoveredPackageInfo(
                                        url = downloadUrl,
                                        releaseTag = tagName,
                                        assetName = name,
                                        arch = arch,
                                        sizeBytes = size,
                                        sizeFormatted = formatFileSize(size),
                                        packageName = packageName
                                    )
                                    _installerState.value = LocalInstallerState.AwaitingConfirmation(info)
                                    return@withContext info
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed discovery from GitHub API: ${e.message}")
        }

        // Fallback default
        val fallbackTag = "bootstrap-2026.08.30-r1+apt.android-7"
        val fallbackUrl = "https://github.com/termux/termux-packages/releases/download/${fallbackTag.replace("+", "%2B")}/bootstrap-$arch.zip"
        val fallbackInfo = DiscoveredPackageInfo(
            url = fallbackUrl,
            releaseTag = fallbackTag,
            assetName = "bootstrap-$arch.zip",
            arch = arch,
            sizeBytes = 33 * 1024 * 1024L,
            sizeFormatted = "~33 MB",
            packageName = packageName
        )
        _installerState.value = LocalInstallerState.AwaitingConfirmation(fallbackInfo)
        return@withContext fallbackInfo
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
                                if (finalDownloadedSize >= MIN_BOOTSTRAP_SIZE_BYTES && validateZipIntegrity(tempZipFile)) {
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
                            if (!validateZipIntegrity(tempZipFile)) {
                                val errorMsg = "Downloaded file is not a valid ZIP archive."
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
                        currentPackageName = "Local ZIP Archive"
                    )
                    updateInstallNotification(appContext, "Importing Linux Rootfs", "Importing ZIP file...", -1, force = true)

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
                            val errorMsg = "Selected ZIP file is only ${formatFileSize(finalDownloadedSize)} (< 10 MB minimum required)."
                            tempZipFile.delete()
                            updateInstallNotification(appContext, "Installation Failed", errorMsg, -2, ongoing = false, force = true)
                            _installerState.value = LocalInstallerState.Error(errorMessage = errorMsg, canRetry = true)
                            return@withContext false
                        }
                        if (!validateZipIntegrity(tempZipFile)) {
                            val errorMsg = "The selected file (${formatFileSize(finalDownloadedSize)}) is not a valid or readable ZIP archive."
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

            // Check if this ZIP is a GitHub Actions artifact wrapper containing an inner bootstrap-*.zip or debs.tar.gz
            var innerZipFound: File? = null
            var isDebsOnlyArchive = false

            try {
                java.util.zip.ZipFile(tempZipFile).use { outerZip ->
                    val entries = outerZip.entries()
                    var hasRootfsFiles = false
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if ((name.endsWith(".zip") && name.contains("bootstrap")) || (name.endsWith(".zip") && !name.contains("__MACOSX"))) {
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

            val inner = innerZipFound
            val archiveToExtract = if (inner != null && inner.exists() && inner.length() > 0) {
                tempZipFile.delete()
                inner
            } else {
                tempZipFile
            }

            // STEP 2: Extract verified bootstrap archive
            log("Archive ready: ${formatFileSize(archiveToExtract.length())}, isDebsArchive=$isDebsOnlyArchive")
            Log.d(TAG, "Extracting verified bootstrap archive (${formatFileSize(archiveToExtract.length())}, isDebsArchive=$isDebsOnlyArchive)...")
            _installerState.value = LocalInstallerState.Extracting(
                extractedFilesCount = 0,
                totalFilesEstimate = if (isDebsOnlyArchive) 155 else 800,
                progressFraction = 0.05f,
                currentFileName = "Preparing directory structure..."
            )
            updateInstallNotification(appContext, "Extracting Linux Rootfs (0%)", "Preparing directory structure...", 0, force = true)

            // Reset prefix directory for a clean install
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
            } else {
                log("Extracting rootfs files from ZIP into ${prefixDir.absolutePath}...")
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
                        val isHomePath = cleanPath.startsWith("home/") || cleanPath.contains("/files/home/")
                        val targetFile = if (isHomePath) {
                            val relHome = cleanPath
                                .replaceFirst(Regex("^.*?files/home/"), "")
                                .removePrefix("home/")
                                .removePrefix("./")
                            if (relHome.isBlank()) null else File(homeDir, relHome)
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
                                val isHome = cleanRel.startsWith("home/") || cleanRel.contains("/files/home/")
                                val linkFile = if (isHome) {
                                    val relHome = cleanRel
                                        .replaceFirst(Regex("^.*?files/home/"), "")
                                        .removePrefix("home/")
                                        .removePrefix("./")
                                    if (relHome.isBlank()) null else File(homeDir, relHome)
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
            configureEnvironmentFiles(appContext, prefixDir, binDir, etcDir, homeDir, projectsDir)

            // STEP 4: Strict Verification
            _installerState.value = LocalInstallerState.Verifying(
                testName = "Verifying Termux binaries and shell execution..."
            )
            updateInstallNotification(appContext, "Verifying Installation (95%)", "Testing shell environment...", 95, force = true)
            delay(200)

            val installedSize = calculateDirectorySize(prefixDir)
            val installedSizeStr = formatFileSize(installedSize)
            log("Total installed prefix size: $installedSizeStr ($installedSize bytes)")

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
                command = "echo 'GEMINI_LOCAL_TOOLS_OK' && pwd && (which bash || which sh || echo 'sh_ok')",
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

    private fun validateZipIntegrity(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_BOOTSTRAP_SIZE_BYTES) {
            Log.w(TAG, "File size check failed: ${file.length()} bytes (< 10 MB required)")
            return false
        }
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val count = zip.size()
                Log.d(TAG, "validateZipIntegrity: ZIP contains $count entries")
                count > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "ZIP integrity error: ${e.message}", e)
            try {
                ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zis ->
                    zis.nextEntry != null
                }
            } catch (_: Exception) {
                false
            }
        }
    }

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
export PATH="${binDir.absolutePath}:${binDir.absolutePath}/applets:/system/bin:/system/xbin"
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
export PATH="${binDir.absolutePath}:${binDir.absolutePath}/applets:/system/bin:/system/xbin"
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

        var resolvedWorkingDir = workingDir
        if (context != null) {
            val prefix = getPrefixDir(context)
            val bin = getBinDir(context)
            val lib = getLibDir(context)
            val home = getHomeDir(context)
            val tmp = getTmpDir(context)

            env["PREFIX"] = prefix.absolutePath
            env["HOME"] = home.absolutePath
            env["PATH"] = "${bin.absolutePath}:${bin.absolutePath}/applets:/system/bin:/system/xbin"
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

    suspend fun resetEnvironment(context: Context) = withContext(Dispatchers.IO) {
        Log.d(TAG, "[Reset] Initiating complete rootfs reset...")
        // 1. Force kill local server runner and all child processes
        try {
            LocalServerManager.forceKillAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping local server during reset: ${e.message}")
        }

        // 2. Close and terminate all active terminal sessions
        try {
            LocalTerminalManager.closeAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing terminal sessions during reset: ${e.message}")
        }

        // 3. Reset auto-start flag so next installation can immediately auto-start server
        LocalServerManager.resetAutoStartFlag()

        // 4. Notify bridge service that local server is offline
        try {
            com.example.gemini.data.remote.AgyBridgeService.instance.notifyLocalStopped()
        } catch (_: Exception) {}

        // 5. Short delay for process termination and file descriptor release
        delay(300)

        val appContext = context.applicationContext
        val prefix = getPrefixDir(appContext)
        val home = getHomeDir(appContext)
        val projects = getProjectsDir(appContext)
        val tmp = getTmpDir(appContext)

        // Delete bootstrap rootfs and user folders strictly within filesDir
        try { if (prefix.exists()) prefix.deleteRecursively() } catch (e: Exception) { Log.w(TAG, "Failed deleting prefix: ${e.message}") }
        try { if (home.exists()) home.deleteRecursively() } catch (e: Exception) { Log.w(TAG, "Failed deleting home: ${e.message}") }
        try { if (projects.exists()) projects.deleteRecursively() } catch (e: Exception) { Log.w(TAG, "Failed deleting projects: ${e.message}") }
        try { if (tmp.exists()) tmp.deleteRecursively() } catch (e: Exception) { Log.w(TAG, "Failed deleting tmp: ${e.message}") }

        // Clean any stray files/directories created in filesDir (excluding internal datastore and profileinstaller)
        val filesDir = appContext.filesDir
        try {
            filesDir.listFiles()?.forEach { file ->
                val name = file.name
                if (name != "datastore" && !name.startsWith("profile")) {
                    try { file.deleteRecursively() } catch (e: Exception) { Log.w(TAG, "Failed deleting stray file $name: ${e.message}") }
                }
            }
        } catch (_: Exception) {}

        // Clean all temporary caches, package archives, and downloaded debs in cacheDir
        try {
            val cacheDir = appContext.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                try { file.deleteRecursively() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Clean external cache if present
        try {
            appContext.externalCacheDir?.listFiles()?.forEach { file ->
                try { file.deleteRecursively() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        updateInstallNotification(appContext, "", "", -2, ongoing = false, force = true)
        _installerState.value = LocalInstallerState.Idle
        clearLogs()
        Log.d(TAG, "Local environment bootstrap files cleared and reset.")
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
