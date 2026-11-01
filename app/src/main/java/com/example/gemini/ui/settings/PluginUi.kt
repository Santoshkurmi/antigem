package com.example.gemini.ui.settings

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.example.gemini.ui.browser.PluginVersions
import com.example.gemini.ui.components.MarkdownContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A plugin's icon from a local file path or URL, or a generic plugin icon when there is none or it fails to load. */
@Composable
fun PluginIcon(source: String, size: Dp = 40.dp) {
    val shape = RoundedCornerShape(size / 4)
    val fallback: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size / 2))
        }
    }
    Box(Modifier.size(size).clip(shape)) {
        if (source.isBlank()) fallback() else {
            val context = LocalContext.current
            val request = remember(source) {
                ImageRequest.Builder(context).data(if (source.startsWith("http")) source else File(source)).crossfade(true)
                    .apply { if (source.substringBefore('?').endsWith(".svg", ignoreCase = true)) decoderFactory(SvgDecoder.Factory()) }.build()
            }
            SubcomposeAsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                loading = { fallback() }, error = { fallback() })
        }
    }
}

/** "Installed: v1.0 → This ZIP: v1.1 (newer)", red when the incoming version is older. */
@Composable
fun VersionChange(installed: String, incoming: String, incomingLabel: String = "This ZIP") {
    val hint = when (PluginVersions.compare(incoming, installed)) {
        null -> ""
        0 -> " (same version)"
        in 1..Int.MAX_VALUE -> " (newer)"
        else -> " (older than installed)"
    }
    Text("Installed: ${PluginVersions.label(installed)}  →  $incomingLabel: ${PluginVersions.label(incoming)}$hint", style = MaterialTheme.typography.bodySmall,
        color = if (hint.startsWith(" (older")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The repository setup guide bundled with the plugin SDK docs. */
const val PLUGIN_REPOSITORY_GUIDE = "plugin-repository.md"

fun readBundledGuide(context: Context): String = context.assets.open("extension-docs/$PLUGIN_REPOSITORY_GUIDE").bufferedReader().use { it.readText() }

/** The whole plugin SDK (all bundled docs and examples) as a ZIP, for building plugins on a PC; start with pc-development.md. */
fun bundledSdkZip(context: Context): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(out).use { zip ->
        fun add(asset: String, entry: String) {
            val children = context.assets.list(asset).orEmpty()
            if (children.isEmpty()) {
                zip.putNextEntry(java.util.zip.ZipEntry(entry))
                context.assets.open(asset).use { it.copyTo(zip) }
                zip.closeEntry()
            } else children.sorted().forEach { add("$asset/$it", "$entry/$it") }
        }
        add("extension-docs", "antigem-plugin-sdk")
    }
    return out.toByteArray()
}

/** Saves text to Downloads/AntiGem and toasts where it went. */
suspend fun saveToDownloads(context: Context, fileName: String, text: String, mime: String = "text/markdown") =
    saveToDownloads(context, fileName, text.toByteArray(), mime)

suspend fun saveToDownloads(context: Context, fileName: String, bytes: ByteArray, mime: String) =
    saveToDownloads(context, fileName, mime) { it.write(bytes) }

/** Copies [file] to Downloads/AntiGem without loading it into memory. */
suspend fun saveToDownloads(context: Context, file: File, mime: String) =
    saveToDownloads(context, file.name, mime) { out -> file.inputStream().use { it.copyTo(out) } }

private suspend fun saveToDownloads(context: Context, fileName: String, mime: String, write: (java.io.OutputStream) -> Unit) {
    val saved = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AntiGem")
                }
                val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Cannot create file")
                resolver.openOutputStream(uri)?.use(write) ?: error("Cannot write file")
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AntiGem").apply { mkdirs() }
                File(dir, fileName).outputStream().use(write)
            }
        }
    }
    Toast.makeText(context, if (saved.isSuccess) "$fileName saved to Downloads/AntiGem" else "Could not save $fileName: ${saved.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
}

/** Full-screen view of a Markdown document with a download button. */
@Composable
fun MarkdownViewerDialog(title: String, markdown: String, fileName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    IconButton(onClick = { scope.launch { saveToDownloads(context, fileName, markdown) } }) { Icon(Icons.Outlined.Download, "Download") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { MarkdownContent(content = markdown) }
            }
        }
    }
}
