package com.example.gemini.data.automation

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * Manages native Android app launching, querying, and foreground detection.
 */
class AndroidAppManager(private val context: Context) {

    companion object {
        private const val TAG = "AndroidAppManager"
    }

    data class AppInfo(
        val name: String,
        val packageName: String,
        val isSystemApp: Boolean
    )

    /**
     * Checks whether the Accessibility Service is enabled in Android Settings.
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "${context.packageName}/${AndroidAutomationService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(expectedServiceName, ignoreCase = true) }
    }

    /**
     * Checks whether the Usage Stats Access permission is granted.
     */
    fun isUsageAccessGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * List all launchable installed apps on the device.
     */
    fun getInstalledApps(includeSystemApps: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val apps = mutableListOf<AppInfo>()

        for (resolveInfo in resolveInfos) {
            val appInfo = resolveInfo.activityInfo.applicationInfo
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystemApps && isSystem) {
                // Keep useful Google / system apps like YouTube, Chrome, Maps, Settings, Camera, Clock
                val name = resolveInfo.loadLabel(pm).toString()
                val pkg = appInfo.packageName.lowercase()
                val isEssential = pkg.contains("chrome") || pkg.contains("youtube") || pkg.contains("camera") ||
                        pkg.contains("gallery") || pkg.contains("photos") || pkg.contains("clock") ||
                        pkg.contains("calendar") || pkg.contains("calculator") || pkg.contains("settings") ||
                        pkg.contains("dialer") || pkg.contains("messaging") || pkg.contains("music")
                if (!isEssential) continue
            }

            val appName = resolveInfo.loadLabel(pm).toString()
            apps.add(
                AppInfo(
                    name = appName,
                    packageName = appInfo.packageName,
                    isSystemApp = isSystem
                )
            )
        }

        return apps.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }

    /**
     * Finds the best matching app by name or package name.
     */
    fun findApp(query: String): AppInfo? {
        val qLower = query.lowercase().trim()
        val allApps = getInstalledApps(includeSystemApps = true)

        // 1. Exact package match
        allApps.find { it.packageName.equals(qLower, ignoreCase = true) }?.let { return it }

        // 2. Exact name match
        allApps.find { it.name.equals(qLower, ignoreCase = true) }?.let { return it }

        // 3. Name starts with query
        allApps.find { it.name.lowercase().startsWith(qLower) }?.let { return it }

        // 4. Name contains query
        allApps.find { it.name.lowercase().contains(qLower) }?.let { return it }

        // 5. Package contains query
        allApps.find { it.packageName.lowercase().contains(qLower) }?.let { return it }

        return null
    }

    /**
     * Launch an application by name or package name.
     */
    fun launchApp(query: String): Result<String> {
        val targetApp = findApp(query)
        val packageName = targetApp?.packageName ?: query.trim()

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return Result.failure(Exception("Could not find launch intent for app '$query' (package: $packageName)"))

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        return try {
            context.startActivity(launchIntent)
            val displayName = targetApp?.name ?: packageName
            Result.success("Successfully launched $displayName ($packageName)")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app $packageName", e)
            Result.failure(e)
        }
    }

    /**
     * Retrieve recent / foreground apps using UsageStatsManager.
     */
    fun getRecentApps(limit: Int = 10): List<String> {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 1000 * 60 * 60, now) // past 1 hour
        val recentPackages = mutableListOf<String>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val pkg = event.packageName
                if (!pkg.isNullOrBlank() && pkg != context.packageName) {
                    recentPackages.remove(pkg)
                    recentPackages.add(0, pkg)
                }
            }
        }

        val pm = context.packageManager
        return recentPackages.take(limit).map { pkg ->
            val label = try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg
            }
            "$label ($pkg)"
        }
    }
}
