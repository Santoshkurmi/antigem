package com.example.gemini.ui.miniapps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.example.gemini.data.miniapps.MiniApp
import com.example.gemini.data.miniapps.MiniAppIntents
import com.example.gemini.data.miniapps.MiniAppRuntime
import com.example.gemini.data.miniapps.MiniApps
import com.example.gemini.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * "Mini app" in Android's share sheet: pick which mini app gets what was shared. Files are copied into that app's
 * own folder (data/inbox/…), text and links go along as they are; the app gets it as an `intent` event.
 */
class MiniAppShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.type ?: "*/*"
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        @Suppress("DEPRECATION")
        val stream: Uri? = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        // a mini app package (by type, name or contents): that's an install, not something for an app
        if (stream != null && intent.action == Intent.ACTION_SEND) {
            lifecycleScope.launch {
                if (isPackage(stream, type)) {
                    startActivity(Intent(this@MiniAppShareActivity, MiniAppInstallActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .setDataAndType(stream, com.example.gemini.data.miniapps.MiniAppSigning.MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                } else showTargets(type, text, subject, stream)
            }
            return
        }
        showTargets(type, text, subject, stream)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.path?.substringAfterLast('/')?.takeIf { it.contains('.') }

    private suspend fun isPackage(uri: Uri, type: String): Boolean {
        if (type == com.example.gemini.data.miniapps.MiniAppSigning.MIME) return true
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        if (name != null) return name.endsWith(".agapp", ignoreCase = true)
        // no name (some chat apps): look inside when the type says "some file"
        if (type != "application/octet-stream" && type != "application/zip" && type != "*/*") return false
        return withContext(Dispatchers.IO) {
            val tmp = File(cacheDir, "share-sniff-${System.nanoTime()}")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var total = 0L
                        while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > 100L * 1024 * 1024) return@withContext false; out.write(buf, 0, n) }
                    }
                }
                com.example.gemini.data.miniapps.MiniAppSigning.looksLikePackage(tmp)
            } catch (_: Exception) { false } finally { tmp.delete() }
        }
    }

    private fun showTargets(type: String, text: String?, subject: String?, stream: Uri?) {
        val targets = MiniAppIntents.targets(this, "share", type)
        if (targets.isEmpty()) {
            Toast.makeText(this, "No mini app accepts $type", Toast.LENGTH_LONG).show(); finish(); return
        }
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        setContent {
            MaterialTheme(colorScheme = ThemeManager.schemeOf(ThemeManager.themeFor(dark))) {
                ActionSheet(
                    title = "Send to a mini app",
                    items = targets.map { NativeDialog.SheetItem(it.id, it.name, if (it.signed) "▣" else "◇", false) },
                    onDismiss = { finish() },
                ) { id -> deliver(targets.first { it.id == id }, type, text, subject, stream) }
            }
        }
    }

    private fun deliver(app: MiniApp, type: String, text: String?, subject: String?, stream: Uri?) = lifecycleScope.launch {
        val intent = JSONObject().put("action", "share").put("type", type).put("from", JSONObject().put("id", "android").put("name", "Android"))
        text?.let { intent.put("text", it) }
        subject?.let { intent.put("title", it) }
        val copied = stream?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    var name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?: uri.lastPathSegment ?: "shared"
                    name = name.replace(Regex("[/\\\\]"), "_")
                    val dir = File(MiniApps.filesDir(this@MiniAppShareActivity, app.id), "inbox").also { it.mkdirs() }
                    var f = File(dir, name); var n = 1
                    while (f.exists()) f = File(dir, "${n++}-$name")
                    contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                    "data/inbox/${f.name}"
                }.getOrNull()
            }
        }
        copied?.let { intent.put("path", it) }
        MiniAppRuntime.deliverIntent(app.id, intent)
        MiniAppActivity.launch(this@MiniAppShareActivity, app.id)
        finish()
    }
}
