package com.example.gemini.data.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.gemini.R
import com.example.gemini.ui.browser.BrowserExtension
import com.example.gemini.ui.browser.BrowserExtensionManager
import com.example.gemini.ui.browser.PluginRepositories
import com.example.gemini.ui.browser.PluginVersions
import com.example.gemini.ui.browser.RepoPlugin
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "plugin_update_check"
private const val CHANNEL_ID = "plugin_updates"
private const val PREFS = "plugin_update_prefs"

/**
 * Checks the plugin repositories once every 12 hours and posts one notification per installed plugin that has a newer
 * version in the repository it was installed from. Each plugin version is notified only once.
 */
class PluginUpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        PluginRepositories.init(context)
        // Both need the IDE bridge; when it is not running, the next 12h run tries again.
        val installed = runCatching { BrowserExtensionManager.instance.loadExtensions() }.getOrNull() ?: return Result.success()
        if (installed.none { it.source != null }) return Result.success()
        runCatching { PluginRepositories.refreshAll() }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (extension in installed) {
            val offered = PluginRepositories.updateFor(extension) ?: continue
            if (prefs.getString(extension.id, null) == offered.version) continue
            if (showNotification(extension, offered)) prefs.edit().putString(extension.id, offered.version).apply()
        }
        return Result.success()
    }

    private fun showNotification(extension: BrowserExtension, offered: RepoPlugin): Boolean {
        val context = applicationContext
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Plugin Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifies when an installed plugin has a new version in its repository"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val name = extension.name.ifBlank { extension.folderName }
        val change = "${PluginVersions.label(extension.version)} → ${PluginVersions.label(offered.version)}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Plugin update: $name")
            .setContentText("$change · update it in Settings → Plugins")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$change is available from ${extension.source?.repo}." +
                    (if (offered.description.isNotBlank()) "\n${offered.description}" else "") +
                    "\nUpdate it in Settings → Plugins."
            ))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return try {
            // One notification per plugin: a newer version replaces that plugin's previous one
            manager.notify("plugin_update", extension.id.hashCode(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PluginUpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
