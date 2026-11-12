package com.example.gemini.data.miniapps

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.example.gemini.R
import com.example.gemini.ui.miniapps.MiniAppLaunchActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Mini app notifications, close to what a native app gets from Android:
 *  - channels per app (`notify.channel`), grouped under the app's name in Android's notification settings, so the
 *    user controls each app (and each of its channels) like an installed app
 *  - accent color, big text / picture / inbox / messaging styles, large and small icons, progress, a countdown or
 *    stopwatch the system ticks by itself, ongoing, silent, priority, category, lock-screen visibility, groups,
 *    badge number, timeout
 *  - up to 3 buttons, including an inline reply box; taps, buttons, replies and swipe-dismiss come back to the app
 *    as `notification` events (or to its background script, or when it is opened next)
 * The same id updates a notification in place.
 */
object MiniAppNotify {
    private const val TAG = "MiniAppNotify"
    /**
     * Default channels: alerts pop up with sound; ongoing ones (timers, downloads) sit in the main section of the shade
     * without sound or vibration (like Clock's stopwatch); silent ones go to the Silent section.
     */
    private const val CHANNEL_ALERTS = "mini_apps"
    private const val CHANNEL_ONGOING = "mini_apps_ongoing"
    private const val CHANNEL_STATUS = "mini_apps_status"
    /** AntiGem's whole package may show ~50 notifications: no single mini app may take them all. */
    const val MAX_ACTIVE_PER_APP = 12
    const val EXTRA_APP = "mini_app_id"
    const val EXTRA_NOTIF = "mini_app_notif"
    const val EXTRA_ACTION = "mini_app_action"
    const val REPLY_KEY = "mini_app_reply"
    private const val ACTION_BUTTON = "com.example.gemini.MINI_APP_NOTIFICATION"

    /** Last options per shown notification, so a reply can re-post it (Android shows a spinner until then). */
    private val lastShown = ConcurrentHashMap<String, JSONObject>()

    fun id(appId: String, tag: String) = "$appId\u0000$tag".hashCode()
    private fun tagOf(appId: String, tag: String) = "miniapp:$appId:$tag"

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun ensureDefaultChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = nm(context)
        if (nm.getNotificationChannel(CHANNEL_ALERTS) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Mini apps", NotificationManager.IMPORTANCE_HIGH))
        if (nm.getNotificationChannel(CHANNEL_STATUS) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Mini apps (silent)", NotificationManager.IMPORTANCE_LOW))
        if (nm.getNotificationChannel(CHANNEL_ONGOING) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Mini apps (running)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Timers, downloads and other things in progress"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
    }

    private fun channelId(appId: String, id: String) = "miniapp:$appId:$id"

    // ------------------------------------------------------------------ channels

    /** {id, name, description?, importance: min|low|default|high, sound?: bool, vibrate?: bool | [ms…], lights?: color, badge?: bool} */
    fun createChannel(context: Context, app: MiniApp, p: JSONObject): JSONObject {
        val id = p.optString("id").trim()
        if (!Regex("^[A-Za-z0-9._-]{1,40}$").matches(id)) throw MiniAppError("TypeError", "Channel ids are letters, digits, '.', '_' or '-'")
        if (Build.VERSION.SDK_INT < 26) return JSONObject().put("id", id)
        val nm = nm(context)
        val groupId = "miniapp:${app.id}"
        if (nm.getNotificationChannelGroup(groupId) == null) nm.createNotificationChannelGroup(NotificationChannelGroup(groupId, "Mini app: ${app.name}"))
        val existing = nm.notificationChannels.count { it.id.startsWith("miniapp:${app.id}:") && it.id != channelId(app.id, id) }
        if (existing >= 10) throw MiniAppError("QuotaExceededError", "At most 10 notification channels per app")
        val importance = when (p.optString("importance", "default")) {
            "min" -> NotificationManager.IMPORTANCE_MIN; "low" -> NotificationManager.IMPORTANCE_LOW
            "high", "max" -> NotificationManager.IMPORTANCE_HIGH; else -> NotificationManager.IMPORTANCE_DEFAULT
        }
        // Android keeps the user's choices: importance, sound and vibration of an existing channel can't be changed
        // by the app afterwards (only name and description)
        val ch = NotificationChannel(channelId(app.id, id), p.optString("name").ifBlank { id }.take(60), importance).apply {
            description = p.optString("description").take(200).ifBlank { null }
            group = groupId
            setShowBadge(p.optBoolean("badge", true))
            if (p.has("sound") && !p.optBoolean("sound", true)) setSound(null, null)
            else setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            when (val v = p.opt("vibrate")) {
                is JSONArray -> { enableVibration(true); vibrationPattern = LongArray(v.length()) { v.optLong(it) } }
                false -> enableVibration(false)
                true -> enableVibration(true)
            }
            p.optString("lights").takeIf { it.isNotBlank() }?.let { c -> MiniApps.parseColor(c)?.let { enableLights(true); lightColor = it } }
        }
        nm.createNotificationChannel(ch)
        return JSONObject().put("id", id).put("importance", p.optString("importance", "default"))
    }

    fun deleteChannel(context: Context, app: MiniApp, id: String) {
        if (Build.VERSION.SDK_INT >= 26) nm(context).deleteNotificationChannel(channelId(app.id, id))
    }

    fun forgetApp(context: Context, appId: String) {
        val nm = nm(context)
        nm.activeNotifications.filter { it.tag?.startsWith("miniapp:$appId:") == true }.forEach { nm.cancel(it.tag, it.id) }
        if (Build.VERSION.SDK_INT >= 26) {
            nm.notificationChannels.filter { it.id.startsWith("miniapp:$appId:") }.forEach { nm.deleteNotificationChannel(it.id) }
            runCatching { nm.deleteNotificationChannelGroup("miniapp:$appId") }
        }
        lastShown.keys.removeIf { it.startsWith("$appId\u0000") }
    }

    // ------------------------------------------------------------------ showing

    /** Simple form (reminders without options). */
    fun show(context: Context, app: MiniApp, title: String, body: String, tag: String) =
        show(context, app, JSONObject().put("id", tag).put("title", title).put("body", body))

    private fun launchIntent(context: Context, app: MiniApp, tag: String, action: String) =
        Intent(context, MiniAppLaunchActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("antigem-notif://${Uri.encode(app.id)}/${Uri.encode(tag)}/${Uri.encode(action)}"))
            .putExtra(EXTRA_APP, app.id).putExtra(EXTRA_NOTIF, tag).putExtra(EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun broadcastIntent(context: Context, app: MiniApp, tag: String, action: String) =
        Intent(context, MiniAppNotificationReceiver::class.java)
            .setAction(ACTION_BUTTON)
            .setData(Uri.parse("antigem-notif://${Uri.encode(app.id)}/${Uri.encode(tag)}/${Uri.encode(action)}"))
            .putExtra(EXTRA_APP, app.id).putExtra(EXTRA_NOTIF, tag).putExtra(EXTRA_ACTION, action)

    private fun flags(mutable: Boolean = false) = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else if (mutable) 0 else PendingIntent.FLAG_IMMUTABLE)

    /** A picture for a large icon / big picture: a file of the app, a "data/…" or "app:<id>/…" file, or an https URL it may reach. */
    private fun bitmap(context: Context, app: MiniApp, src: String, maxPx: Int): Bitmap? = runCatching {
        val bytes: ByteArray = when {
            src.startsWith("https://") || src.startsWith("http://") -> {
                // as the app: its hosts, cookies, and never this phone's servers without the localhost permission
                val url = src.toHttpUrlOrNull() ?: return null
                MiniAppPermissions.checkNetwork(context, app, url.host, url.port)?.let { Log.w(TAG, "Image blocked: $it"); return null }
                MiniAppNet.fetchBytes(context, app, src) ?: return null
            }
            src.startsWith("data/") || src.startsWith("app:") || src.startsWith("~/") || src.startsWith("/") ->
                MiniAppPermissions.resolve(context, app, src, MiniAppPermissions.Need.READ).readBytes()
            else -> {
                val root = app.folder.canonicalFile
                val f = File(root, src.trimStart('/')).canonicalFile
                if (!f.path.startsWith(root.path + File.separator)) return null
                // an installed app's file only if it is the one that was signed
                if (app.signed && !MiniAppInstaller.isIntact(context, app, f.path.removePrefix(root.path + File.separator), f)) return null
                f.readBytes()
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxPx && o.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.onFailure { Log.w(TAG, "Image $src: ${it.message}") }.getOrNull()

    private fun time(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    /**
     * Shows (or updates, same id) a notification. Options: see the README ("Notifications"). Blocking: may load
     * images, call off the main thread.
     */
    fun show(context: Context, app: MiniApp, p: JSONObject): JSONObject {
        ensureDefaultChannels(context)
        val tag = p.optString("id").ifBlank { p.optString("tag") }.ifBlank { UUID.randomUUID().toString() }.take(100)
        val nm = nm(context)
        val mine = nm.activeNotifications.filter { it.tag?.startsWith("miniapp:${app.id}:") == true }
        if (mine.size >= MAX_ACTIVE_PER_APP && mine.none { it.tag == tagOf(app.id, tag) })
            throw MiniAppError("QuotaExceededError", "At most $MAX_ACTIVE_PER_APP notifications of one app at a time: cancel some first")

        val ongoing = p.optBoolean("ongoing")
        val silent = p.optBoolean("silent")
        val channel = p.optString("channel").takeIf { it.isNotBlank() }?.let { c ->
            channelId(app.id, c).also { if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(it) == null) throw MiniAppError("NotFoundError", "No channel \"$c\": create it with AntiGem.notify.channel()") }
        } ?: when { silent -> CHANNEL_STATUS; ongoing -> CHANNEL_ONGOING; else -> CHANNEL_ALERTS }
        val title = p.optString("title").take(200)
        val body = p.optString("body").take(4000)
        val color = p.optString("color").takeIf { it.isNotBlank() }?.let { MiniApps.parseColor(it) } ?: app.themeColor
        val req = id(app.id, tag)

        val b = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(p.optString("subtext").ifBlank { app.name })
            .setOngoing(ongoing)
            .setAutoCancel(p.optBoolean("autoCancel", !ongoing))
            .setOnlyAlertOnce(p.optBoolean("onlyAlertOnce", ongoing))
            .setSilent(silent)
            .setShowWhen(true)
            .setPriority(when (p.optString("priority")) {
                "min" -> NotificationCompat.PRIORITY_MIN; "low" -> NotificationCompat.PRIORITY_LOW; "high" -> NotificationCompat.PRIORITY_HIGH
                "max" -> NotificationCompat.PRIORITY_MAX; "default" -> NotificationCompat.PRIORITY_DEFAULT
                else -> if (ongoing || silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH
            })
            .setContentIntent(PendingIntent.getActivity(context, req, launchIntent(context, app, tag, p.optString("tapAction").ifBlank { "tap" }), flags()))
            .setDeleteIntent(PendingIntent.getBroadcast(context, req, broadcastIntent(context, app, tag, "dismiss"), flags()))

        // small icon: the app's own monochrome glyph if it has one, else AntiGem's
        val small = p.optString("smallIcon").takeIf { it.isNotBlank() }?.let { bitmap(context, app, it, 96) }
        if (small != null) b.setSmallIcon(IconCompat.createWithBitmap(small)) else b.setSmallIcon(R.mipmap.ic_launcher)
        val large = p.optString("icon").let { if (it.isBlank()) MiniApps.iconBitmap(app, 128) else bitmap(context, app, it, 256) }
        if (large != null) b.setLargeIcon(large)
        if (color != null) { b.color = color; b.setColorized(p.optBoolean("colorized")) }

        p.optString("category").takeIf { it.isNotBlank() }?.let { b.setCategory(it) }
        b.setVisibility(when (p.optString("visibility")) { "public" -> NotificationCompat.VISIBILITY_PUBLIC; "secret" -> NotificationCompat.VISIBILITY_SECRET; else -> NotificationCompat.VISIBILITY_PRIVATE })
        // Always a group: without one Android bundles every AntiGem notification together (mini apps mixed with each
        // other and with AntiGem's own). Default: the notification's own, so it stands alone.
        val group = p.optString("group").takeIf { it.isNotBlank() }
        b.setGroup("miniapp:${app.id}:" + (group ?: "n:$tag"))
        if (group != null) b.setGroupSummary(p.optBoolean("groupSummary"))
        if (p.has("number")) b.setNumber(p.optInt("number"))
        if (p.has("timeout")) b.setTimeoutAfter(p.optLong("timeout"))

        // A countdown / stopwatch / big value shown like Clock's stopwatch: the time is the content, big, ticked by the
        // system itself (no updates, no battery); Android still draws the header and the buttons around it.
        val countdown = time(p.opt("countdown"))
        val chrono = time(p.opt("chronometer"))
        val display = p.optString("display").takeIf { it.isNotBlank() }
        if (countdown != null || chrono != null || display != null) {
            val now = System.currentTimeMillis()
            val rv = android.widget.RemoteViews(context.packageName, R.layout.miniapp_notification_timer)
            rv.setTextViewText(R.id.miniapp_notif_title, title)
            if (body.isBlank()) rv.setViewVisibility(R.id.miniapp_notif_body, android.view.View.GONE) else rv.setTextViewText(R.id.miniapp_notif_body, body)
            when {
                countdown != null -> {
                    rv.setChronometer(R.id.miniapp_notif_timer, android.os.SystemClock.elapsedRealtime() + (countdown - now), null, true)
                    rv.setChronometerCountDown(R.id.miniapp_notif_timer, true)
                    if (!p.has("timeout") && countdown > now) b.setTimeoutAfter(countdown - now + 1500)
                }
                chrono != null -> rv.setChronometer(R.id.miniapp_notif_timer, android.os.SystemClock.elapsedRealtime() - (now - chrono), null, true)
                else -> {
                    rv.setViewVisibility(R.id.miniapp_notif_timer, android.view.View.GONE)
                    rv.setViewVisibility(R.id.miniapp_notif_value, android.view.View.VISIBLE)
                    rv.setTextViewText(R.id.miniapp_notif_value, display)
                }
            }
            b.setStyle(NotificationCompat.DecoratedCustomViewStyle()).setCustomContentView(rv).setCustomBigContentView(rv)
            b.setShowWhen(false)
        }
        p.optJSONObject("progress")?.let { pr -> b.setProgress(pr.optInt("max", 100), pr.optInt("value"), pr.optBoolean("indeterminate")) }

        // styles
        val style = p.optString("style")
        val image = p.optString("image").takeIf { it.isNotBlank() }?.let { bitmap(context, app, it, 1024) }
        val lines = p.optJSONArray("lines")
        val messages = p.optJSONArray("messages")
        if (countdown == null && chrono == null && display == null) when {
            image != null || style == "picture" -> if (image != null) b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(body))
            lines != null || style == "inbox" -> b.setStyle(NotificationCompat.InboxStyle().also { s ->
                if (lines != null) for (i in 0 until minOf(lines.length(), 7)) s.addLine(lines.optString(i))
                if (body.isNotBlank()) s.setSummaryText(body)
            })
            messages != null || style == "messages" -> {
                val me = Person.Builder().setName(p.optString("me").ifBlank { "You" }).build()
                b.setStyle(NotificationCompat.MessagingStyle(me).also { s ->
                    if (title.isNotBlank()) s.conversationTitle = title
                    if (messages != null) for (i in 0 until messages.length()) {
                        val m = messages.optJSONObject(i) ?: continue
                        val from = m.optString("from").takeIf { it.isNotBlank() }?.let { Person.Builder().setName(it).build() }
                        s.addMessage(m.optString("text"), time(m.opt("time")) ?: System.currentTimeMillis(), from)
                    }
                })
            }
            body.length > 40 || style == "text" -> b.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        // buttons: open the app, or not ("open": false goes to the app if it is on screen, else its background script);
        // "reply" adds a text box
        p.optJSONArray("actions")?.let { acts ->
            for (i in 0 until minOf(acts.length(), 3)) {
                val a = acts.optJSONObject(i) ?: continue
                val aid = a.optString("id").ifBlank { "action$i" }
                val reply = a.opt("reply")
                val label = a.optString("title").ifBlank { aid }
                val actionReq = "${app.id}\u0000$tag\u0000$aid".hashCode()
                val pi = if (reply != null && reply != false || !a.optBoolean("open", true))
                    PendingIntent.getBroadcast(context, actionReq, broadcastIntent(context, app, tag, aid), flags(mutable = reply != null && reply != false))
                else PendingIntent.getActivity(context, actionReq, launchIntent(context, app, tag, aid), flags())
                val builder = NotificationCompat.Action.Builder(0, label, pi)
                if (reply != null && reply != false) {
                    builder.addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel((reply as? JSONObject)?.optString("placeholder")?.ifBlank { null } ?: label).build())
                    builder.setAllowGeneratedReplies(true)
                }
                b.addAction(builder.build())
            }
        }

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) throw MiniAppError("NotAllowedError", "Notifications are turned off for AntiGem in Android settings")
        nm.notify(tagOf(app.id, tag), req, b.build())
        lastShown["${app.id}\u0000$tag"] = JSONObject(p.toString()).put("id", tag)
        return JSONObject().put("id", tag)
    }

    /** Removes a shown notification (all of the app's when [tag] is null). */
    fun cancel(context: Context, appId: String, tag: String?) {
        val nm = nm(context)
        if (tag != null) { nm.cancel(tagOf(appId, tag), id(appId, tag)); lastShown.remove("$appId\u0000$tag"); return }
        nm.activeNotifications.filter { it.tag?.startsWith("miniapp:$appId:") == true }.forEach { nm.cancel(it.tag, it.id) }
        lastShown.keys.removeIf { it.startsWith("$appId\u0000") }
    }

    /** The app's notifications on screen now: [{id, title, body, ongoing, posted}]. */
    fun active(context: Context, appId: String): JSONArray = JSONArray().apply {
        nm(context).activeNotifications.filter { it.tag?.startsWith("miniapp:$appId:") == true }.forEach { s ->
            val extras = s.notification.extras
            put(JSONObject().put("id", s.tag.removePrefix("miniapp:$appId:"))
                .put("title", extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty())
                .put("body", extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty())
                .put("ongoing", s.isOngoing).put("posted", s.postTime))
        }
    }

    /** After an inline reply Android shows a spinner until the notification is posted again. */
    internal fun refreshAfterReply(context: Context, app: MiniApp, tag: String, reply: String) {
        val last = lastShown["${app.id}\u0000$tag"] ?: run { cancel(context, app.id, tag); return }
        // a messaging notification shows the reply in the thread; others just stop spinning
        last.optJSONArray("messages")?.put(JSONObject().put("text", reply).put("time", System.currentTimeMillis()))
        runCatching { show(context, app, last.put("silent", true)) }
    }
}

/** A button (without "open"), a reply or a swipe-away on a mini app notification. */
class MiniAppNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appId = intent.getStringExtra(MiniAppNotify.EXTRA_APP) ?: return
        val tag = intent.getStringExtra(MiniAppNotify.EXTRA_NOTIF) ?: return
        val action = intent.getStringExtra(MiniAppNotify.EXTRA_ACTION) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MiniAppNotify.REPLY_KEY)?.toString()
        val event = JSONObject().put("id", tag).put("action", action).apply { if (reply != null) put("reply", reply) }
        val pending = goAsync()
        Thread {
            try {
                val app = MiniApps.find(context, appId) ?: return@Thread
                if (reply != null) MiniAppNotify.refreshAfterReply(context, app, tag, reply)
                MiniAppRuntime.deliverNotificationEvent(context, app, event, preferBackground = true)
            } catch (e: Exception) {
                Log.w("MiniAppNotify", "action failed", e)
            } finally { pending.finish() }
        }.start()
    }
}
