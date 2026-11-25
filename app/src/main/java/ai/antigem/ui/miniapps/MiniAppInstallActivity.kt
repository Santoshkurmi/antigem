package ai.antigem.ui.miniapps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.antigem.theme.ThemeManager

/**
 * AntiGem's mini app installer, the way Android's package installer is for .apk files: tapping an .agapp in
 * WhatsApp, Telegram, Gmail, Files, a browser's downloads or AntiGem's own file explorer (or sharing one to
 * "Mini app") opens this over the app it came from: install / update screen, then Open / Done.
 */
class MiniAppInstallActivity : ComponentActivity() {
    private var source by mutableStateOf<InstallSource?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = sourceOf(intent)
        if (source == null) { finish(); return }
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        setContent {
            MaterialTheme(colorScheme = ThemeManager.schemeOf(ThemeManager.themeFor(dark))) {
                source?.let { s -> key(s) { PackageInstaller(s) { finish() } } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sourceOf(intent)?.let { source = it }
    }

    private fun sourceOf(i: Intent?): InstallSource? {
        @Suppress("DEPRECATION")
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null || (uri.scheme != "content" && uri.scheme != "file")) return null
        // another app can't point the installer at AntiGem's own private files (file:// or AntiGem's own providers)
        if (!ai.antigem.data.files.IncomingUriGuard.allowed(this, i, uri)) {
            ai.antigem.data.files.IncomingUriGuard.toastRefused(this)
            return null
        }
        return InstallSource.FromUri(uri)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
