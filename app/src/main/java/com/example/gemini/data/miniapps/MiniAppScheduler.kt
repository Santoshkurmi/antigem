package com.example.gemini.data.miniapps

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Everything a mini app schedules, kept natively (AntiGem's private files, not reachable by any app) so it
 * survives restarts and reboots:
 *  - reminders: notifications at exact times, with repeat rules (no app code runs)
 *  - background tasks: the app's background script runs with the app closed, periodically (WorkManager),
 *    after a delay, or at exact times (AlarmManager → WorkManager)
 * Alarms are set again after a reboot, an AntiGem update, a time or time-zone change, and whenever AntiGem starts.
 */
object MiniAppScheduler {
    private const val TAG = "MiniAppScheduler"
    const val MAX_REMINDERS = 64
    const val MAX_TASKS = 10
    const val MIN_INTERVAL = 15 * 60_000L
    const val MAX_RUNS_PER_DAY = 48
    /** A reminder missed while the phone was off still shows if it is at most this late. */
    private const val MISSED_GRACE = 6 * 3600_000L
    private const val ACTION_REMINDER = "com.example.gemini.MINI_APP_REMINDER"
    private const val ACTION_TASK = "com.example.gemini.MINI_APP_TASK"

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------ storage

    private class Db(context: Context) : SQLiteOpenHelper(context, File(context.filesDir, "miniapps").also { it.mkdirs() }.resolve("scheduler.db").path, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE reminders (app TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, rule TEXT NOT NULL, next_at INTEGER NOT NULL, exact INTEGER NOT NULL, opts TEXT, PRIMARY KEY (app, id))")
            db.execSQL("CREATE TABLE tasks (app TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, spec TEXT NOT NULL, next_at INTEGER NOT NULL, PRIMARY KEY (app, name))")
            db.execSQL("CREATE TABLE runs (app TEXT NOT NULL, task TEXT NOT NULL, started INTEGER NOT NULL, ms INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, reason TEXT NOT NULL)")
            db.execSQL("CREATE INDEX runs_app ON runs (app, started)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2: the notification's full options (color, buttons, style …) are kept with the reminder
            if (oldVersion < 2) db.execSQL("ALTER TABLE reminders ADD COLUMN opts TEXT")
        }
    }

    @Volatile private var db: Db? = null
    private fun db(context: Context): SQLiteDatabase = (db ?: synchronized(this) { db ?: Db(context.applicationContext).also { db = it } }).writableDatabase

    // ------------------------------------------------------------------ repeat rules

    /**
     * k: once | every | daily | days. a = first time (epoch ms), i = interval (every), t = "HH:MM" (daily/days),
     * d = days of week (Calendar.SUNDAY = 1 … SATURDAY = 7), u = until (epoch ms), n = times left.
     */
    class Rule(val k: String, val a: Long, val i: Long = 0, val t: String = "", val d: List<Int> = emptyList(), val u: Long = 0, var n: Int = 0) {
        fun toJson(): JSONObject = JSONObject().put("k", k).put("a", a).put("i", i).put("t", t).put("d", JSONArray(d)).put("u", u).put("n", n)

        /** First occurrence strictly after [after], or null when the rule has ended. */
        fun next(after: Long): Long? {
            val at = when (k) {
                "once" -> a.takeIf { it > after }
                "every" -> if (a > after) a else a + ((after - a) / i + 1) * i
                "daily" -> nextTime(after) { true }
                "days" -> nextTime(after) { it in d }
                else -> null
            } ?: return null
            return at.takeIf { u == 0L || it <= u }
        }

        private fun nextTime(after: Long, dayOk: (Int) -> Boolean): Long? {
            val (h, m) = t.split(':').let { (it[0].toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            val c = Calendar.getInstance().apply { timeInMillis = maxOf(after, a - 1) }
            for (day in 0..8) {
                val cand = (c.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, day)
                    set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                if (cand.timeInMillis > after && dayOk(cand.get(Calendar.DAY_OF_WEEK))) return cand.timeInMillis
            }
            return null
        }

        val timeOfDay get() = k == "daily" || k == "days"

        fun describe(): String = when (k) {
            "once" -> "once"
            "every" -> "every ${duration(i)}"
            "daily" -> "daily at $t"
            else -> d.joinToString(",") { DAY_NAMES[it - 1] } + " at $t"
        }

        companion object {
            fun from(o: JSONObject) = Rule(o.optString("k"), o.optLong("a"), o.optLong("i"), o.optString("t"),
                o.optJSONArray("d")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty(), o.optLong("u"), o.optInt("n"))
        }
    }

    private val DAY_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
    private val HHMM = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    private fun duration(ms: Long): String = when {
        ms > 0 && ms % 86_400_000L == 0L -> "${ms / 86_400_000L}d"
        ms > 0 && ms % 3_600_000L == 0L -> "${ms / 3_600_000L}h"
        ms >= 60_000L && ms % 60_000L == 0L -> "${ms / 60_000L}m"
        ms >= 60_000L -> "${ms / 60_000L}m ${(ms % 60_000L) / 1000}s"
        else -> "${(ms + 999) / 1000}s"
    }

    /** "15m", "2h", "1d", "90s", or a number of ms. */
    fun parseDuration(v: Any?, unit: String? = null): Long {
        if (v is Number) {
            val n = v.toDouble()
            return (when (unit?.lowercase()?.trimEnd('s')) { "minute", "min" -> n * 60_000; "hour" -> n * 3_600_000; "day" -> n * 86_400_000; "week" -> n * 604_800_000; else -> n }).toLong()
        }
        val m = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|s|sec|m|min|h|hr|hour|d|day|w|week)s?\\s*$", RegexOption.IGNORE_CASE).find(v?.toString().orEmpty())
            ?: throw MiniAppError("TypeError", "Use a duration like \"15m\", \"2h\" or \"1d\" (got $v)")
        val n = m.groupValues[1].toDouble()
        return (n * when (m.groupValues[2].lowercase()) { "ms" -> 1.0; "s", "sec" -> 1e3; "m", "min" -> 6e4; "h", "hr", "hour" -> 3.6e6; "d", "day" -> 8.64e7; else -> 6.048e8 }).toLong()
    }

    private fun parseDays(a: JSONArray): List<Int> = (0 until a.length()).map { i ->
        val v = a.opt(i)
        when {
            v is Number -> v.toInt().also { if (it !in 0..6) throw MiniAppError("TypeError", "Days are 0 (Sunday) to 6") } + 1
            else -> DAY_NAMES.indexOf(v.toString().lowercase().take(3)).takeIf { it >= 0 }?.plus(1) ?: throw MiniAppError("TypeError", "Unknown day \"$v\"")
        }
    }.distinct().sorted()

    /** Local "YYYY-MM-DD[THH:MM[:SS]]" (or with Z / an offset). */
    private fun parseDateTime(s: String): Long? {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?$").find(s.trim()) ?: return null
        val g = m.groupValues
        val tz = when {
            g[7].isEmpty() -> java.util.TimeZone.getDefault()
            g[7] == "Z" -> java.util.TimeZone.getTimeZone("UTC")
            else -> java.util.TimeZone.getTimeZone("GMT" + g[7].let { if (it.contains(':')) it else it.substring(0, 3) + ":" + it.substring(3) })
        }
        return Calendar.getInstance(tz).apply {
            clear(); set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toIntOrNull() ?: 0, g[5].toIntOrNull() ?: 0, g[6].toIntOrNull() ?: 0)
        }.timeInMillis
    }

    private fun hhmmOf(ms: Long): String = Calendar.getInstance().apply { timeInMillis = ms }.let { "%02d:%02d".format(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
    private fun dayOf(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)

    /**
     * From the JS options: at (epoch ms | "HH:MM" | "YYYY-MM-DDTHH:MM") or delay (ms), and repeat:
     * "daily" | "weekly" | "weekdays" | "hourly" | "2h" | {every, unit} | {days: ["mon", …], time?} | {…, until, count}.
     */
    fun parseRule(p: JSONObject, now: Long = System.currentTimeMillis()): Rule {
        val at = p.opt("at")
        val repeat = p.opt("repeat")?.takeIf { it != JSONObject.NULL && it != false }
        val ro = repeat as? JSONObject
        // repeating every N (rather than at times of day)?
        val interval: Long? = when {
            repeat == "hourly" -> 3_600_000L
            ro != null && ro.has("every") -> parseDuration(ro.opt("every"), ro.optString("unit"))
            repeat is Number -> parseDuration(repeat)
            repeat is String && repeat !in setOf("daily", "weekly", "weekdays") -> parseDuration(repeat)
            else -> null
        }
        var time = ""
        val anchor: Long = when {
            at is Number -> at.toLong()
            at is String && HHMM.matches(at.trim()) -> { time = at.trim().padStart(5, '0'); Rule("daily", 0, t = time).next(now)!! }
            at is String -> parseDateTime(at) ?: throw MiniAppError("TypeError", "\"at\" must be epoch ms, \"HH:MM\" or \"YYYY-MM-DDTHH:MM\" (got $at)")
            p.has("delay") -> now + parseDuration(p.opt("delay"))
            interval != null -> now + interval
            repeat != null -> now
            else -> throw MiniAppError("TypeError", "Give \"at\" or \"delay\"")
        }
        if (time.isEmpty()) time = ro?.optString("time")?.takeIf { HHMM.matches(it) } ?: hhmmOf(anchor)
        val until = (ro?.opt("until") ?: p.opt("until"))?.let { if (it is Number) it.toLong() else parseDateTime(it.toString()) } ?: 0L
        // count inside "repeat" wins; else the top-level one (optInt gives 0, never null, so check for a real value)
        val count = ro?.optInt("count", 0)?.takeIf { it > 0 } ?: p.optInt("count", 0)
        val rule = when {
            repeat == null -> Rule("once", anchor)
            repeat == "daily" -> Rule("daily", anchor, t = time, u = until, n = count)
            repeat == "weekly" -> Rule("days", anchor, t = time, d = listOf(dayOf(anchor)), u = until, n = count)
            repeat == "weekdays" -> Rule("days", anchor, t = time, d = listOf(2, 3, 4, 5, 6), u = until, n = count)
            ro?.has("days") == true -> Rule("days", anchor, t = time, d = parseDays(ro.getJSONArray("days")), u = until, n = count)
            interval != null -> Rule("every", anchor, i = interval, u = until, n = count)
            else -> throw MiniAppError("TypeError", "Unknown repeat: $repeat")
        }
        if (rule.k == "every" && rule.i < MIN_INTERVAL) throw MiniAppError("NotAllowedError", "Repeating more often than every 15 minutes is not allowed")
        if (rule.k == "days" && rule.d.isEmpty()) throw MiniAppError("TypeError", "\"days\" is empty")
        return rule
    }

    // ------------------------------------------------------------------ alarms

    private fun alarmIntent(context: Context, action: String, app: String, id: String) = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, MiniAppAlarmReceiver::class.java).setAction(action)
            .setData(Uri.parse("antigem-alarm://${if (action == ACTION_TASK) "t" else "r"}/${Uri.encode(app)}/${Uri.encode(id)}"))
            .putExtra("app", app).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(context: Context, action: String, app: String, id: String, at: Long, exact: Boolean) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, action, app, id)
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // no "Alarms & reminders" access: Android will fire it within a window instead (App info offers the fix)
            Log.w(TAG, "Exact alarm not allowed, using an inexact one: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** False when Android only allows inexact alarms (Android 12+ without "Alarms & reminders" access). */
    fun canExact(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun disarm(context: Context, action: String, app: String, id: String) =
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, action, app, id))

    // ------------------------------------------------------------------ reminders

    @Synchronized
    fun scheduleReminder(context: Context, app: MiniApp, p: JSONObject): JSONObject {
        val id = (p.optString("id").ifBlank { p.optString("tag") }).ifBlank { UUID.randomUUID().toString() }.take(100)
        val title = p.optString("title").take(200)
        val body = p.optString("body").take(1000)
        if (title.isBlank() && body.isBlank()) throw MiniAppError("TypeError", "A reminder needs a title or a body")
        val now = System.currentTimeMillis()
        val rule = parseRule(p, now)
        val next = rule.next(now - 1) ?: throw MiniAppError("TypeError", "That time is in the past")
        val d = db(context)
        val count = d.rawQuery("SELECT COUNT(*) FROM reminders WHERE app = ? AND id != ?", arrayOf(app.id, id)).use { it.moveToFirst(); it.getInt(0) }
        if (count >= MAX_REMINDERS) throw MiniAppError("QuotaExceededError", "At most $MAX_REMINDERS reminders per app")
        val exact = p.optBoolean("exact", true)
        d.insertWithOnConflict("reminders", null, ContentValues().apply {
            put("app", app.id); put("id", id); put("title", title); put("body", body); put("rule", rule.toJson().toString()); put("next_at", next); put("exact", if (exact) 1 else 0)
            // everything notify.show accepts, minus the scheduling fields
            put("opts", JSONObject(p.toString()).apply { listOf("at", "delay", "repeat", "until", "count", "exact", "tag").forEach { remove(it) } }.put("id", id).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        arm(context, ACTION_REMINDER, app.id, id, next, exact)
        return JSONObject().put("id", id).put("at", next).put("repeat", rule.describe())
    }

    @Synchronized
    fun listReminders(context: Context, appId: String): JSONArray = JSONArray().apply {
        db(context).rawQuery("SELECT id, title, body, rule, next_at FROM reminders WHERE app = ? ORDER BY next_at", arrayOf(appId)).use { c ->
            while (c.moveToNext()) put(JSONObject().put("id", c.getString(0)).put("title", c.getString(1)).put("body", c.getString(2))
                .put("repeat", Rule.from(JSONObject(c.getString(3))).describe()).put("at", c.getLong(4)))
        }
    }

    @Synchronized
    fun cancelReminder(context: Context, appId: String, id: String?) {
        val ids = if (id != null) listOf(id) else db(context).rawQuery("SELECT id FROM reminders WHERE app = ?", arrayOf(appId)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        ids.forEach { disarm(context, ACTION_REMINDER, appId, it) }
        if (id != null) db(context).delete("reminders", "app = ? AND id = ?", arrayOf(appId, id)) else db(context).delete("reminders", "app = ?", arrayOf(appId))
        MiniAppNotify.cancel(context, appId, id)
    }

    @Synchronized
    private fun fireReminder(context: Context, appId: String, id: String, show: Boolean = true) {
        val d = db(context)
        val row = d.rawQuery("SELECT title, body, rule, exact, opts FROM reminders WHERE app = ? AND id = ?", arrayOf(appId, id)).use { c ->
            if (c.moveToFirst()) arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getInt(3).toString(), c.getString(4)) else null
        } ?: return
        val app = MiniApps.find(context, appId)
        if (app == null) { cancelReminder(context, appId, null); return }
        // the user may have taken the permission back: stay scheduled, just don't show
        if (show && MiniAppPermissions.isGranted(context, app, "notifications")) runCatching {
            val opts = row[4]?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (opts != null) MiniAppNotify.show(context, app, opts) else MiniAppNotify.show(context, app, row[0], row[1], id)
        }.onFailure { Log.w(TAG, "Reminder $id of $appId: ${it.message}") }
        val rule = Rule.from(JSONObject(row[2]))
        if (rule.n > 0 && --rule.n == 0) { d.delete("reminders", "app = ? AND id = ?", arrayOf(appId, id)); return }
        val next = rule.next(System.currentTimeMillis())
        if (next == null) { d.delete("reminders", "app = ? AND id = ?", arrayOf(appId, id)); return }
        d.execSQL("UPDATE reminders SET next_at = ?, rule = ? WHERE app = ? AND id = ?", arrayOf<Any>(next, rule.toJson().toString(), appId, id))
        arm(context, ACTION_REMINDER, appId, id, next, row[3] == "1")
    }

    // ------------------------------------------------------------------ background tasks

    private fun uniqueName(appId: String, name: String) = "miniapp:$appId:$name"

    /**
     * opts: {every: "1h"} (periodic, WorkManager) | {delay: "10m"} (once) | {at: "07:30", repeat: "daily"} (exact, alarm),
     * plus network: "any" | "unmetered", charging, batteryNotLow.
     */
    @Synchronized
    fun registerTask(context: Context, app: MiniApp, name: String, opts: JSONObject): JSONObject {
        if (!Regex("^[A-Za-z0-9._-]{1,64}$").matches(name)) throw MiniAppError("TypeError", "Task names are letters, digits, '.', '_' or '-'")
        if (app.backgroundScript == null) throw MiniAppError("InvalidStateError", "The manifest has no \"background\" script")
        val d = db(context)
        val count = d.rawQuery("SELECT COUNT(*) FROM tasks WHERE app = ? AND name != ?", arrayOf(app.id, name)).use { it.moveToFirst(); it.getInt(0) }
        if (count >= MAX_TASKS) throw MiniAppError("QuotaExceededError", "At most $MAX_TASKS background tasks per app")
        val now = System.currentTimeMillis()
        val wm = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(when (opts.optString("network")) { "any", "connected" -> NetworkType.CONNECTED; "unmetered", "wifi" -> NetworkType.UNMETERED; else -> NetworkType.NOT_REQUIRED })
            .setRequiresCharging(opts.optBoolean("charging"))
            .setRequiresBatteryNotLow(opts.optBoolean("batteryNotLow"))
            .build()
        val input = workDataOf("app" to app.id, "task" to name, "reason" to "schedule")
        fun kindOf(o: JSONObject) = when { o.has("every") -> "every"; o.has("at") -> "at"; else -> "delay" }
        val old = d.rawQuery("SELECT spec, next_at FROM tasks WHERE app = ? AND name = ?", arrayOf(app.id, name)).use { c ->
            if (c.moveToFirst()) JSONObject(c.getString(0)) to c.getLong(1) else null
        }
        // Apps register their tasks every time they open: the same periodic task keeps its timing (UPDATE).
        // Only a change of kind clears the old schedule first.
        if (old != null && kindOf(old.first) != kindOf(opts)) cancelTaskWork(context, app.id, name)
        val kind: String
        val next: Long
        when {
            opts.has("every") -> {
                val every = parseDuration(opts.opt("every"))
                if (every < MIN_INTERVAL) throw MiniAppError("NotAllowedError", "Background tasks can repeat at most every 15 minutes")
                wm.enqueueUniquePeriodicWork(uniqueName(app.id, name), ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<MiniAppBackgroundWorker>(every, TimeUnit.MILLISECONDS)
                        // first run after one period, not at once: started right away it would run inside the
                        // open app, and lose the network if the app is closed mid-run (OEMs cut cached processes)
                        .setInitialDelay(every, TimeUnit.MILLISECONDS)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                        .setConstraints(constraints).setInputData(input).addTag("miniapp:${app.id}").build())
                kind = "every ${duration(every)}"
                next = old?.takeIf { kindOf(it.first) == "every" && it.second > now }?.second ?: (now + every)
            }
            opts.has("at") -> {
                val rule = parseRule(opts, now)
                next = rule.next(now - 1) ?: throw MiniAppError("TypeError", "That time is in the past")
                if (rule.k == "every" && rule.i < MIN_INTERVAL) throw MiniAppError("NotAllowedError", "Background tasks can repeat at most every 15 minutes")
                arm(context, ACTION_TASK, app.id, name, next, true)
                opts.put("rule", rule.toJson())
                kind = rule.describe()
            }
            opts.has("delay") -> {
                val delay = parseDuration(opts.opt("delay"))
                wm.enqueueUniqueWork(uniqueName(app.id, name), ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<MiniAppBackgroundWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                        .setConstraints(constraints).setInputData(input).addTag("miniapp:${app.id}").build())
                kind = "once in ${duration(delay)}"; next = now + delay
            }
            else -> throw MiniAppError("TypeError", "Give \"every\", \"delay\" or \"at\"")
        }
        d.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("app", app.id); put("name", name); put("kind", kind); put("spec", opts.toString()); put("next_at", next)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return JSONObject().put("name", name).put("schedule", kind).put("next", next)
    }

    private fun cancelTaskWork(context: Context, appId: String, name: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(appId, name))
        disarm(context, ACTION_TASK, appId, name)
    }

    @Synchronized
    fun cancelTask(context: Context, appId: String, name: String?) {
        val names = if (name != null) listOf(name) else db(context).rawQuery("SELECT name FROM tasks WHERE app = ?", arrayOf(appId)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        names.forEach { cancelTaskWork(context, appId, it) }
        if (name != null) db(context).delete("tasks", "app = ? AND name = ?", arrayOf(appId, name)) else db(context).delete("tasks", "app = ?", arrayOf(appId))
    }

    /** Runs a task now (used by exact alarms, "Run now" in App info and AntiGem.background.run). */
    fun runNow(context: Context, appId: String, name: String, reason: String, data: String? = null) {
        // one after another (a second tap must not cancel the first run)
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(appId, name) + ":now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<MiniAppBackgroundWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf("app" to appId, "task" to name, "reason" to reason, "data" to data))
                .addTag("miniapp:$appId").build())
    }

    @Synchronized
    fun listTasks(context: Context, appId: String): JSONArray {
        val d = db(context)
        val out = JSONArray()
        d.rawQuery("SELECT name, kind, next_at FROM tasks WHERE app = ? ORDER BY name", arrayOf(appId)).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                val o = JSONObject().put("name", name).put("schedule", c.getString(1)).put("next", c.getLong(2))
                d.rawQuery("SELECT started, ms, status, detail, reason FROM runs WHERE app = ? AND task = ? ORDER BY started DESC LIMIT 1", arrayOf(appId, name)).use { r ->
                    if (r.moveToFirst()) o.put("lastRun", JSONObject().put("at", r.getLong(0)).put("ms", r.getLong(1)).put("status", r.getString(2)).put("detail", r.getString(3)).put("reason", r.getString(4)))
                }
                out.put(o)
            }
        }
        return out
    }

    @Synchronized
    fun runsLastDay(context: Context, appId: String): Int =
        db(context).rawQuery("SELECT COUNT(*) FROM runs WHERE app = ? AND started > ? AND status != 'skipped'", arrayOf(appId, (System.currentTimeMillis() - 86_400_000L).toString())).use { it.moveToFirst(); it.getInt(0) }

    /** A one-off "delay" task ran (or was skipped) at its time: it is done, so it leaves the list and the limit. */
    @Synchronized
    fun finishedOnce(context: Context, appId: String, name: String) {
        db(context).delete("tasks", "app = ? AND name = ? AND kind LIKE 'once in %'", arrayOf(appId, name))
    }

    @Synchronized
    fun recordRun(context: Context, appId: String, task: String, started: Long, ms: Long, status: String, detail: String, reason: String) {
        val d = db(context)
        d.insert("runs", null, ContentValues().apply {
            put("app", appId); put("task", task); put("started", started); put("ms", ms); put("status", status); put("detail", detail.take(500)); put("reason", reason)
        })
        d.execSQL("DELETE FROM runs WHERE app = ? AND rowid NOT IN (SELECT rowid FROM runs WHERE app = ? ORDER BY started DESC LIMIT 200)", arrayOf(appId, appId))
        // keep "next" roughly right for periodic tasks (WorkManager doesn't tell us)
        d.rawQuery("SELECT kind FROM tasks WHERE app = ? AND name = ?", arrayOf(appId, task)).use { c ->
            if (c.moveToFirst() && c.getString(0).startsWith("every ")) {
                val every = runCatching { parseDuration(c.getString(0).removePrefix("every ")) }.getOrDefault(0L)
                if (every > 0) d.execSQL("UPDATE tasks SET next_at = ? WHERE app = ? AND name = ?", arrayOf<Any>(started + every, appId, task))
            }
        }
    }

    @Synchronized
    private fun fireTask(context: Context, appId: String, name: String, run: Boolean = true) {
        val d = db(context)
        val spec = d.rawQuery("SELECT spec FROM tasks WHERE app = ? AND name = ?", arrayOf(appId, name)).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null } ?: return
        if (run) runNow(context, appId, name, "alarm")
        val rule = spec.optJSONObject("rule")?.let { Rule.from(it) } ?: return
        if (rule.n > 0 && --rule.n == 0) { d.delete("tasks", "app = ? AND name = ?", arrayOf(appId, name)); return }
        val next = rule.next(System.currentTimeMillis())
        if (next == null) { d.delete("tasks", "app = ? AND name = ?", arrayOf(appId, name)); return }
        spec.put("rule", rule.toJson())
        d.execSQL("UPDATE tasks SET next_at = ?, spec = ? WHERE app = ? AND name = ?", arrayOf<Any>(next, spec.toString(), appId, name))
        arm(context, ACTION_TASK, appId, name, next, true)
    }

    // ------------------------------------------------------------------ lifecycle

    /** Clear data / app removed: everything it scheduled goes. */
    fun forgetApp(context: Context, appId: String) {
        cancelReminder(context, appId, null)
        cancelTask(context, appId, null)
        WorkManager.getInstance(context).cancelAllWorkByTag("miniapp:$appId")
        synchronized(this) { db(context).delete("runs", "app = ?", arrayOf(appId)) }
    }

    /**
     * After boot / update / time change / AntiGem start: alarms again for everything. Missed reminders (phone off)
     * still show if at most 6 h late; times of day are worked out again (the time zone may have changed).
     */
    @Synchronized
    fun rearmAll(context: Context) {
        val now = System.currentTimeMillis()
        val d = db(context)
        data class R(val app: String, val id: String, val rule: Rule, val next: Long, val exact: Boolean)
        val reminders = d.rawQuery("SELECT app, id, rule, next_at, exact FROM reminders", null).use { c ->
            buildList { while (c.moveToNext()) add(R(c.getString(0), c.getString(1), Rule.from(JSONObject(c.getString(2))), c.getLong(3), c.getInt(4) == 1)) }
        }
        for (r in reminders) {
            if (r.next <= now) { fireReminder(context, r.app, r.id, show = now - r.next <= MISSED_GRACE); continue }
            val next = if (r.rule.timeOfDay) r.rule.next(now) ?: r.next else r.next
            if (next != r.next) d.execSQL("UPDATE reminders SET next_at = ? WHERE app = ? AND id = ?", arrayOf<Any>(next, r.app, r.id))
            arm(context, ACTION_REMINDER, r.app, r.id, next, r.exact)
        }
        val tasks = d.rawQuery("SELECT app, name, spec, next_at FROM tasks", null).use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3).toString())) }
        }
        for ((app, name, spec, nextS) in tasks) {
            val rule = JSONObject(spec).optJSONObject("rule")?.let { Rule.from(it) } ?: continue // WorkManager keeps its own
            val stored = nextS.toLong()
            if (stored <= now) { fireTask(context, app, name, run = now - stored <= 3600_000L); continue }
            arm(context, ACTION_TASK, app, name, if (rule.timeOfDay) rule.next(now) ?: stored else stored, true)
        }
        Log.i(TAG, "Re-armed ${reminders.size} reminders, ${tasks.size} tasks")
    }

    fun rearmAsync(context: Context) { val c = context.applicationContext; io.launch { runCatching { rearmAll(c) }.onFailure { Log.w(TAG, "rearm failed", it) } } }

    internal fun onAlarm(context: Context, intent: Intent) {
        val app = intent.getStringExtra("app") ?: return
        val id = intent.getStringExtra("id") ?: return
        when (intent.action) {
            ACTION_REMINDER -> fireReminder(context, app, id)
            ACTION_TASK -> fireTask(context, app, id)
        }
    }
}

/** An exact alarm of a reminder or task went off. */
class MiniAppAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { MiniAppScheduler.onAlarm(context.applicationContext, intent) } catch (e: Exception) { Log.w("MiniAppScheduler", "alarm failed", e) } finally { pending.finish() }
        }.start()
    }
}

/** Reboot, AntiGem update, clock or time-zone change: Android dropped (or shifted) the alarms, set them again. */
class MiniAppBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { MiniAppScheduler.rearmAll(context.applicationContext) } catch (e: Exception) { Log.w("MiniAppScheduler", "rearm failed", e) } finally { pending.finish() }
        }.start()
    }
}
