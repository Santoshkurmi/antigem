package ai.antigem.data.updater

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
import ai.antigem.BuildConfig
import ai.antigem.R
import java.util.concurrent.TimeUnit
import ai.antigem.i18n.L

private const val WORK_NAME = "app_update_check"
private const val CHANNEL_ID = "app_updates"
private const val NOTIFICATION_ID = 7301
private const val PREFS = "app_update_prefs"
private const val KEY_LAST_NOTIFIED = "last_notified_version_code"
private const val KEY_BACKGROUND_ON = "background_check"
private const val KEY_BACKGROUND_EVERY = "background_check_every"
private const val KEY_BACKGROUND_UNIT = "background_check_unit"

/**
 * How often the background check runs (Settings > Updates): every 12 hours unless the user picks another number of
 * hours, days or weeks, or turns it off. Only the background one: opening the app always checks (MainActivity).
 */
object BackgroundUpdateCheck {
    enum class IntervalUnit(private val labelText: () -> String, val hours: Long) { HOURS({ L.updater.updateCheckWorker.hours }, 1), DAYS({ L.updater.updateCheckWorker.days }, 24), WEEKS({ L.updater.updateCheckWorker.weeks }, 168);
        val label: String get() = labelText()
    }

    data class Interval(val enabled: Boolean = true, val every: Int = 12, val unit: IntervalUnit = IntervalUnit.HOURS) {
        val hours: Long get() = every.toLong() * unit.hours
    }

    /** The largest number the setting takes, in any unit. */
    const val MAX_EVERY = 999

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): Interval {
        val p = prefs(context)
        val unit = IntervalUnit.entries.firstOrNull { it.name == p.getString(KEY_BACKGROUND_UNIT, null) } ?: IntervalUnit.HOURS
        return Interval(p.getBoolean(KEY_BACKGROUND_ON, true), p.getInt(KEY_BACKGROUND_EVERY, 12).coerceIn(1, MAX_EVERY), unit)
    }

    /** Saves the setting and schedules (or cancels) the background check to match. */
    fun set(context: Context, interval: Interval) {
        prefs(context).edit()
            .putBoolean(KEY_BACKGROUND_ON, interval.enabled)
            .putInt(KEY_BACKGROUND_EVERY, interval.every.coerceIn(1, MAX_EVERY))
            .putString(KEY_BACKGROUND_UNIT, interval.unit.name)
            .apply()
        UpdateCheckWorker.schedule(context)
    }
}

/**
 * Checks version.json in the background (every 12 hours unless changed in Settings > Updates, see
 * [BackgroundUpdateCheck]) and posts a notification when a newer release is published. Each version is notified only once.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // turned off while this run was already queued
        if (!BackgroundUpdateCheck.get(applicationContext).enabled) return Result.success()
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
                description = L.updater.updateCheckWorker.notifiesWhenANewAntigemVersion
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
            .setContentTitle(L.updater.updateCheckWorker.updateAvailableVVersionname(info.versionName))
            .setContentText(info.changelog.firstOrNull() ?: L.updater.updateCheckWorker.tapToUpdateAntigem)
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
        /** Schedules the background check as set in [BackgroundUpdateCheck], or cancels it when that is off. */
        fun schedule(context: Context) {
            val interval = BackgroundUpdateCheck.get(context)
            val workManager = WorkManager.getInstance(context)
            // debug builds never check for updates (see AppUpdateManager.checkForUpdates)
            if (!interval.enabled || BuildConfig.DEBUG) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(interval.hours, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // UPDATE: a changed interval replaces the scheduled one without restarting its period (the next check
            // comes that long after the last one); the same interval, at every app start, changes nothing
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
