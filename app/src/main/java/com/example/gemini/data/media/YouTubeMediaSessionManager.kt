package com.example.gemini.data.media

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.gemini.MainActivity
import com.example.gemini.ui.components.MarkdownBlock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton manager that retains the active YouTube player WebView across Compose list recycling,
 * and maintains the Android MediaSession & System Media Notification with full playback controls.
 */
object YouTubeMediaSessionManager {
    private const val TAG = "YouTubeMediaSession"
    private const val CHANNEL_ID = "antigravity_youtube_playback"
    private const val NOTIFICATION_ID = 8092

    const val ACTION_YT_PLAY = "com.antigem.action.YT_PLAY"
    const val ACTION_YT_PAUSE = "com.antigem.action.YT_PAUSE"
    const val ACTION_YT_STOP = "com.antigem.action.YT_STOP"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { block() } catch (e: Throwable) { Log.e(TAG, "Error on main thread", e) }
        } else {
            mainHandler.post {
                try { block() } catch (e: Throwable) { Log.e(TAG, "Error on main thread", e) }
            }
        }
    }

    @Volatile
    private var activeWebView: WebView? = null
    @Volatile
    private var activeVideoId: String? = null
    @Volatile
    private var activeTitle: String = "YouTube Video"
    @Volatile
    private var activeThumbnailUrl: String? = null
    @Volatile
    private var activeDurationMs: Long = 0L
    @Volatile
    private var activePositionMs: Long = 0L
    @Volatile
    private var isPlaying: Boolean = false
    private var thumbnailBitmap: Bitmap? = null

    private var mediaSession: MediaSession? = null
    private var isReceiverRegistered = false

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_YT_PLAY -> play()
                ACTION_YT_PAUSE -> pause()
                ACTION_YT_STOP -> release()
            }
        }
    }

    data class ActiveVideoPlayback(
        val videoId: String,
        val title: String,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val thumbnailUrl: String?
    )

    private val _playbackState = MutableStateFlow<ActiveVideoPlayback?>(null)
    val playbackState: StateFlow<ActiveVideoPlayback?> = _playbackState.asStateFlow()

    private fun syncPlaybackState() {
        val id = activeVideoId
        if (id == null) {
            _playbackState.value = null
        } else {
            _playbackState.value = ActiveVideoPlayback(
                videoId = id,
                title = activeTitle,
                isPlaying = isPlaying,
                positionMs = activePositionMs,
                durationMs = activeDurationMs,
                thumbnailUrl = activeThumbnailUrl
            )
        }
    }

    fun getActiveVideoId(): String? = activeVideoId

    fun isVideoActive(videoId: String): Boolean = activeVideoId == videoId && activeWebView != null

    @Volatile
    private var customViewHandler: ((android.view.View, WebChromeClient.CustomViewCallback) -> Unit)? = null
    @Volatile
    private var hideCustomViewHandler: (() -> Unit)? = null

    fun updateCallbacks(
        onCustomView: ((android.view.View, WebChromeClient.CustomViewCallback) -> Unit)?,
        onHideCustomView: (() -> Unit)?
    ) {
        customViewHandler = onCustomView
        hideCustomViewHandler = onHideCustomView
    }

    class KeepAliveWebView(context: Context) : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            // Prevent Chromium from auto-pausing playback when scrolled offscreen or when app is minimized
            super.onWindowVisibilityChanged(android.view.View.VISIBLE)
        }

        override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
            super.onVisibilityChanged(changedView, android.view.View.VISIBLE)
        }

        override fun dispatchWindowVisibilityChanged(visibility: Int) {
            super.dispatchWindowVisibilityChanged(android.view.View.VISIBLE)
        }
    }

    @Volatile
    private var parkHolder: FrameLayout? = null

    private fun getOrCreateParkHolder(activity: Activity): FrameLayout {
        val existing = parkHolder
        if (existing != null && existing.parent != null) {
            return existing
        }
        val decorView = activity.window.decorView as? ViewGroup ?: (activity.findViewById(android.R.id.content) as? ViewGroup)
        val holder = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            visibility = android.view.View.VISIBLE
            alpha = 0f
        }
        decorView?.addView(holder)
        parkHolder = holder
        return holder
    }

    /**
     * Park the active WebView in a tiny invisible container attached to the Window
     * so that Chromium never considers it detached when scrolled offscreen.
     */
    fun parkActivePlayer(context: Context) {
        val wv = activeWebView ?: return
        val activity = (context as? Activity)
            ?: (wv.context as? Activity)
            ?: ((context as? android.content.ContextWrapper)?.baseContext as? Activity)
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
                val holder = getOrCreateParkHolder(activity)
                if (wv.parent != holder) {
                    holder.addView(wv)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to park active player: ${e.message}")
            }
        }
    }

    /**
     * Obtains or creates a cached WebView for seamless playback without getting destroyed by LazyColumn scrolling.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreatePlayer(
        context: Context,
        video: MarkdownBlock.YouTubeVideo,
        onCustomView: ((android.view.View, WebChromeClient.CustomViewCallback) -> Unit)? = null,
        onHideCustomView: (() -> Unit)? = null
    ): WebView {
        customViewHandler = onCustomView
        hideCustomViewHandler = onHideCustomView

        val current = activeWebView
        if (current != null && activeVideoId == video.videoId) {
            (current.parent as? ViewGroup)?.removeView(current)
            return current
        }

        // Clean up any previously playing video
        release()

        customViewHandler = onCustomView
        hideCustomViewHandler = onHideCustomView

        val appContext = context.applicationContext
        activeVideoId = video.videoId
        activeTitle = video.title?.takeIf { it.isNotBlank() } ?: "YouTube Video"
        activeThumbnailUrl = video.customThumbnailUrl?.takeIf { it.isNotBlank() }
            ?: "https://img.youtube.com/vi/${video.videoId}/hqdefault.jpg"

        initMediaSession(appContext)
        loadThumbnailAsync(appContext, activeThumbnailUrl)

        val webView = KeepAliveWebView(appContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
            isNestedScrollingEnabled = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = true
                allowContentAccess = true
            }

            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }

            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                    customViewHandler?.invoke(view, callback)
                }

                override fun onHideCustomView() {
                    hideCustomViewHandler?.invoke()
                }
            }

            addJavascriptInterface(
                YouTubeMediaJsInterface(
                    onStateChange = { state, currentTimeMs, durationMs ->
                        handlePlayerState(appContext, state, currentTimeMs, durationMs)
                    },
                    onTimeUpdate = { currentTimeMs, durationMs ->
                        handleTimeUpdate(appContext, currentTimeMs, durationMs)
                    }
                ),
                "AndroidMedia"
            )

            val embedHtml = buildYouTubeHtml(video.videoId)
            tag = embedHtml
            loadDataWithBaseURL("https://www.youtube-nocookie.com", embedHtml, "text/html", "UTF-8", null)
        }

        activeWebView = webView
        syncPlaybackState()
        return webView
    }

    private fun buildYouTubeHtml(videoId: String): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <style>
            * { margin: 0; padding: 0; box-sizing: border-box; background: #000; }
            html, body { width: 100%; height: 100%; overflow: hidden; background: #000; }
            #player { width: 100%; height: 100%; border: none; }
          </style>
        </head>
        <body>
          <div id="player"></div>
          <script>
            // Override Page Visibility & Focus APIs to prevent YouTube from auto-pausing on scroll or background
            try {
              Object.defineProperty(document, 'hidden', { get: function() { return false; } });
              Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; } });
              Object.defineProperty(document, 'webkitHidden', { get: function() { return false; } });
              Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; } });
            } catch(e) {}

            ['visibilitychange', 'webkitvisibilitychange', 'blur', 'focusout', 'pagehide'].forEach(function(evt) {
              window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
              document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
            });

            var tag = document.createElement('script');
            tag.src = "https://www.youtube.com/iframe_api";
            var firstScriptTag = document.getElementsByTagName('script')[0];
            firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

            var player;
            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                videoId: '$videoId',
                playerVars: {
                  'autoplay': 1,
                  'playsinline': 1,
                  'rel': 0,
                  'modestbranding': 1,
                  'fs': 1,
                  'enablejsapi': 1,
                  'origin': 'https://www.youtube-nocookie.com'
                },
                events: {
                  'onReady': onPlayerReady,
                  'onStateChange': onPlayerStateChange
                }
              });
            }

            var timeInterval;
            function onPlayerReady(event) {
              try {
                var duration = player.getDuration();
                if (window.AndroidMedia) {
                  window.AndroidMedia.onPlayerReady(duration);
                }
              } catch(e) {}
            }

            function onPlayerStateChange(event) {
              try {
                var state = event.data;
                var currentTime = player.getCurrentTime();
                var duration = player.getDuration();
                if (window.AndroidMedia) {
                  window.AndroidMedia.onPlayerStateChange(state, currentTime, duration);
                }
                if (state === 1) {
                  startProgressTracker();
                } else {
                  stopProgressTracker();
                }
              } catch(e) {}
            }

            function startProgressTracker() {
              stopProgressTracker();
              timeInterval = setInterval(function() {
                try {
                  if (player && window.AndroidMedia) {
                    window.AndroidMedia.onTimeUpdate(player.getCurrentTime(), player.getDuration());
                  }
                } catch(e) {}
              }, 1000);
            }

            function stopProgressTracker() {
              if (timeInterval) {
                clearInterval(timeInterval);
                timeInterval = null;
              }
            }
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun initMediaSession(context: Context) {
        appContext = context.applicationContext
        if (mediaSession != null) return

        ensureChannelCreated(context)
        registerReceiver(context)

        mediaSession = MediaSession(context, "AntiGemYouTubeMedia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    runOnMainThread { play() }
                }

                override fun onPause() {
                    runOnMainThread { pause() }
                }

                override fun onSeekTo(pos: Long) {
                    runOnMainThread { seekTo(pos) }
                }

                override fun onStop() {
                    runOnMainThread { release() }
                }
            }, mainHandler)
            isActive = true
        }
    }

    private fun registerReceiver(context: Context) {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_YT_PLAY)
                addAction(ACTION_YT_PAUSE)
                addAction(ACTION_YT_STOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(mediaActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.applicationContext.registerReceiver(mediaActionReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    @Volatile
    private var isBrowserMedia: Boolean = false

    fun play() {
        runOnMainThread {
            if (isBrowserMedia) {
                activeWebView?.evaluateJavascript("var v = document.querySelector('video'); if (v) { v.play(); }", null)
            } else {
                activeWebView?.evaluateJavascript("if (player && player.playVideo) { player.playVideo(); }", null)
            }
        }
    }

    fun pause() {
        runOnMainThread {
            if (isBrowserMedia) {
                activeWebView?.evaluateJavascript("var v = document.querySelector('video'); if (v) { v.pause(); }", null)
            } else {
                activeWebView?.evaluateJavascript("if (player && player.pauseVideo) { player.pauseVideo(); }", null)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        runOnMainThread {
            val sec = positionMs / 1000.0
            activePositionMs = positionMs
            if (isBrowserMedia) {
                activeWebView?.evaluateJavascript("var v = document.querySelector('video'); if (v) { v.currentTime = $sec; }", null)
            } else {
                activeWebView?.evaluateJavascript("if (player && player.seekTo) { player.seekTo($sec, true); }", null)
            }
        }
    }

    fun onBrowserMediaState(
        context: Context,
        webView: WebView,
        state: Int,
        title: String,
        url: String,
        currentTimeMs: Long,
        durationMs: Long
    ) {
        runOnMainThread {
            if (activeWebView != null && !isBrowserMedia && isPlaying) {
                return@runOnMainThread
            }

            val appCtx = context.applicationContext
            appContext = appCtx
            isBrowserMedia = true
            activeWebView = webView

            val ytVideoId = extractYouTubeVideoId(url)
            activeVideoId = ytVideoId ?: "browser_media"
            activeTitle = title.takeIf { it.isNotBlank() && it != "New Tab" } ?: "Browser Video"
            activeThumbnailUrl = if (!ytVideoId.isNullOrBlank()) {
                "https://img.youtube.com/vi/$ytVideoId/hqdefault.jpg"
            } else null

            initMediaSession(appCtx)
            loadThumbnailAsync(appCtx, activeThumbnailUrl)
            handlePlayerState(appCtx, state, currentTimeMs, durationMs)
        }
    }

    fun onBrowserTimeUpdate(currentTimeMs: Long, durationMs: Long) {
        runOnMainThread {
            if (isBrowserMedia) {
                val ctx = appContext ?: activeWebView?.context?.applicationContext ?: return@runOnMainThread
                handleTimeUpdate(ctx, currentTimeMs, durationMs)
            }
        }
    }

    private fun extractYouTubeVideoId(url: String): String? {
        if (url.isBlank()) return null
        val reg = Regex("(?:v=|youtu\\.be/|embed/|shorts/)([a-zA-Z0-9_-]{11})")
        return reg.find(url)?.groupValues?.getOrNull(1)
    }

    private fun handlePlayerState(context: Context, state: Int, currentTimeMs: Long, durationMs: Long) {
        activePositionMs = currentTimeMs
        if (durationMs > 0) activeDurationMs = durationMs

        when (state) {
            1 -> { // PLAYING
                isPlaying = true
                updateMediaSessionState(PlaybackState.STATE_PLAYING, activePositionMs)
                updateNotification(context)
            }
            2 -> { // PAUSED
                isPlaying = false
                updateMediaSessionState(PlaybackState.STATE_PAUSED, activePositionMs)
                updateNotification(context)
            }
            0 -> { // ENDED
                isPlaying = false
                updateMediaSessionState(PlaybackState.STATE_STOPPED, activePositionMs)
                hideNotification(context)
            }
            3 -> { // BUFFERING
                updateMediaSessionState(PlaybackState.STATE_BUFFERING, activePositionMs)
            }
        }
        syncPlaybackState()
    }

    private fun handleTimeUpdate(context: Context, currentTimeMs: Long, durationMs: Long) {
        activePositionMs = currentTimeMs
        if (durationMs > 0) activeDurationMs = durationMs
        if (isPlaying) {
            updateMediaSessionState(PlaybackState.STATE_PLAYING, activePositionMs)
        }
        syncPlaybackState()
    }

    private fun updateMediaSessionState(state: Int, positionMs: Long) {
        val session = mediaSession ?: return

        val playbackSpeed = if (state == PlaybackState.STATE_PLAYING) 1.0f else 0.0f
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP
            )
            .setState(state, positionMs, playbackSpeed)
            .build()
        session.setPlaybackState(playbackState)

        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, activeTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "YouTube")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Antigravity Chat")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, activeDurationMs)

        thumbnailBitmap?.let {
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
        }
        session.setMetadata(metadataBuilder.build())
    }

    private fun updateNotification(context: Context) {
        val session = mediaSession ?: return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(if (isPlaying) ACTION_YT_PAUSE else ACTION_YT_PLAY).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent(ACTION_YT_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(activeTitle)
            .setContentText("YouTube Video")
            .setSubText("Antigravity")
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopIntent)
            .setStyle(mediaStyle)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, playPauseIcon),
                    playPauseTitle,
                    playPauseIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Close",
                    stopIntent
                ).build()
            )

        thumbnailBitmap?.let {
            builder.setLargeIcon(it)
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post media notification: ${e.message}")
        }
    }

    private fun hideNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    private fun loadThumbnailAsync(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                val drawable = result.drawable
                if (drawable is BitmapDrawable) {
                    thumbnailBitmap = drawable.bitmap
                    withContext(Dispatchers.Main) {
                        updateMediaSessionState(
                            if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                            activePositionMs
                        )
                        updateNotification(context)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Thumbnail download for notification skipped: ${e.message}")
            }
        }
    }

    private fun ensureChannelCreated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YouTube Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows playback controls for videos in chat"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun release() {
        runOnMainThread {
            val ctx = appContext ?: activeWebView?.context
            if (ctx != null) {
                hideNotification(ctx)
                if (isReceiverRegistered) {
                    try {
                        ctx.applicationContext.unregisterReceiver(mediaActionReceiver)
                    } catch (_: Exception) {}
                    isReceiverRegistered = false
                }
            }

            mediaSession?.let {
                it.isActive = false
                it.release()
            }
            mediaSession = null

            if (isBrowserMedia) {
                try {
                    activeWebView?.evaluateJavascript("var v = document.querySelector('video'); if (v) { v.pause(); }", null)
                } catch (_: Exception) {}
            } else {
                parkHolder?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                }
                parkHolder = null

                activeWebView?.let { wv ->
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    try { wv.destroy() } catch (_: Exception) {}
                }
            }
            isBrowserMedia = false
            activeWebView = null
            activeVideoId = null
            activeTitle = "YouTube Video"
            activeThumbnailUrl = null
            thumbnailBitmap = null
            isPlaying = false
            activePositionMs = 0L
            activeDurationMs = 0L
            syncPlaybackState()
        }
    }

    class YouTubeMediaJsInterface(
        private val onStateChange: (state: Int, currentTimeMs: Long, durationMs: Long) -> Unit,
        private val onTimeUpdate: (currentTimeMs: Long, durationMs: Long) -> Unit
    ) {
        @JavascriptInterface
        fun onPlayerReady(durationSec: Double) {
            onStateChange(2, 0L, (durationSec * 1000).toLong())
        }

        @JavascriptInterface
        fun onPlayerStateChange(state: Int, currentTimeSec: Double, durationSec: Double) {
            onStateChange(state, (currentTimeSec * 1000).toLong(), (durationSec * 1000).toLong())
        }

        @JavascriptInterface
        fun onTimeUpdate(currentTimeSec: Double, durationSec: Double) {
            onTimeUpdate((currentTimeSec * 1000).toLong(), (durationSec * 1000).toLong())
        }
    }
}
