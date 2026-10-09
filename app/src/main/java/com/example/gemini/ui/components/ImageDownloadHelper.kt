package com.example.gemini.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.FileProvider
import com.example.gemini.data.remote.HubMediaResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

object ImageDownloadHelper {

    fun downloadImage(
        context: Context,
        imageSource: String,
        title: String = "",
        coroutineScope: CoroutineScope,
        snackbarHostState: SnackbarHostState? = null
    ) {
        if (imageSource.isBlank()) {
            Toast.makeText(context, "No image to download", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            val (savedUri, mimeType) = withContext(Dispatchers.IO) {
                saveImageToDownloads(context, imageSource, title)
            }

            if (savedUri != null) {
                if (snackbarHostState != null) {
                    val result = snackbarHostState.showSnackbar(
                        message = "Image saved to Downloads/AntiGem",
                        actionLabel = "Open",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        openImage(context, savedUri, mimeType)
                    }
                } else {
                    Toast.makeText(context, "Image saved to Downloads/AntiGem", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Saves a video (local path, file://, content:// or http(s) URL) to Downloads/AntiGem, streaming it to disk.
     */
    fun downloadVideo(context: Context, videoSource: String, title: String, coroutineScope: CoroutineScope) {
        if (videoSource.isBlank()) {
            Toast.makeText(context, "No video to download", Toast.LENGTH_SHORT).show()
            return
        }
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) { saveVideoToDownloads(context, videoSource, title) }
            if (saved != null) {
                Toast.makeText(context, "Video saved to Downloads/AntiGem", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to save video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveVideoToDownloads(context: Context, source: String, title: String): Uri? = try {
        val clean = source.trim().removePrefix("file://")
        val extension = clean.substringBefore('?').substringAfterLast('.', "mp4").lowercase().takeIf { it.length in 2..4 } ?: "mp4"
        val mimeType = when (extension) {
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            else -> "video/mp4"
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = title.replace(Regex("[^A-Za-z0-9_-]"), "_").take(30).trimEnd('_')
        val fileName = if (safeTitle.isNotBlank()) "VID_${safeTitle}_$timestamp.$extension" else "VID_$timestamp.$extension"

        val input: java.io.InputStream? = when {
            source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))
            source.startsWith("http://") || source.startsWith("https://") -> {
                val resp = OkHttpClient().newCall(Request.Builder().url(source).get().build()).execute()
                if (resp.isSuccessful) resp.body?.byteStream() else { resp.close(); null }
            }
            else -> File(clean).takeIf { it.canRead() && it.length() > 0 }?.inputStream()
        }
        input?.use { stream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AntiGem")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val itemUri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                itemUri?.also { uri ->
                    resolver.openOutputStream(uri)?.use { out -> stream.copyTo(out) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AntiGem").apply { mkdirs() }
                val target = File(dir, fileName)
                target.outputStream().use { out -> stream.copyTo(out) }
                Uri.fromFile(target)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun openImage(context: Context, uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "image/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open Image"))
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open image", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun saveImageToDownloads(
        context: Context,
        imageSource: String,
        title: String
    ): Pair<Uri?, String> = withContext(Dispatchers.IO) {
        try {
            val bytes = resolveImageBytes(context, imageSource) ?: return@withContext Pair(null, "image/png")
            val mimeType = detectMimeType(imageSource, bytes)
            val extension = if (mimeType.contains("jpeg") || mimeType.contains("jpg")) ".jpg" else if (mimeType.contains("webp")) ".webp" else ".png"

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeTitle = title.trim()
                .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                .take(30)
                .trimEnd('_')
            val fileName = if (safeTitle.isNotBlank()) "IMG_${safeTitle}_$timestamp$extension" else "IMG_$timestamp$extension"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AntiGem")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues) ?: return@withContext Pair(null, mimeType)

                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)

                Pair(itemUri, mimeType)
            } else {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AntiGem")
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, fileName)
                targetFile.writeBytes(bytes)

                val fileUri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                } catch (_: Exception) {
                    Uri.fromFile(targetFile)
                }
                Pair(fileUri, mimeType)
            }
        } catch (e: Exception) {
            Pair(null, "image/png")
        }
    }

    private suspend fun resolveImageBytes(context: Context, source: String): ByteArray? {
        val memKey = HubMediaResolver.normalizeKey(source)
        HubMediaResolver.getImageBytes(memKey)?.let { return it }

        if (source.startsWith("data:image/")) {
            val b64 = source.substringAfter("base64,")
            return try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        }

        val clean = source.removePrefix("file://")
        val localFile = File(clean)
        if (localFile.exists() && localFile.canRead() && localFile.length() > 0) {
            return try {
                localFile.readBytes()
            } catch (_: Exception) {
                null
            }
        }

        if (source.startsWith("http://") || source.startsWith("https://")) {
            return try {
                val client = OkHttpClient()
                val req = Request.Builder()
                    .url(source)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 AntiGem/1.0")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            } catch (_: Exception) {
                null
            }
        }

        val resolved = HubMediaResolver.resolveMediaUri(context, source)
        if (resolved.startsWith("data:image/")) {
            val b64 = resolved.substringAfter("base64,")
            return try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    private fun detectMimeType(source: String, bytes: ByteArray): String {
        if (source.startsWith("data:image/jpeg") || source.endsWith(".jpg", ignoreCase = true) || source.endsWith(".jpeg", ignoreCase = true)) {
            return "image/jpeg"
        }
        if (source.startsWith("data:image/webp") || source.endsWith(".webp", ignoreCase = true)) {
            return "image/webp"
        }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return "image/png"
        }
        return "image/png"
    }
}

