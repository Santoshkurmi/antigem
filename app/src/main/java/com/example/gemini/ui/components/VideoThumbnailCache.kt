package com.example.gemini.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.example.gemini.data.remote.HubMediaResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local previews only: no video or thumbnail files are written. Besides a small first frame it reads the
 * length and size, so the chat card has the right shape and shows the duration before anything plays. Bridge videos
 * are read over the stream (only the header and one frame are fetched, not the whole file).
 */
internal object VideoThumbnailCache {

    data class Info(val bitmap: Bitmap?, val durationMs: Int, val width: Int, val height: Int) {
        val aspect: Float? get() = if (width > 0 && height > 0) width.toFloat() / height else null
    }

    private val previews = object : LruCache<String, Info>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Info): Int = value.bitmap?.allocationByteCount ?: 4096
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // a slow network video must not hold up previews of files on the phone
    private val localLock = Mutex()
    private val remoteLock = Mutex()
    private val pending = mutableMapOf<String, Deferred<Info>>()

    fun peek(key: String): Info? = previews.get(key)

    suspend fun load(context: Context, key: String, stream: HubMediaResolver.MediaStream): Info {
        val request = synchronized(pending) {
            previews.get(key)?.let { return it }
            pending.getOrPut(key) {
                // Survives list-item disposal, so scrolling cannot restart extraction.
                scope.async(start = CoroutineStart.LAZY) {
                    val remote = stream.uri.scheme == "http" || stream.uri.scheme == "https"
                    (if (remote) remoteLock else localLock).withLock {
                        val info = extract(context, stream)
                        // Remember failures too, instead of retrying on every scroll.
                        previews.put(key, info)
                        info
                    }
                }.also { task ->
                    task.invokeOnCompletion { synchronized(pending) { pending.remove(key) } }
                }
            }
        }
        request.start()
        return request.await()
    }

    private fun extract(context: Context, stream: HubMediaResolver.MediaStream): Info {
        val retriever = MediaMetadataRetriever()
        return try {
            if (stream.uri.scheme == "http" || stream.uri.scheme == "https") {
                retriever.setDataSource(stream.uri.toString(), stream.headers)
            } else {
                retriever.setDataSource(context, stream.uri)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            val frame = runCatching { frameOf(retriever) }.getOrNull()
            Info(frame, duration, w, h)
        } catch (_: Exception) {
            Info(null, 0, 0, 0)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun frameOf(retriever: MediaMetadataRetriever): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
        } else {
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                val scale = minOf(480f / frame.width, 480f / frame.height, 1f)
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        frame, (frame.width * scale).toInt().coerceAtLeast(1),
                        (frame.height * scale).toInt().coerceAtLeast(1), true
                    ).also { if (it !== frame) frame.recycle() }
                } else frame
            }
        }
}
