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
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "app_update_check"
private const val CHANNEL_ID = "app_updates"
private const val NOTIFICATION_ID = 7301
private const val PREFS = "app_update_prefs"
private const val KEY_LAST_NOTIFIED = "last_notified_version_code"

/**
 * Checks version.json once every 12 hours in the background and posts a notification
 * when a newer release is published. Each version is notified only once.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Failures (offline, rate limit) are ignored; the next 12h run tries again.
        val info = AppUpdateManager(applicationContext).checkForUpdates().getOrNull() ?: return Result.success()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAST_NOTIFIED, 0) >= info.versionCode) return Result.success()

        if (showNotification(info)) {
            prefs.edit().putInt(KEY_LAST_NOTIFIED, info.versionCode).apply()
        }
        return Result.success()
    }

    private fun showNotification(info: AppUpdateInfo): Boolean {
        val context = applicationContext
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "App Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifies when a new AntiGem version is available"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        // Opening the app runs the existing launch-time update check, which shows the update dialog.
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Update available: v${info.versionName}")
            .setContentText(info.changelog.firstOrNull() ?: "Tap to update AntiGem")
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.changelog.joinToString("\n") { "• $it" }))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
