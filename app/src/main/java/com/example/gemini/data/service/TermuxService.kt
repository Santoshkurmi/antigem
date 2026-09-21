package com.example.gemini.data.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.gemini.MainActivity
import com.example.gemini.R
import com.example.gemini.data.local.LocalTerminalManager
import com.example.gemini.ui.bubble.FloatingChatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TermuxService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentSessionCount: Int = 0

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "TermuxService"
        private const val CHANNEL_ID = "antigem_termux_service_channel"
        private const val NOTIFICATION_ID = 1337

        const val ACTION_WAKE_LOCK = "com.termux.service_wake_lock"
        const val ACTION_WAKE_UNLOCK = "com.termux.service_wake_unlock"
        const val ACTION_STOP_SERVICE = "com.termux.service_stop"

        fun start(context: Context) {
            val intent = Intent(context, TermuxService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TermuxService::class.java).setAction(ACTION_STOP_SERVICE)
            context.startService(intent)
        }

        fun requestDisableBatteryOptimizations(context: Context) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed requesting battery optimization ignore: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        runStartForeground()
        observeTerminalSessions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "ACTION_STOP_SERVICE intent received")
                actionStopService()
                return START_NOT_STICKY
            }
            ACTION_WAKE_LOCK -> {
                Log.d(TAG, "ACTION_WAKE_LOCK intent received")
                actionAcquireWakeLock()
            }
            ACTION_WAKE_UNLOCK -> {
                Log.d(TAG, "ACTION_WAKE_UNLOCK intent received")
                actionReleaseWakeLock()
            }
            else -> {
                runStartForeground()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        actionReleaseWakeLock()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun observeTerminalSessions() {
        serviceScope.launch {
            LocalTerminalManager.sessions.collect { sessions ->
                currentSessionCount = sessions.size
                updateNotification()
            }
        }
    }

    private fun runStartForeground() {
        setupNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "AntiGem Terminal & Execution Service"
            val descriptionText = "Terminal sessions and background execution"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val wakeLockHeld = wakeLock != null

        val sessionText = "$currentSessionCount " + if (currentSessionCount == 1) "session" else "sessions"
        val notificationText = if (wakeLockHeld) "$sessionText (wake lock held)" else sessionText

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Float Window Action
        val floatIntent = Intent(this, FloatingChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        val floatPendingIntent = PendingIntent.getActivity(
            this, 3, floatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Exit action
        val exitIntent = Intent(this, TermuxService::class.java).setAction(ACTION_STOP_SERVICE)
        val exitPendingIntent = PendingIntent.getService(
            this, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // WakeLock toggle action
        val wakeAction = if (wakeLockHeld) ACTION_WAKE_UNLOCK else ACTION_WAKE_LOCK
        val toggleWakeLockIntent = Intent(this, TermuxService::class.java).setAction(wakeAction)
        val wakePendingIntent = PendingIntent.getService(
            this, 2, toggleWakeLockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val wakeActionTitle = if (wakeLockHeld) "Release wakelock" else "Acquire wakelock"
        val wakeActionIcon = if (wakeLockHeld) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_lock

        val priority = if (wakeLockHeld) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AntiGem")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(priority)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPendingIntent)
            .addAction(wakeActionIcon, wakeActionTitle, wakePendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Float", floatPendingIntent)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun actionAcquireWakeLock() {
        if (wakeLock != null) {
            Log.d(TAG, "Ignoring acquiring WakeLocks since they are already held")
            return
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "termux:service-wakelock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Acquired partial WakeLock")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "termux").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Acquired high-perf WifiLock")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }

        requestDisableBatteryOptimizations(this)
        updateNotification()
    }

    private fun actionReleaseWakeLock() {
        if (wakeLock == null && wifiLock == null) {
            Log.d(TAG, "Ignoring releasing WakeLocks since none are already held")
            return
        }

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WifiLock: ${e.message}")
        }

        updateNotification()
        Log.d(TAG, "WakeLocks released successfully")
    }

    private fun actionStopService() {
        Log.d(TAG, "actionStopService: Force-killing all background processes and stopping service")
        actionReleaseWakeLock()
        try {
            com.example.gemini.data.local.LocalServerManager.forceKillAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error force killing server manager: ${e.message}")
        }
        try {
            LocalTerminalManager.closeAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing terminal sessions: ${e.message}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }
}
