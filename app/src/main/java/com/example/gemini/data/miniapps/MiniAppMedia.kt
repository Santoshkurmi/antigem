package com.example.gemini.data.miniapps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import java.io.File

/**
 * Native image work for mini apps: thumbnails of photos and video frames (fast grids instead of full-size images),
 * and HEIC/HEIF photos turned into JPEG (the WebView can't decode them). Results are cached in the app's data folder
 * (thumbs/), keyed by file, size and modification time.
 */
object MiniAppMedia {
    private const val FULL_MAX = 4096

    /** A JPEG of [f]: a thumbnail of at most [size] px, or (null) the whole image, capped at 4096 px. */
    fun jpeg(context: Context, app: MiniApp, f: File, size: Int?): File {
        val dir = File(MiniApps.dataDir(context, app.id), "thumbs").also { it.mkdirs() }
        val key = Integer.toHexString("${f.path}|${f.length()}|${f.lastModified()}|${size ?: 0}".hashCode())
        val out = File(dir, "$key.jpg")
        if (out.isFile && out.length() > 0) return out
        val bmp = decode(f, size) ?: error("Can't decode ${f.name}")
        val tmp = File(dir, "$key.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, if (size != null) 82 else 90, it) }
        bmp.recycle()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        return out
    }

    private fun isVideo(f: File) = MiniAppNet.guessMime(f.name).startsWith("video/")

    private fun decode(f: File, size: Int?): Bitmap? {
        if (isVideo(f)) return videoFrame(f, size ?: 1024)
        // ImageDecoder (Android 9+): HEIF, EXIF rotation, decoding at the target size directly
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            if (size != null && Build.VERSION.SDK_INT >= 29) return ThumbnailUtils.createImageThumbnail(f, Size(size, size), null)
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                val max = size ?: FULL_MAX
                val w = info.size.width; val h = info.size.height
                if (w > max || h > max) { val s = max.toFloat() / maxOf(w, h); decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1)) }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        val max = size ?: FULL_MAX
        while (o.outWidth / (sample * 2) >= max || o.outHeight / (sample * 2) >= max) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun videoFrame(f: File, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) runCatching { return ThumbnailUtils.createVideoThumbnail(f, Size(size, size), null) }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                val s = size.toFloat() / maxOf(b.width, b.height)
                if (s < 1) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
            }
        } finally { runCatching { r.release() } }
    }
}
