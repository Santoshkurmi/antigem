package com.example.gemini.data.jarvis

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.gemini.MainActivity
import com.example.gemini.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the microphone available while hands-free mode is on (the screen can be off and another app in front).
 * The notification shows what Jarvis is doing and has Talk / Stop buttons.
 */
class JarvisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> {
                if (JarvisController.isAttached) JarvisController.onMicPressed()
                return START_STICKY
            }
            ACTION_STOP -> {
                if (JarvisController.isAttached) JarvisController.setHandsFree(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureChannel()
        val n = notification("Hands-free is on")
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
        acquireWakeLock()
        job?.cancel()
        job = scope.launch {
            combine(JarvisController.phase, JarvisController.heard) { p, h -> p to h }.collect { (p, h) ->
                val text = when (p) {
                    JarvisController.Phase.LISTENING -> if (h.isNotBlank()) "Listening: $h" else "Listening…"
                    JarvisController.Phase.THINKING -> "Working on it…"
                    JarvisController.Phase.SPEAKING -> "Speaking…"
                    JarvisController.Phase.OFF -> "Jarvis is off"
                    JarvisController.Phase.IDLE ->
                        if (JarvisPreferences.hotword.value == "phrase") "Say “${JarvisPreferences.wakePhrase.value}”" else "Tap Talk to speak"
                }
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text)) }
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antigem:jarvis").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis hands-free", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Jarvis listens for your voice"
                setShowBadge(false)
            })
        }
    }

    private fun notification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        fun action(a: String, code: Int) =
            PendingIntent.getService(this, code, Intent(this, JarvisService::class.java).setAction(a), flags)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Talk", action(ACTION_TALK, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jarvis_hands_free"
        private const val NOTIF_ID = 7712
        const val ACTION_TALK = "com.example.gemini.jarvis.TALK"
        const val ACTION_STOP = "com.example.gemini.jarvis.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }
}
