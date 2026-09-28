package com.example.gemini.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.FileProvider
import com.example.gemini.data.remote.HubMediaResolver
import com.example.gemini.domain.model.ArtifactSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object ArtifactDownloadHelper {

    fun downloadArtifact(
        context: Context,
        artifact: ArtifactSnapshot,
        coroutineScope: CoroutineScope,
        snackbarHostState: SnackbarHostState? = null
    ) {
        val targetUri = artifact.absoluteUri.ifBlank { artifact.name }
        if (targetUri.isBlank()) {
            Toast.makeText(context, "No artifact file to download", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            val (savedUri, mimeType, fileName) = withContext(Dispatchers.IO) {
                saveArtifactToDownloads(context, artifact)
            }

            if (savedUri != null) {
                if (snackbarHostState != null) {
                    val result = snackbarHostState.showSnackbar(
                        message = "$fileName saved to Downloads/AntiGem",
                        actionLabel = "Open",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        openFile(context, savedUri, mimeType)
                    }
                } else {
                    Toast.makeText(context, "$fileName saved to Downloads/AntiGem", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Failed to download $fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFile(context: Context, uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open File"))
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open file", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun saveArtifactToDownloads(
        context: Context,
        artifact: ArtifactSnapshot
    ): Triple<Uri?, String, String> = withContext(Dispatchers.IO) {
        try {
            val rawSource = artifact.absoluteUri.ifBlank { artifact.name }
            val bytes = resolveFileBytes(context, rawSource) ?: return@withContext Triple(null, "*/*", artifact.name)
            
            // Determine file name
            val uriFileName = rawSource.removePrefix("file://").substringAfterLast("/")
            var fileName = if (artifact.name.isNotBlank()) {
                val clean = artifact.name.substringAfterLast("/")
                if (clean.contains(".")) clean else if (uriFileName.contains(".")) "$clean.${uriFileName.substringAfterLast(".")}" else clean
            } else {
                uriFileName
            }

            // Determine mime type and extension
            var extension = if (fileName.contains(".")) fileName.substringAfterLast(".").lowercase() else ""
            if (extension.isBlank()) {
                if (isImageBytes(bytes)) {
                    extension = "png"
                    fileName = "$fileName.png"
                } else {
                    extension = "md"
                    fileName = "$fileName.md"
                }
            }

            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: when (extension) {
                    "md", "markdown" -> "text/markdown"
                    "py" -> "text/x-python"
                    "kt" -> "text/x-kotlin"
                    "json" -> "application/json"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    "txt" -> "text/plain"
                    else -> "*/*"
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AntiGem")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues) ?: return@withContext Triple(null, mimeType, fileName)

                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)

                Triple(itemUri, mimeType, fileName)
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
                Triple(fileUri, mimeType, fileName)
            }
        } catch (e: Exception) {
            Triple(null, "*/*", artifact.name)
        }
    }

    private suspend fun resolveFileBytes(context: Context, source: String): ByteArray? {
        // 1. In-memory media cache
        val memKey = HubMediaResolver.normalizeKey(source)
        HubMediaResolver.getImageBytes(memKey)?.let { return it }

        // 2. Base64 data URL
        if (source.startsWith("data:")) {
            val b64 = source.substringAfter("base64,")
            return try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        }

        // 3. Local filesystem
        val clean = source.removePrefix("file://")
        val localFile = File(clean)
        if (localFile.exists() && localFile.canRead() && localFile.length() > 0) {
            return try {
                localFile.readBytes()
            } catch (_: Exception) {
                null
            }
        }

        // 4. Remote HTTP URL
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return try {
                val client = OkHttpClient()
                val req = Request.Builder().url(source).get().build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            } catch (_: Exception) {
                null
            }
        }

        // 5. Hub media resolver
        val resolved = HubMediaResolver.resolveMediaUri(context, source)
        if (resolved.startsWith("data:")) {
            val b64 = resolved.substringAfter("base64,")
            return try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        } else if (resolved.startsWith("file://")) {
            val f = File(resolved.removePrefix("file://"))
            if (f.exists() && f.canRead()) {
                return try {
                    f.readBytes()
                } catch (_: Exception) {
                    null
                }
            }
        }

        return null
    }

    private fun isImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // PNG magic: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        // JPEG magic: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        // GIF magic: 47 49 46 38
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return true
        // WEBP: RIFF....WEBP
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) return true
        return false
    }
}
