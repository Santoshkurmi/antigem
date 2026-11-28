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
// the version_code beta updates were turned on at (on a beta build: the beta's own, kept up to date by offNoticeDue)
private const val KEY_BETA_ON_AT = "beta_updates_on_at"
// the version_code whose "beta updates turned off" notice was shown
private const val KEY_BETA_OFF_NOTICE = "beta_updates_off_notice"
// before: a plain on/off kept for good; read once by offNoticeDue, then removed
private const val KEY_BETA_UPDATES_OLD = "beta_updates"

/**
 * "Get beta updates" (Settings > Updates). It lasts until the next stable release: a beta build always gets betas
 * (the switch can't be turned off there), and a stable build only while it is still the version the switch was turned
 * on at. So installing any newer stable version turns it off by itself, and the user turns it on again to get the
 * betas after it: no one stays on betas only because the switch was once on.
 */
object BetaUpdates {
    private fun prefs(context: Context) = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        BuildConfig.IS_BETA || prefs(context).getInt(KEY_BETA_ON_AT, -1) == BuildConfig.VERSION_CODE

    /** Only on a stable build (on a beta the switch is locked on). */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().apply {
            if (enabled) putInt(KEY_BETA_ON_AT, BuildConfig.VERSION_CODE) else remove(KEY_BETA_ON_AT)
        }.apply()
    }

    /** Beta updates were on for an older version and turned off by themselves when this stable version was installed. */
    fun turnedOffByStable(context: Context): Boolean {
        if (BuildConfig.IS_BETA) return false
        val prefs = prefs(context)
        return prefs.contains(KEY_BETA_ON_AT) && prefs.getInt(KEY_BETA_ON_AT, -1) != BuildConfig.VERSION_CODE
    }

    /**
     * At app start (MainActivity): true on the first start of the stable version that turned beta updates off, so
     * the app tells the user once ([offNoticeShown] after). On a beta it notes the beta, so the next stable one tells.
     */
    fun offNoticeDue(context: Context): Boolean {
        val prefs = prefs(context)
        prefs.edit().apply {
            if (prefs.contains(KEY_BETA_UPDATES_OLD)) {
                // turned on before this version (old switch): it turned off with this update too
                if (prefs.getBoolean(KEY_BETA_UPDATES_OLD, false) && !prefs.contains(KEY_BETA_ON_AT)) putInt(KEY_BETA_ON_AT, 0)
                remove(KEY_BETA_UPDATES_OLD)
            }
            if (BuildConfig.IS_BETA) putInt(KEY_BETA_ON_AT, BuildConfig.VERSION_CODE)
        }.apply()
        return turnedOffByStable(context) && prefs.getInt(KEY_BETA_OFF_NOTICE, -1) != BuildConfig.VERSION_CODE
    }

    fun offNoticeShown(context: Context) {
        prefs(context).edit().putInt(KEY_BETA_OFF_NOTICE, BuildConfig.VERSION_CODE).apply()
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
                response.close()
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
     * the stable one. Once that stable one is installed beta updates are off again ([BetaUpdates]).
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
                response.close()
                return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty download body"))
            val totalBytes = body.contentLength()

            // private cache, not externalCacheDir: on Android 10 and older other apps could swap the file there before
            // the installer opens it (it reaches the installer through the FileProvider either way)
            val targetDir = context.cacheDir
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
