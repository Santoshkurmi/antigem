package com.example.gemini.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private const val SKIP_MS = 10_000

// ---------------- full screen ----------------

/** The full-screen player, drawn once by [FullscreenVideoHost] above everything else. */
object FullscreenVideo {
    internal class Entry(val controller: VideoPlayerController, val title: String)

    internal var current by mutableStateOf<Entry?>(null)
        private set

    /** Continues a chat card's video full screen, without restarting it. */
    fun open(controller: VideoPlayerController, title: String) {
        close()
        controller.retain()
        current = Entry(controller, title)
    }

    /** Plays a file or link full screen in the built-in player ("Play in AntiGem"). */
    fun play(context: Context, source: String, title: String? = null) {
        val c = VideoPlayerController(context, source)
        open(c, title?.takeIf { it.isNotBlank() } ?: c.name)
        c.play()
    }

    fun close() {
        val e = current ?: return
        current = null
        e.controller.releaseRef()
    }
}

/** Put once at the root of the app: shows the full-screen player and pauses video when the app goes to the background. */
@Composable
fun FullscreenVideoHost() {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) ActiveVideo.pauseCurrent() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    FullscreenVideo.current?.let { entry ->
        FullscreenVideoDialog(entry.controller, entry.title, onClose = { FullscreenVideo.close() })
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
private fun FullscreenVideoDialog(c: VideoPlayerController, title: String, onClose: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val originalOrientation = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    // null: follow the video's shape; true / false: the user turned the phone with the rotate button
    var forceLandscape by remember { mutableStateOf<Boolean?>(null) }
    val videoLandscape = if (c.videoWidth > 0 && c.videoHeight > 0) c.videoWidth > c.videoHeight else null
    LaunchedEffect(forceLandscape, videoLandscape) {
        val landscape = forceLandscape ?: videoLandscape ?: return@LaunchedEffect
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else if (forceLandscape == false) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else originalOrientation
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = originalOrientation }
    }
    val isLandscapeNow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let { w ->
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                w.setDimAmount(0f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
                }
                WindowCompat.setDecorFitsSystemWindows(w, false)
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {}
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val ratio = c.aspect ?: VideoThumbnailCache.peek(c.source)?.aspect ?: (16f / 9f)
            // fit the whole picture on screen
            val boxRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val (w, h) = if (boxRatio > ratio) (maxHeight * ratio) to maxHeight else maxWidth to (maxWidth / ratio)
            Box(Modifier.size(w, h)) {
                VideoSurface(c, VideoSlot.FULLSCREEN, Modifier.fillMaxSize())
                if (!c.hasFrame) PreviewImage(VideoThumbnailCache.peek(c.source)?.bitmap, Modifier.fillMaxSize())
            }
            VideoOverlay(
                c = c,
                title = title,
                fullscreen = true,
                previewDurationMs = null,
                onToggleFullscreen = onClose,
                onRotate = { forceLandscape = !isLandscapeNow },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ---------------- chat card ----------------

/** A video in a chat message: thumbnail with its length, then an in-place player with full-screen, seek and share. */
@Composable
fun ChatVideoPlayer(source: String, caption: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = remember(source) { VideoPlayerController(context, source) }
    DisposableEffect(c) {
        c.retain()
        onDispose { c.releaseRef() }
    }
    val preview by produceState(VideoThumbnailCache.peek(source), source) {
        value = VideoThumbnailCache.load(context.applicationContext, source, c.previewStream)
    }
    val ratio = (c.aspect ?: preview?.aspect ?: (16f / 9f)).coerceIn(0.4f, 3f)
    val title = caption.ifBlank { c.name }
    val inFullscreen = FullscreenVideo.current?.controller === c

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .heightIn(max = 420.dp)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            // stays in place while full screen is open: the player switches views without a gap (no pause)
            if (c.phase != VideoPlayerController.Phase.IDLE) VideoSurface(c, VideoSlot.INLINE, Modifier.fillMaxSize())
            if (!c.hasFrame || inFullscreen || c.phase == VideoPlayerController.Phase.IDLE) PreviewImage(preview?.bitmap, Modifier.fillMaxSize())
            if (inFullscreen) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Fullscreen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Playing full screen", color = Color.White, fontSize = 13.sp)
                    }
                }
            } else {
                VideoOverlay(
                    c = c,
                    title = title,
                    fullscreen = false,
                    previewDurationMs = preview?.durationMs,
                    onToggleFullscreen = { FullscreenVideo.open(c, title) },
                    onRotate = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (caption.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = caption,
                fontSize = 11.5.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PreviewImage(bitmap: android.graphics.Bitmap?, modifier: Modifier) {
    if (bitmap == null) return
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun VideoSurface(c: VideoPlayerController, slot: VideoSlot, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(texture).also { c.attachSurface(slot, it) }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        c.attachSurface(slot, null)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                }
            }
        },
        update = { it.keepScreenOn = c.isPlaying },
        modifier = modifier
    )
}

// ---------------- controls ----------------

@Composable
private fun VideoOverlay(
    c: VideoPlayerController,
    title: String,
    fullscreen: Boolean,
    previewDurationMs: Int?,
    onToggleFullscreen: () -> Unit,
    onRotate: (() -> Unit)?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val idle = c.phase == VideoPlayerController.Phase.IDLE && c.task == null
    val ready = c.phase == VideoPlayerController.Phase.READY
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    // double-tap skip: which side, and how far so far (taps in a row add up)
    var skipForward by remember { mutableStateOf(true) }
    var skipSeconds by remember { mutableIntStateOf(0) }
    var skipTick by remember { mutableIntStateOf(0) }

    fun poke() {
        controlsVisible = true
        interaction++
    }

    LaunchedEffect(controlsVisible, c.isPlaying, interaction, scrubFraction, menuOpen, speedMenuOpen) {
        if (controlsVisible && c.isPlaying && scrubFraction == null && !menuOpen && !speedMenuOpen) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(skipTick) {
        if (skipTick > 0) {
            delay(750)
            skipSeconds = 0
        }
    }
    // controls come back when playback stops
    LaunchedEffect(c.isPlaying) { if (!c.isPlaying) controlsVisible = true }

    val big: Dp = if (fullscreen) 64.dp else 52.dp
    val pad: Dp = if (fullscreen) 16.dp else 8.dp

    Box(
        modifier
            .pointerInput(c) {
                detectTapGestures(
                    onTap = {
                        when (c.phase) {
                            VideoPlayerController.Phase.IDLE -> c.play()
                            VideoPlayerController.Phase.READY -> if (controlsVisible) controlsVisible = false else poke()
                            else -> poke()
                        }
                    },
                    onDoubleTap = { offset ->
                        if (c.phase == VideoPlayerController.Phase.READY) {
                            val forward = offset.x > size.width / 2f
                            if (skipSeconds == 0 || skipForward != forward) skipSeconds = 0
                            skipForward = forward
                            skipSeconds += SKIP_MS / 1000
                            skipTick++
                            c.seekBy(if (forward) SKIP_MS else -SKIP_MS)
                        } else if (c.phase == VideoPlayerController.Phase.IDLE) {
                            c.play()
                        }
                    }
                )
            }
    ) {
        // ----- before playing: big play button and the length -----
        if (idle) {
            PlayDisc(Icons.Filled.PlayArrow, big, Modifier.align(Alignment.Center)) { c.play() }
            val d = previewDurationMs ?: 0
            if (d > 0) {
                Text(
                    formatTime(d),
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (fullscreen) TopBar(title, onBack = onToggleFullscreen, pad = pad, menu = {})
        }

        // ----- error -----
        if (c.phase == VideoPlayerController.Phase.ERROR) {
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(6.dp))
                Text(c.error ?: "Video could not be loaded", color = Color.White, fontSize = 12.5.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c.retry() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Retry", color = Color.White, fontSize = 12.5.sp) }
                    Button(onClick = { c.openElsewhere() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Open in another app", fontSize = 12.5.sp) }
                }
            }
            if (fullscreen) TopBar(title, onBack = onToggleFullscreen, pad = pad, menu = {})
        }

        // ----- loading / buffering / downloading / preparing a share -----
        val loading = c.task != null || c.phase == VideoPlayerController.Phase.PREPARING || (ready && c.isBuffering)
        if (loading) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(big).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    val p = c.taskProgress
                    if (p != null) CircularProgressIndicator(progress = { p }, color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(big * 0.5f))
                    else CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(big * 0.5f))
                }
                c.task?.let { label ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        label + (c.taskProgress?.let { "  ${(it * 100).toInt()}%" } ?: ""),
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                    TextButton(onClick = { c.cancelTask() }) { Text("Cancel", color = Color.White, fontSize = 12.5.sp) }
                }
            }
        }

        // ----- double-tap skip bubble -----
        if (skipSeconds > 0) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f)
                    .align(if (skipForward) Alignment.CenterEnd else Alignment.CenterStart)
                    .clip(if (skipForward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50) else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (skipForward) Icons.Filled.FastForward else Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(if (fullscreen) 34.dp else 26.dp))
                    Text("$skipSeconds seconds", color = Color.White, fontSize = if (fullscreen) 13.sp else 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // ----- player controls -----
        AnimatedVisibility(visible = ready && controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.3f to Color.Transparent, 0.65f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)))
            ) {
                val menu: @Composable () -> Unit = {
                    Box {
                        ControlIcon(Icons.Filled.MoreVert, "More") { menuOpen = true; poke() }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Playback speed  ·  ${speedLabel(c.speed)}") },
                                leadingIcon = { Icon(Icons.Filled.Speed, null) },
                                onClick = { menuOpen = false; speedMenuOpen = true }
                            )
                            DropdownMenuItem(
                                text = { Text(if (c.muted) "Unmute" else "Mute") },
                                leadingIcon = { Icon(if (c.muted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, null) },
                                onClick = { menuOpen = false; c.toggleMute() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Download") },
                                leadingIcon = { Icon(Icons.Filled.Download, null) },
                                onClick = { menuOpen = false; ImageDownloadHelper.downloadVideo(context, c.source, title, scope) }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Filled.Share, null) },
                                onClick = { menuOpen = false; c.share() }
                            )
                            DropdownMenuItem(
                                text = { Text("Open in another app") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                onClick = { menuOpen = false; c.openElsewhere() }
                            )
                        }
                        DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                            PLAYBACK_SPEEDS.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(speedLabel(s), fontWeight = if (s == c.speed) FontWeight.Bold else FontWeight.Normal) },
                                    leadingIcon = { if (s == c.speed) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                                    onClick = { speedMenuOpen = false; c.setPlaybackSpeed(s) }
                                )
                            }
                        }
                    }
                }

                if (fullscreen) {
                    TopBar(title, onBack = onToggleFullscreen, pad = pad, menu = menu)
                } else {
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { menu() }
                }

                // center: back 10 s, play / pause, forward 10 s
                if (!c.isBuffering) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 40.dp else 24.dp)) {
                        ControlIcon(Icons.Filled.Replay10, "Back 10 seconds", size = big * 0.62f) { c.seekBy(-SKIP_MS); poke() }
                        PlayDisc(
                            when {
                                c.completed -> Icons.Filled.Replay
                                c.isPlaying -> Icons.Filled.Pause
                                else -> Icons.Filled.PlayArrow
                            },
                            big
                        ) { c.toggle(); poke() }
                        ControlIcon(Icons.Filled.Forward10, "Forward 10 seconds", size = big * 0.62f) { c.seekBy(SKIP_MS); poke() }
                    }
                }

                // bottom: time, seek bar, speed / rotate / full screen
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                        .padding(horizontal = pad)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = scrubFraction?.let { (it * c.durationMs).toInt() } ?: c.positionMs
                        Text(
                            "${formatTime(shown)} / ${formatTime(c.durationMs)}",
                            color = Color.White,
                            fontSize = if (fullscreen) 13.sp else 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        if (c.speed != 1f) {
                            Spacer(Modifier.width(8.dp))
                            Text(speedLabel(c.speed), color = Color.White, fontSize = 10.5.sp, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f)).padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (onRotate != null) ControlIcon(Icons.Filled.ScreenRotation, "Rotate", size = 22.dp) { onRotate(); poke() }
                        ControlIcon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, if (fullscreen) "Exit full screen" else "Full screen", size = if (fullscreen) 28.dp else 24.dp) { onToggleFullscreen() }
                    }
                    SeekBar(
                        c = c,
                        onScrub = { f -> scrubFraction = f; poke() },
                        modifier = Modifier.fillMaxWidth().padding(bottom = if (fullscreen) 8.dp else 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.TopBar(title: String, onBack: () -> Unit, pad: Dp, menu: @Composable () -> Unit) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = pad, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlIcon(Icons.AutoMirrored.Filled.ArrowBack, "Close") { onBack() }
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        menu()
    }
}

@Composable
private fun PlayDisc(icon: ImageVector, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Black.copy(alpha = 0.5f), modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = "Play or pause", tint = Color.White, modifier = Modifier.size(size * 0.6f))
        }
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, description: String, size: Dp = 24.dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size))
    }
}

/**
 * Drag anywhere on it (or tap) to jump. The time follows the finger, and the jump happens on release, to the exact
 * frame. The bar takes the touch from the start, so the chat list does not scroll instead.
 */
@Composable
private fun SeekBar(c: VideoPlayerController, onScrub: (Float?) -> Unit, modifier: Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val duration = c.durationMs
    val played = drag ?: if (duration > 0) (c.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .height(28.dp)
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun fraction(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    drag = fraction(down.position.x)
                    onScrub(drag)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        drag = fraction(change.position.x)
                        onScrub(drag)
                        change.consume()
                    }
                    drag?.let { c.seekTo((it * duration).toInt()) }
                    drag = null
                    onScrub(null)
                }
            }
    ) {
        val active = drag != null
        Canvas(Modifier.fillMaxSize()) {
            val trackH = (if (active) 5.dp else 3.dp).toPx()
            val y = size.height / 2f
            val r = CornerRadius(trackH / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y - trackH / 2f), Size(size.width, trackH), r)
            drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y - trackH / 2f), Size(size.width * c.bufferedFraction, trackH), r)
            drawRoundRect(accent, Offset(0f, y - trackH / 2f), Size(size.width * played, trackH), r)
            drawCircle(accent, radius = (if (active) 8.dp else 6.dp).toPx(), center = Offset(size.width * played, y))
        }
    }
}

private fun speedLabel(s: Float): String = if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s}×"

private fun formatTime(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
