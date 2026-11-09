package com.example.gemini.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The copy of an attached image that the model sees. Every agent shrinks images to about 2000 px itself, so a bigger
 * image only costs memory, transfer time and history size. Small PNG, JPEG, GIF and WebP images are kept as they are;
 * anything else (big photos, HEIC, BMP, …) is decoded at reduced size, so a 50 MP photo is never held in memory at full
 * size, and saved as a JPEG of at most [MAX_SIDE] px. Returns null when Android cannot decode the file (e.g. TIFF).
 */
object ImageShrinker {
    const val MAX_SIDE = 2048
    private const val KEEP_BYTES = 3_000_000L
    private const val JPEG_QUALITY = 85
    private val KEEP_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    class Shrunk(val bytes: ByteArray, val mimeType: String)

    private class Input(val open: () -> InputStream?, val context: Context? = null, val uri: Uri? = null, val file: File? = null)

    fun fromUri(context: Context, uri: Uri, mimeType: String, size: Long): Shrunk? =
        shrink(Input({ context.contentResolver.openInputStream(uri) }, context = context, uri = uri), mimeType, size)

    fun fromFile(file: File, mimeType: String): Shrunk? =
        shrink(Input({ file.inputStream() }, file = file), mimeType, file.length())

    private fun shrink(input: Input, mimeType: String, size: Long): Shrunk? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { input.open()?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        val w = bounds.outWidth
        val h = bounds.outHeight
        val type = mimeType.lowercase()
        if (type in KEEP_TYPES && size in 1..KEEP_BYTES && w in 1..MAX_SIDE && h in 1..MAX_SIDE) {
            val bytes = input.open()?.use { it.readBytes() } ?: return null
            return Shrunk(bytes, type)
        }
        val bitmap = (if (Build.VERSION.SDK_INT >= 28) decodeScaled(input) else decodeSampled(input, w, h)) ?: return null
        return try {
            Shrunk(toJpeg(bitmap), "image/jpeg")
        } finally {
            bitmap.recycle()
        }
    }

    /** Android 9+: decodes straight to the target size (also HEIC), turned the right way up. */
    @RequiresApi(28)
    private fun decodeScaled(input: Input): Bitmap? {
        val source = when {
            input.uri != null && input.context != null -> ImageDecoder.createSource(input.context.contentResolver, input.uri)
            input.file != null -> ImageDecoder.createSource(input.file)
            else -> return null
        }
        return runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, MAX_SIDE.toFloat() / max(w, h))
                if (scale < 1f) decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    }

    /** Older Android: decodes every 2nd/4th/… pixel first, then scales the rest of the way. */
    private fun decodeSampled(input: Input, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (max(w, h) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching { input.open()?.use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull() ?: return null
        val big = max(decoded.width, decoded.height)
        if (big <= MAX_SIDE) return decoded
        val scale = MAX_SIDE.toFloat() / big
        val scaled = Bitmap.createScaledBitmap(decoded, max(1, (decoded.width * scale).roundToInt()), max(1, (decoded.height * scale).roundToInt()), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** JPEG has no transparency: transparent parts become white instead of black. */
    private fun toJpeg(bitmap: Bitmap): ByteArray {
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
            }
        } else bitmap
        return try {
            ByteArrayOutputStream().use { out ->
                opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            if (opaque !== bitmap) opaque.recycle()
        }
    }

    /** The name the model gets: "IMG_1.heic" sent as JPEG is "IMG_1.jpg". */
    fun nameFor(original: String, shrunk: Shrunk): String {
        if (shrunk.mimeType != "image/jpeg") return original
        val ext = original.substringAfterLast('.', "").lowercase()
        if (ext == "jpg" || ext == "jpeg") return original
        val stem = if (original.contains('.')) original.substringBeforeLast('.') else original
        return "$stem.jpg"
    }
}
