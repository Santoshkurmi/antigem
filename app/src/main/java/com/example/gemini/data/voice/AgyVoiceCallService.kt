package com.example.gemini.data.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.gemini.MainActivity
import com.example.gemini.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps an Antigravity voice call going while the screen is off or another app is in front (Android only lets a
 * foreground service of the microphone type use the mic there), with a call notification: Mute / Unmute and End.
 */
class AgyVoiceCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always first: a service started with startForegroundService() must call startForeground(), even when the
        // call already ended or failed meanwhile (Android ends the app otherwise). It stops itself below once the
        // call is over; nothing else may stop it before this ran.
        ensureChannel()
        val started = runCatching {
            val n = notification(AgyVoiceCall.state.value)
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        }
        if (started.isFailure) android.util.Log.w("AgyVoiceCall", "call service could not go foreground: ${started.exceptionOrNull()}")
        when (intent?.action) {
            ACTION_MUTE -> AgyVoiceCall.toggleMute()
            ACTION_END -> AgyVoiceCall.end()
        }
        lastStartId = startId
        if (started.isFailure) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (wakeLock == null) runCatching {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antigem:agy-voice-call")
                .apply { setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L) }
        }
        job?.cancel()
        job = scope.launch {
            AgyVoiceCall.state
                .map { Triple(it.phase, it.muted, it.agentSpeaking) to it }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, st) ->
                    if (!st.active || st.phase == AgyVoiceCall.Phase.FAILED) {
                        // only when no newer start came in (a new call right after this one keeps the service)
                        stopSelf(lastStartId)
                        return@collect
                    }
                    runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(st)) }
                }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Antigravity voice calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you are on a voice call with Antigravity"
                setShowBadge(false)
            })
        }
    }

    private fun notification(st: AgyVoiceCall.State): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        fun action(a: String, code: Int) =
            PendingIntent.getService(this, code, Intent(this, AgyVoiceCallService::class.java).setAction(a), flags)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val text = when (st.phase) {
            AgyVoiceCall.Phase.CONNECTING -> "Connecting…"
            AgyVoiceCall.Phase.RECONNECTING -> "Reconnecting…"
            AgyVoiceCall.Phase.ENDING -> "Ending…"
            else -> when {
                st.muted -> "Muted"
                st.agentSpeaking -> "${st.voice} is speaking"
                else -> "Listening"
            }
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis_mic)
            .setContentTitle(st.title.ifBlank { "Antigravity" } + " · voice call")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
        if (st.connectedAt > 0) {
            b.setUsesChronometer(true)
            b.setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - st.connectedAt))
        } else b.setShowWhen(false)
        b.addAction(0, if (st.muted) "Unmute" else "Mute", action(ACTION_MUTE, 1))
        b.addAction(0, "End", action(ACTION_END, 2))
        return b.build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "agy_voice_call"
        private const val NOTIF_ID = 7731
        const val ACTION_MUTE = "com.example.gemini.agyvoice.MUTE"
        const val ACTION_END = "com.example.gemini.agyvoice.END"

        /**
         * Starts the call service (needs the microphone permission: a microphone foreground service cannot start
         * without it). There is no stop(): the service stops itself when the call is over, so it is never stopped
         * before it went foreground.
         */
        fun start(context: Context) {
            val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
            ContextCompat.startForegroundService(context, Intent(context, AgyVoiceCallService::class.java))
        }
    }
}
