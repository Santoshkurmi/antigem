package ai.antigem.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import ai.antigem.BuildConfig
import ai.antigem.data.local.StorageSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import ai.antigem.i18n.L

private const val TAG = "AppUpdateManager"
private const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/santoshkurmi/antigem/standalone/version.json"
// the updater's prefs (UpdateCheckWorker keeps its "last notified" version there too)
private const val UPDATE_PREFS = "app_update_prefs"
private const val KEY_BETA_UPDATES = "beta_updates"

/**
 * "Get beta updates" (Settings > About). Saved in the app's data, so it stays through updates: someone who turns it on
 * keeps getting each beta, and the stable releases in between, for good. Never set, it follows the installed APK: a
 * beta build gets betas, a stable build doesn't (so a tester who installed a beta from a link goes back to stable-only
 * once the stable release has replaced it).
 */
object BetaUpdates {
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_BETA_UPDATES)) prefs.getBoolean(KEY_BETA_UPDATES, false) else BuildConfig.IS_BETA
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BETA_UPDATES, enabled).apply()
    }
}

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val downloadUrl: String,
    val changelog: List<String>,
    val isCritical: Boolean = false
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()


    /**
     * Checks if a new version is available by fetching centralized version.json from GitHub.
     */
    suspend fun checkForUpdates(
        versionJsonUrl: String = DEFAULT_VERSION_URL,
        forceCheck: Boolean = false
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        // a debug build is its own app (ai.antidev, debug key): a release APK can't install over it, so it never checks
        if (BuildConfig.DEBUG) return@withContext Result.failure(Exception("Update checks are off in debug builds."))
        try {
            val request = Request.Builder()
                .url(versionJsonUrl)
                .header("User-Agent", "antiGem-Updater/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    404 -> L.updater.appUpdateManager.versionJsonNotFoundOnGithub
                    403 -> L.updater.appUpdateManager.githubRateLimitExceededOrAccess
                    else -> "Failed to fetch version info: HTTP ${response.code} (${response.message})"
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            val rawJson = response.body?.string() ?: return@withContext Result.failure(Exception("Empty version response from server"))

            val json = JSONObject(rawJson)
            val release = releaseFor(json)
            val remoteVersionCode = release.code
            val remoteVersionName = release.name
            val minSupportedCode = json.optInt("min_supported_version_code", 0)
            val changelog = release.changelog
            val isCritical = json.optBoolean("is_critical", false) || (BuildConfig.VERSION_CODE < minSupportedCode)

            // each release (tag v<version>) has the app as AntiGem-v<version>.apk, the chroot build as
            // AntiGem-v<version>-chroot.apk and the x86_64 build as AntiGem-v<version>-x86_64.apk
            // (scripts/build_all_apks.sh): an install keeps getting its own kind
            val downloadUrl = apkUrl(remoteVersionName, BuildConfig.APK_SUFFIX)

            if (remoteVersionCode > BuildConfig.VERSION_CODE && downloadUrl.isNotBlank()) {
                // Verify that the APK asset is actually published and downloadable on GitHub
                if (!isPublished(downloadUrl)) {
                    // Release is not yet fully published or APK is still building on CI
                    return@withContext Result.success(null)
                }

                Result.success(
                    AppUpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = remoteVersionName,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        downloadUrl = downloadUrl,
                        changelog = changelog,
                        isCritical = isCritical
                    )
                )
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** One release in version.json: the stable one (top level) or the beta ("beta" block). */
    private class RemoteRelease(val code: Int, val name: String, val changelog: List<String>)

    /**
     * The release this install follows in version.json. Without beta updates ([BetaUpdates]) it reads only the top
     * level, as every installed app always has, so it never sees a beta. With them it takes the newer (higher
     * version_code) of the stable release and the "beta" block: each next beta and, once a stable release passes it,
     * the stable one. Turned off on a beta, the app gets nothing until the next stable release, which is higher.
     */
    private fun releaseFor(json: JSONObject): RemoteRelease {
        val stable = RemoteRelease(json.optInt("version_code", 0), json.optString("version_name", "1.0.0"), changelogOf(json))
        if (!BetaUpdates.isEnabled(context)) return stable
        val beta = json.optJSONObject("beta") ?: return stable
        val betaCode = beta.optInt("version_code", 0)
        val betaName = beta.optString("version_name", "")
        return if (betaCode > stable.code && betaName.isNotBlank()) RemoteRelease(betaCode, betaName, changelogOf(beta)) else stable
    }

    private fun changelogOf(json: JSONObject): List<String> = when (val raw = json.opt("changelog")) {
        is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
        is String -> listOf(raw)
        else -> listOf(L.updater.appUpdateManager.generalImprovementsAndBugFixes)
    }

    private fun apkUrl(versionName: String, suffix: String) =
        "https://github.com/santoshkurmi/antigem/releases/download/v$versionName/AntiGem-v$versionName$suffix.apk"

    /** Whether the release file is on GitHub (a release is published before CI has uploaded its APKs). */
    private fun isPublished(url: String): Boolean = try {
        val probe = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "antiGem-Updater/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(probe).execute().use { it.isSuccessful || it.isRedirect }
    } catch (_: Exception) {
        false
    }

    /**
     * The chroot build (AntiGem-v<version>-chroot.apk) for an install of the normal build, which has no Ubuntu chroot:
     * of the newest release when it has one (with beta updates on: the newest beta, see [releaseFor]), else of this app's
     * version. Same app and signing key, so it installs over this one and keeps all data.
     */
    suspend fun findChrootBuild(versionJsonUrl: String = DEFAULT_VERSION_URL): Result<String> = withContext(Dispatchers.IO) {
        try {
            val latest = runCatching {
                val request = Request.Builder()
                    .url(versionJsonUrl)
                    .header("User-Agent", "antiGem-Updater/${BuildConfig.VERSION_NAME}")
                    .header("Cache-Control", "no-cache")
                    .build()
                httpClient.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) null else releaseFor(JSONObject(r.body?.string().orEmpty())).let { it.code to it.name }
                }
            }.getOrNull()
            val versions = buildList {
                if (latest != null && latest.first >= BuildConfig.VERSION_CODE && latest.second.isNotBlank()) add(latest.second)
                add(BuildConfig.VERSION_NAME)
            }.distinct()
            val url = versions.map { apkUrl(it, "-chroot") }.firstOrNull { isPublished(it) }
            if (url != null) Result.success(url)
            else Result.failure(Exception("The chroot build of AntiGem ${versions.first()} is not on GitHub yet. Try again later, or open the releases page."))
        } catch (e: Exception) {
            Log.e(TAG, "Chroot build lookup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file to cache directory with live progress updates.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var targetFile: File? = null
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "antiGem-Updater/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty download body"))
            val totalBytes = body.contentLength()

            val targetDir = context.externalCacheDir ?: context.cacheDir
            // the APK, and Android's installer copies it once more while it installs
            if (totalBytes > 0) {
                StorageSpace.shortage(targetDir, totalBytes * 2 + StorageSpace.MARGIN_BYTES, L.updater.appUpdateManager.theUpdate)?.let {
                    response.close()
                    return@withContext Result.failure(Exception(it))
                }
            }
            val apkFile = File(targetDir, "antiGem_update_${System.currentTimeMillis()}.apk")
            targetFile = apkFile

            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var downloadedBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val progress = if (totalBytes > 0) {
                            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(progress, downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download APK error: ${e.message}", e)
            if (StorageSpace.isOutOfSpace(e)) {
                targetFile?.delete()
                return@withContext Result.failure(Exception(StorageSpace.outOfSpaceMessage(L.updater.appUpdateManager.theUpdate)))
            }
            Result.failure(e)
        }
    }

    /**
     * Launches the system Package Installer using FileProvider.
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Android 8.0+ Unknown sources permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return false
                }
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }
}
