package com.example.gemini.ui.miniapps

import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.gemini.data.miniapps.MiniAppNet
import java.io.File

/** One thing to show in the native viewer: a local file (already permission-checked) or an https URL. */
class MediaItem(val file: File?, val url: String?, val video: Boolean, val title: String)

/**
 * AntiGem.ui.viewMedia: a native full-screen viewer, like a phone's gallery. Swipe between items, pinch / double-tap
 * to zoom photos (HEIC too, decoded by Android), videos in the system player with its controls, share, close.
 * The result is the index the user was on when they closed it.
 */
@Composable
fun MediaViewerDialog(d: NativeDialog.MediaViewer) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = d.index.coerceIn(0, (d.items.size - 1).coerceAtLeast(0))) { d.items.size }
    var chrome by remember { mutableStateOf(true) }
    val close = { d.result.complete(pager.currentPage) }
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { close() }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { it }) { page ->
                val item = d.items[page]
                if (item.video) VideoPage(item, active = pager.currentPage == page && !pager.isScrollInProgress) { chrome = !chrome }
                else ZoomableImage(item) { chrome = !chrome }
            }
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                        .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                    val item = d.items.getOrNull(pager.currentPage)
                    Text(
                        (item?.title ?: "") + if (d.items.size > 1) "   ${pager.currentPage + 1} / ${d.items.size}" else "",
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (item != null) IconButton(onClick = { share(context, item) }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                }
            }
        }
    }
}

private fun share(context: android.content.Context, item: MediaItem) {
    val send = Intent(Intent.ACTION_SEND)
    if (item.file != null) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", item.file)
        send.setType(MiniAppNet.guessMime(item.file.name)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.url)
    context.startActivity(Intent.createChooser(send, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Pinch and double-tap zoom; at normal size one-finger drags go to the pager (swipe to the next photo). */
@Composable
private fun ZoomableImage(item: MediaItem, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(item) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                        else { scale = 2.5f; offset = Offset((size.width / 2f - tap.x) * 1.5f, (size.height / 2f - tap.y) * 1.5f) }
                    },
                )
            }
            .pointerInput(item) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            val maxX = size.width * (scale - 1) / 2f
                            val maxY = size.height * (scale - 1) / 2f
                            val next = offset + event.calculatePan()
                            offset = if (scale <= 1f) Offset.Zero else Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = item.file ?: item.url,
            contentDescription = item.title,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}

/** The system video player (VideoView + its controls); plays while the page is the one on screen. */
@Composable
private fun VideoPage(item: MediaItem, active: Boolean, onTap: () -> Unit) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    LaunchedEffect(active, view) {
        val v = view ?: return@LaunchedEffect
        if (active) v.start() else if (v.isPlaying) v.pause()
    }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(if (item.file != null) Uri.fromFile(item.file) else Uri.parse(item.url))
                    setOnPreparedListener { mp -> mp.isLooping = false; if (active) start(); controller.show(2500) }
                    view = this
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
