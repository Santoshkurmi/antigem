package com.example.gemini.ui.components

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.example.gemini.data.files.FileOpener
import com.example.gemini.data.remote.HubMediaResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

/** Which view shows the picture: the chat card or the full-screen player (full screen wins while it is open). */
enum class VideoSlot { INLINE, FULLSCREEN }

/** One video at a time: starting one pauses whichever was playing. */
internal object ActiveVideo {
    private var current: WeakReference<VideoPlayerController>? = null

    fun claim(c: VideoPlayerController) {
        val prev = current?.get()
        if (prev != null && prev !== c) prev.pause()
        current = WeakReference(c)
    }

    fun drop(c: VideoPlayerController) {
        if (current?.get() === c) current = null
    }

    fun pauseCurrent() {
        current?.get()?.pause()
    }
}

/**
 * Plays one video with Android's MediaPlayer. Files on the phone play from disk; files only the bridge sees are
 * streamed (with seeking), so a 2 GB video starts in a moment and is never held in memory or copied first. Only if a
 * stream cannot be played is the file downloaded once, with progress. Shared by the chat card and the full-screen
 * player: the full-screen view takes over the picture without restarting playback.
 *
 * Owners call [retain] / [releaseRef]; the player is freed when the last owner lets go.
 */
@Stable
class VideoPlayerController(context: Context, val source: String) {

    enum class Phase { IDLE, PREPARING, READY, ERROR }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val name: String = HubMediaResolver.displayNameOf(source)

    /** Where the thumbnail is read from (the original source, even after a fallback download). */
    val previewStream: HubMediaResolver.MediaStream = HubMediaResolver.mediaStreamFor(source)
    private var stream = previewStream

    var phase by mutableStateOf(Phase.IDLE); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    /** False until the first picture is on screen (the thumbnail stays up until then). */
    var hasFrame by mutableStateOf(false); private set
    var completed by mutableStateOf(false); private set
    var durationMs by mutableIntStateOf(0); private set
    var positionMs by mutableIntStateOf(0); private set
    var bufferedFraction by mutableFloatStateOf(0f); private set
    var videoWidth by mutableIntStateOf(0); private set
    var videoHeight by mutableIntStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var muted by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** A longer job for this video (fallback download, preparing a share) and its progress. */
    var task by mutableStateOf<String?>(null); private set
    var taskProgress by mutableStateOf<Float?>(null); private set

    val aspect: Float? get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else null

    private var player: MediaPlayer? = null
    private var inlineSurface: Surface? = null
    private var fullSurface: Surface? = null
    private var playWhenReady = false
    private var seekTarget: Int? = null
    private var triedDownload = false
    private var pollJob: Job? = null
    private var taskJob: Job? = null
    private var refs = 0
    private var released = false

    fun retain() {
        refs++
    }

    fun releaseRef() {
        refs--
        if (refs <= 0) release()
    }

    // ---------------- playback ----------------

    fun play() {
        if (released) return
        ActiveVideo.claim(this)
        error = null
        val p = player
        when {
            p == null || phase == Phase.ERROR -> {
                playWhenReady = true
                open()
            }
            phase == Phase.READY -> start(p)
            else -> playWhenReady = true
        }
    }

    fun pause() {
        playWhenReady = false
        val p = player ?: return
        try { if (p.isPlaying) p.pause() } catch (_: Exception) {}
        isPlaying = false
        syncPosition()
    }

    fun toggle() = if (isPlaying || (playWhenReady && phase == Phase.PREPARING)) pause() else play()

    fun retry() {
        triedDownload = false
        stream = previewStream
        player?.let { releasePlayer() }
        phase = Phase.IDLE
        play()
    }

    private fun start(p: MediaPlayer) {
        try {
            if (completed) {
                seekInternal(p, 0)
                positionMs = 0
                completed = false
            }
            p.start()
            applySpeed(p)
            isPlaying = true
            playWhenReady = false
            startPolling()
        } catch (_: Exception) {
        }
    }

    /** Seeks to the exact frame (not the previous keyframe, which in short clips is often 0:00). */
    fun seekTo(ms: Int) {
        val d = durationMs
        if (d <= 0) return
        val t = ms.coerceIn(0, d)
        positionMs = t
        if (t < d) completed = false
        val p = player ?: return
        if (phase != Phase.READY) {
            seekTarget = t
            return
        }
        seekInternal(p, t)
    }

    fun seekBy(deltaMs: Int) = seekTo((seekTarget ?: positionMs) + deltaMs)

    private fun seekInternal(p: MediaPlayer, t: Int) {
        seekTarget = t
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.seekTo(t.toLong(), MediaPlayer.SEEK_CLOSEST) else p.seekTo(t)
        } catch (_: Exception) {
            seekTarget = null
        }
    }

    fun setPlaybackSpeed(value: Float) {
        speed = value
        val p = player ?: return
        if (isPlaying) applySpeed(p)
    }

    /** Only while playing: on many phones setting the speed of a paused player starts it. */
    private fun applySpeed(p: MediaPlayer) {
        if (speed == 1f && !speedChanged) return
        speedChanged = true
        try { p.playbackParams = p.playbackParams.setSpeed(speed) } catch (_: Exception) {}
    }

    private var speedChanged = false

    fun toggleMute() {
        muted = !muted
        val v = if (muted) 0f else 1f
        try { player?.setVolume(v, v) } catch (_: Exception) {}
    }

    private fun syncPosition() {
        val p = player ?: return
        if (seekTarget == null && phase == Phase.READY) positionMs = runCatching { p.currentPosition }.getOrDefault(positionMs)
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && isPlaying) {
                syncPosition()
                delay(200)
            }
        }
    }

    // ---------------- surfaces ----------------

    fun attachSurface(slot: VideoSlot, surface: Surface?) {
        if (slot == VideoSlot.FULLSCREEN) fullSurface = surface else inlineSurface = surface
        val target = fullSurface ?: inlineSurface
        val p = player ?: return
        try { p.setSurface(target) } catch (_: Exception) {}
        if (target == null) {
            // the card scrolled away and nothing else shows the picture
            if (isPlaying) pause()
        } else if (phase == Phase.READY && !isPlaying) {
            // a paused player does not repaint a new view on its own
            seekInternal(p, seekTarget ?: positionMs)
        }
    }

    // ---------------- player lifecycle ----------------

    private fun open() {
        if (released) return
        releasePlayer()
        phase = Phase.PREPARING
        hasFrame = false
        isBuffering = false
        bufferedFraction = if (stream.viaBridge || stream.uri.scheme?.startsWith("http") == true) 0f else 1f
        val s = stream
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            )
            if (s.headers.isEmpty()) mp.setDataSource(appContext, s.uri) else mp.setDataSource(appContext, s.uri, s.headers)
            mp.setOnPreparedListener { onPrepared(it) }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                }
            }
            mp.setOnBufferingUpdateListener { _, pct -> bufferedFraction = (pct / 100f).coerceIn(0f, 1f) }
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> isBuffering = true
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> isBuffering = false
                    MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                        isBuffering = false
                        hasFrame = true
                    }
                }
                false
            }
            mp.setOnSeekCompleteListener {
                seekTarget = null
                if (phase == Phase.READY) hasFrame = true
                syncPosition()
            }
            mp.setOnCompletionListener {
                isPlaying = false
                completed = true
                positionMs = durationMs
            }
            mp.setOnErrorListener { _, _, _ ->
                // freeing the player inside its own callback is unsafe: do it right after
                scope.launch { onPlayerError() }
                true
            }
            (fullSurface ?: inlineSurface)?.let { mp.setSurface(it) }
            if (muted) mp.setVolume(0f, 0f)
            mp.prepareAsync()
        } catch (_: Exception) {
            scope.launch { onPlayerError() }
        }
    }

    private fun onPrepared(mp: MediaPlayer) {
        if (player !== mp) return
        durationMs = mp.duration.coerceAtLeast(0)
        if (mp.videoWidth > 0 && mp.videoHeight > 0) {
            videoWidth = mp.videoWidth
            videoHeight = mp.videoHeight
        }
        phase = Phase.READY
        isBuffering = false
        val pendingSeek = seekTarget ?: positionMs.takeIf { it > 0 && it < durationMs }
        pendingSeek?.let { seekInternal(mp, it) }
        if (playWhenReady) start(mp)
    }

    private fun onPlayerError() {
        pollJob?.cancel()
        isPlaying = false
        isBuffering = false
        releasePlayer()
        // a stream the player cannot handle (or an older bridge without streaming): fetch the file once instead
        if (stream.viaBridge && !triedDownload) {
            triedDownload = true
            downloadThenPlay()
            return
        }
        phase = Phase.ERROR
        error = "This video can't be played here"
    }

    private fun downloadThenPlay() {
        val primary = stream.uri.toString()
        val legacy = listOfNotNull(HubMediaResolver.bridgeUrlFor(source))
        phase = Phase.PREPARING
        runTask("Loading video…") { progress ->
            val file = HubMediaResolver.downloadBridgeMedia(appContext, primary, legacy, progress)
            if (file == null) {
                phase = Phase.ERROR
                error = "Could not load this video from the IDE bridge"
            } else {
                stream = HubMediaResolver.MediaStream(Uri.fromFile(file), emptyMap(), false)
                playWhenReady = true
                open()
            }
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        pollJob?.cancel()
        try { p.setSurface(null) } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
        isPlaying = false
    }

    private fun release() {
        if (released) return
        released = true
        ActiveVideo.drop(this)
        taskJob?.cancel()
        releasePlayer()
        scope.cancel()
        phase = Phase.IDLE
    }

    // ---------------- tasks: share / open elsewhere ----------------

    private fun runTask(label: String, block: suspend (onProgress: (Float?) -> Unit) -> Unit) {
        if (taskJob?.isActive == true) return
        task = label
        taskProgress = null
        taskJob = scope.launch {
            try {
                // progress comes from the download thread; ignore any that lands after the task ended
                block { p -> scope.launch { if (task != null) taskProgress = p } }
            } finally {
                task = null
                taskProgress = null
            }
        }
    }

    fun cancelTask() {
        taskJob?.cancel()
        if (phase == Phase.PREPARING && player == null) phase = Phase.IDLE
    }

    /** A `content://` uri other apps can read: files on the phone go through FileProvider, bridge videos are fetched once. */
    private suspend fun shareableUri(onProgress: (Float?) -> Unit): Uri? {
        val s = previewStream
        return when {
            s.viaBridge -> HubMediaResolver.downloadBridgeMedia(appContext, s.uri.toString(), listOfNotNull(HubMediaResolver.bridgeUrlFor(source)), onProgress)
                ?.let { runCatching { FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", it) }.getOrNull() }
            s.uri.scheme == "file" -> s.uri.path?.let { p ->
                runCatching { FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", File(p)) }.getOrNull()
            }
            else -> s.uri
        }
    }

    /** Opens the video in another app (the user's own player). */
    fun openElsewhere() {
        pause()
        runTask("Preparing…") { progress ->
            val uri = shareableUri(progress)
            if (uri == null) AppToastHelper.showToast("Could not prepare $name", ChatToastType.ERROR)
            else FileOpener.view(appContext, uri, name, forceChooser = true, contentMime = "video/*")?.let { AppToastHelper.showToast(it, ChatToastType.ERROR) }
        }
    }

    fun share() {
        runTask("Preparing to share…") { progress ->
            val uri = shareableUri(progress)
            when {
                uri == null -> AppToastHelper.showToast("Could not prepare $name", ChatToastType.ERROR)
                uri.scheme == "http" || uri.scheme == "https" -> {
                    // a web video: share its link
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, uri.toString())
                    }
                    runCatching {
                        appContext.startActivity(Intent.createChooser(send, "Share video link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                else -> FileOpener.share(appContext, listOf(uri to name))?.let { AppToastHelper.showToast(it, ChatToastType.ERROR) }
            }
        }
    }
}
