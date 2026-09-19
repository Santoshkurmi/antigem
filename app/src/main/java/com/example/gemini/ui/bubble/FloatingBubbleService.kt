package com.example.gemini.ui.bubble

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.gemini.MainActivity
import com.example.gemini.R
import com.example.gemini.theme.GeminiTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FloatingBubbleService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var bubbleX = 30
    private var bubbleY = 400

    companion object {
        private const val CHANNEL_ID = "antiGem_floating_bubble_channel"
        private const val NOTIFICATION_ID = 2001

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _isMainAppForeground = MutableStateFlow(false)
        val isMainAppForeground: StateFlow<Boolean> = _isMainAppForeground.asStateFlow()

        private val _isActivityOpen = MutableStateFlow(false)
        val isActivityOpen: StateFlow<Boolean> = _isActivityOpen.asStateFlow()

        fun setMainAppForeground(foreground: Boolean) {
            _isMainAppForeground.value = foreground
        }

        fun setActivityOpen(open: Boolean) {
            _isActivityOpen.value = open
        }

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        _isServiceRunning.value = true

        startForegroundNotification()
        setupOverlayBubble()
    }

    private fun startForegroundNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "antiGem Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the antiGem floating chat & terminal bubble active over other apps"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("antiGem Bubble Active")
            .setContentText("Tap the floating bubble to chat or run terminal commands")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupOverlayBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val displayMetrics = resources.displayMetrics
        bubbleX = displayMetrics.widthPixels - 180
        bubbleY = (displayMetrics.heightPixels * 0.35f).toInt()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)

            setContent {
                val isForeground by _isMainAppForeground.collectAsState()
                val isActivityOpen by _isActivityOpen.collectAsState()
                val shouldShow = !isForeground && !isActivityOpen

                LaunchedEffect(shouldShow) {
                    this@apply.visibility = if (shouldShow) android.view.View.VISIBLE else android.view.View.GONE
                }

                GeminiTheme {
                    FloatingBubbleContent(
                        isVisible = shouldShow,
                        onClick = {
                            val intent = Intent(this@FloatingBubbleService, FloatingChatActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(intent)
                        },
                        onDrag = { dx, dy ->
                            updateBubblePosition(dx, dy)
                        },
                        onSnap = {
                            snapBubbleToEdge()
                        },
                        onDismissToDropZone = {
                            stopSelf()
                        }
                    )
                }
            }
        }

        try {
            windowManager?.addView(composeView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun updateBubblePosition(dx: Float, dy: Float) {
        val wm = windowManager ?: return
        val view = composeView ?: return

        val displayMetrics = resources.displayMetrics
        bubbleX = (bubbleX + dx.toInt()).coerceIn(0, displayMetrics.widthPixels - 100)
        bubbleY = (bubbleY + dy.toInt()).coerceIn(60, displayMetrics.heightPixels - 160)

        layoutParams.x = bubbleX
        layoutParams.y = bubbleY

        try {
            wm.updateViewLayout(view, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun snapBubbleToEdge() {
        val wm = windowManager ?: return
        val view = composeView ?: return

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val margin = 30
        val targetX = if (bubbleX + 70 < screenWidth / 2) {
            margin
        } else {
            screenWidth - 170 - margin
        }

        val startX = bubbleX
        val animator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { va ->
                bubbleX = va.animatedValue as Int
                layoutParams.x = bubbleX
                try {
                    wm.updateViewLayout(view, layoutParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        animator.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false

        if (composeView != null && windowManager != null) {
            try {
                windowManager?.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            composeView = null
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
