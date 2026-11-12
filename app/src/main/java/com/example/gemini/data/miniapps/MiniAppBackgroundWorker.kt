package com.example.gemini.data.miniapps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.gemini.R
import com.example.gemini.ui.miniapps.BackgroundTask
import com.example.gemini.ui.miniapps.BackgroundTaskHost
import com.example.gemini.ui.miniapps.MiniAppPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/**
 * Runs one background task of a mini app: its background script in an invisible WebView page with the full SDK
 * (fetch, IndexedDB, files, cookies, notifications), then closes it. Limits: the "background" permission, at most
 * MAX_RUNS_PER_DAY runs per app, 60 s per run, one task at a time across all apps, nothing in battery saver
 * (except "Run now"). Every run (or skip) is recorded for App info.
 */
class MiniAppBackgroundWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "MiniAppBackground"
        const val TIME_LIMIT = 60_000L
        private val oneAtATime = Mutex()
        private val NETWORK_ERROR = Regex("Unable to resolve host|Failed to connect|Failed to fetch|timeout|Network is unreachable", RegexOption.IGNORE_CASE)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val appId = inputData.getString("app") ?: return Result.success()
        val name = inputData.getString("task") ?: return Result.success()
        val reason = inputData.getString("reason") ?: "schedule"
        val now = System.currentTimeMillis()
        fun skip(why: String): Result {
            MiniAppScheduler.recordRun(ctx, appId, name, now, 0, "skipped", why, reason)
            // a notification button the script couldn't handle now: the app gets it when it's opened instead
            if (reason == "notification") inputData.getString("data")?.let { d -> runCatching { MiniAppRuntime.queueEvent(appId, "notification", JSONObject(d).put("queued", true)) } }
            if (reason == "schedule") MiniAppScheduler.finishedOnce(ctx, appId, name)
            return Result.success()
        }

        val app = MiniApps.find(ctx, appId) ?: run { MiniAppScheduler.forgetApp(ctx, appId); return Result.success() }
        if (app.backgroundScript == null) return skip("The manifest has no background script")
        if (app.tampered) return skip("The app was modified after it was installed")
        if (!app.signed && !MiniApps.devMode(ctx)) return skip("Developer mode is off (development apps don't run)")
        if (!MiniAppPermissions.isGranted(ctx, app, "background")) return skip("\"background\" permission is not granted")
        MiniAppPermissions.missingRequired(ctx, app).takeIf { it.isNotEmpty() }?.let { return skip("Required permission missing: ${it.joinToString()}") }
        if (MiniAppScheduler.runsLastDay(ctx, appId) >= MiniAppScheduler.MAX_RUNS_PER_DAY) return skip("Daily limit of ${MiniAppScheduler.MAX_RUNS_PER_DAY} runs reached")
        // things the user just did (Run now, a notification button) run even in battery saver
        if (reason != "manual" && reason != "notification" && ctx.getSystemService(PowerManager::class.java).isPowerSaveMode) return skip("Battery saver is on")

        return oneAtATime.withLock {
            val started = System.currentTimeMillis()
            val outcome = withTimeoutOrNull(TIME_LIMIT) { run(ctx, app, name, reason, inputData.getString("data")) } ?: (false to "Stopped after ${TIME_LIMIT / 1000} s")
            val ms = System.currentTimeMillis() - started
            // A network failure on the first attempt is often the device cutting a just-backgrounded process off:
            // retry once through the system scheduler (~30 s later), which gives the run network access.
            val retry = !outcome.first && runAttemptCount == 0 && NETWORK_ERROR.containsMatchIn(outcome.second)
            MiniAppScheduler.recordRun(ctx, appId, name, started, ms, if (outcome.first) "ok" else "error", outcome.second + if (retry) " (retrying)" else "", reason)
            MiniAppConsole.add(ctx, appId, if (outcome.first) "info" else "error",
                "⏱ Background task \"$name\" ($reason) ${if (outcome.first) "finished" else "failed"} in ${ms} ms${if (outcome.second.isNotBlank()) ": ${outcome.second}" else ""}${if (retry) " (retrying)" else ""}", "background")
            if (!retry && reason == "schedule") MiniAppScheduler.finishedOnce(ctx, appId, name)
            if (retry) Result.retry() else Result.success()
        }
    }

    /** Loads the background page and waits for the app's handler to report back. */
    private suspend fun run(ctx: Context, app: MiniApp, name: String, reason: String, data: String?): Pair<Boolean, String> {
        val done = CompletableDeferred<Pair<Boolean, String>>()
        var page: MiniAppPage? = null
        try {
            withContext(Dispatchers.Main) {
                val host = BackgroundTaskHost(ctx) { done.complete(false to "The page crashed") }
                page = MiniAppPage(host, app, "_antigem/background.html", BackgroundTask(name, reason, UUID.randomUUID().toString(), data) { ok, error -> done.complete(ok to error) })
                    .also { it.load() }
            }
            return done.await()
        } catch (e: Exception) {
            Log.w(TAG, "Task $name of ${app.id} failed", e)
            return false to (e.message ?: e.javaClass.simpleName)
        } finally {
            withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { page?.destroy() }
        }
    }

    /** Only used on Android < 12, where expedited work runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("mini_apps_background") == null)
            nm.createNotificationChannel(NotificationChannel("mini_apps_background", "Mini app background tasks", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(applicationContext, "mini_apps_background")
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Mini app task").setContentText(inputData.getString("task").orEmpty())
            .setPriority(NotificationCompat.PRIORITY_MIN).setOngoing(true).build()
        return ForegroundInfo(0x6d1a, n)
    }
}
