package com.example.gemini.data.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.gemini.MainActivity
import com.example.gemini.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps Jarvis reachable while hands-free mode is on (the screen can be off and another app in front): the microphone
 * for the wake phrase, the headset / Bluetooth button (one press talks or sends, two presses stop), and a notification
 * that shows what Jarvis is doing with Talk / Send / Stop buttons.
 */
class JarvisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var session: MediaSession? = null
    private var clicks = 0
    private var clickJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TALK -> {
                if (JarvisController.isAttached) JarvisController.onMicPressed()
                return START_STICKY
            }
            ACTION_SEND -> {
                if (JarvisController.isAttached) JarvisController.finishListening()
                return START_STICKY
            }
            ACTION_STOP -> {
                if (JarvisController.isAttached) JarvisController.setHandsFree(false)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureChannel()
        val n = notification("Hands-free is on", JarvisController.Phase.IDLE)
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
        acquireWakeLock()
        startMediaSession()
        job?.cancel()
        job = scope.launch {
            combine(JarvisController.phase, JarvisController.heard, JarvisTasks.tasks) { p, h, t -> Triple(p, h, t) }.collect { (p, h, tasks) ->
                val active = tasks.count { it.active }
                val needs = tasks.count { it.state.needsUser }
                val taskLine = when {
                    needs > 0 -> " · $needs task${if (needs == 1) "" else "s"} need${if (needs == 1) "s" else ""} you"
                    active > 0 -> " · $active task${if (active == 1) "" else "s"} running"
                    else -> ""
                }
                val text = when (p) {
                    JarvisController.Phase.LISTENING -> if (h.isNotBlank()) "Listening: $h" else "Listening…"
                    JarvisController.Phase.THINKING -> "Working on it…$taskLine"
                    JarvisController.Phase.SPEAKING -> "Speaking…"
                    JarvisController.Phase.OFF -> "Jarvis is off"
                    JarvisController.Phase.IDLE ->
                        (if (JarvisPreferences.hotword.value == "phrase") "Say “${JarvisPreferences.wakePhrase.value.substringBefore(',').trim()}”" else "Tap Talk to speak") + taskLine
                }
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text, p)) }
            }
        }
        return START_STICKY
    }

    /** Headset and Bluetooth buttons reach Jarvis through a media session (works while no music app holds the button). */
    private fun startMediaSession() {
        if (session != null || !JarvisPreferences.headsetButton.value) return
        runCatching {
            val s = MediaSession(this, "JarvisHeadset")
            s.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    val handled = ev.keyCode == KeyEvent.KEYCODE_HEADSETHOOK || ev.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        ev.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || ev.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                    if (!handled) return false
                    if (ev.action == KeyEvent.ACTION_UP) onHeadsetClick()
                    return true
                }
                override fun onPlay() = onHeadsetClick()
                override fun onPause() = onHeadsetClick()
            })
            @Suppress("DEPRECATION")
            s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PAUSED, 0, 1f)
                    .build()
            )
            s.isActive = true
            session = s
        }
    }

    private fun onHeadsetClick() {
        clicks++
        clickJob?.cancel()
        clickJob = scope.launch {
            delay(380)
            val n = clicks
            clicks = 0
            if (JarvisController.isAttached) JarvisController.onHeadsetClicks(n)
        }
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

    private fun notification(text: String, phase: JarvisController.Phase): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        fun action(a: String, code: Int) =
            PendingIntent.getService(this, code, Intent(this, JarvisService::class.java).setAction(a), flags)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis_mic)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (phase == JarvisController.Phase.LISTENING) b.addAction(0, "Send", action(ACTION_SEND, 3))
        else b.addAction(0, "Talk", action(ACTION_TALK, 1))
        b.addAction(0, "Stop", action(ACTION_STOP, 2))
        return b.build()
    }

    override fun onDestroy() {
        job?.cancel()
        clickJob?.cancel()
        scope.cancel()
        runCatching { session?.isActive = false; session?.release() }
        session = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jarvis_hands_free"
        private const val NOTIF_ID = 7712
        const val ACTION_TALK = "com.example.gemini.jarvis.TALK"
        const val ACTION_SEND = "com.example.gemini.jarvis.SEND"
        const val ACTION_STOP = "com.example.gemini.jarvis.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }
}
