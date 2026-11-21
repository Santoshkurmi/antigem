package ai.antigem.ui.settings.chroot

import ai.antigem.data.updater.AppUpdateManager
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private sealed class ChrootBuildStep {
    object Idle : ChrootBuildStep()
    object Looking : ChrootBuildStep()
    data class Downloading(val progress: Float, val done: Long, val total: Long) : ChrootBuildStep()
    /** Downloaded; [opened] is false when Android first asked to allow installing apps from AntiGem. */
    data class Downloaded(val file: File, val opened: Boolean) : ChrootBuildStep()
    data class Failed(val message: String) : ChrootBuildStep()
}

private const val RELEASES_PAGE = "https://github.com/Santoshkurmi/antigem/releases/latest"

/**
 * The normal build has no Linux bridge, which the Ubuntu chroot runs: instead of the chroot setup, this page downloads
 * the chroot build (AntiGem-v<version>-chroot.apk) and opens Android's installer for it. Same app and signing key, so
 * it installs over this one and keeps everything; its updates are chroot builds too.
 */
@Composable
fun ChrootBuildScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { AppUpdateManager(context) }
    var step by remember { mutableStateOf<ChrootBuildStep>(ChrootBuildStep.Idle) }
    val busy = step is ChrootBuildStep.Looking || step is ChrootBuildStep.Downloading

    fun install(file: File) {
        step = ChrootBuildStep.Downloaded(file, opened = manager.installApk(file))
    }

    fun start() {
        if (busy) return
        scope.launch {
            step = ChrootBuildStep.Looking
            val url = manager.findChrootBuild().getOrElse {
                step = ChrootBuildStep.Failed(it.message ?: "Could not reach GitHub.")
                return@launch
            }
            step = ChrootBuildStep.Downloading(0f, 0L, 0L)
            val file = manager.downloadApk(url) { progress, done, total ->
                step = ChrootBuildStep.Downloading(progress, done, total)
            }.getOrElse {
                step = ChrootBuildStep.Failed("The download failed: ${it.message ?: "network error"}")
                return@launch
            }
            install(file)
        }
    }

    // a download in progress is not left by Back (it would stop it)
    BackHandler { if (!busy) onClose() }

    Column(Modifier.fillMaxSize()) {
        // header, like the chroot setup page
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!busy) onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AppColors.onBackground) }
            Column(Modifier.weight(1f)) {
                Text("Ubuntu chroot (root)", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.onBackground)
                Text("Needs the chroot build of AntiGem", fontSize = 11.5.sp, color = AppColors.textSecondary)
            }
        }
        HorizontalDivider(color = AppColors.border.copy(alpha = 0.5f))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = chrootColumnSpacing
        ) {
            ChrootSection("The chroot build", "This is the normal build of AntiGem", Icons.Outlined.Storage, AppColors.success) {
                Text(
                    "On a rooted phone, agents can run in a real Ubuntu (apt, glibc, sudo) through a chroot. It uses a Linux " +
                        "build of AntiGem's bridge, which only the chroot build carries (about 4 MB more), so other phones " +
                        "don't download it.",
                    fontSize = 12.5.sp, lineHeight = 17.sp, color = AppColors.onBackground
                )
                Spacer(Modifier.height(8.dp))
                ChrootNote("• The same app with the same signature: it installs over this one and keeps your chats, settings and environment.")
                ChrootNote("• Its updates are chroot builds too.")
                ChrootNote("• Setting up the chroot afterwards needs root (Magisk, KernelSU or APatch).")
            }

            when (val s = step) {
                ChrootBuildStep.Idle -> Unit
                ChrootBuildStep.Looking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ClaudeTerracotta)
                    Spacer(Modifier.width(10.dp))
                    Text("Looking for the chroot build on GitHub…", fontSize = 12.5.sp, color = AppColors.onBackground)
                }
                is ChrootBuildStep.Downloading -> Column {
                    Text(
                        if (s.total > 0) "Downloading the chroot build: ${mb(s.done)} of ${mb(s.total)}" else "Downloading the chroot build: ${mb(s.done)}",
                        fontSize = 12.5.sp, color = AppColors.onBackground
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = ClaudeTerracotta
                    )
                }
                is ChrootBuildStep.Downloaded -> ChrootNote(
                    if (s.opened) "Downloaded. Confirm the update in Android's installer: AntiGem restarts as the chroot build."
                    else "Downloaded. Allow AntiGem to install apps (Android opened that setting), then come back and tap Install.",
                    if (s.opened) AppColors.success else AppColors.warning
                )
                is ChrootBuildStep.Failed -> ChrootNote(s.message, AppColors.danger)
            }
        }

        // actions
        HorizontalDivider(color = AppColors.border.copy(alpha = 0.5f))
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val s = step
            Button(
                onClick = { if (s is ChrootBuildStep.Downloaded) install(s.file) else start() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
            ) {
                Text(
                    when (s) {
                        is ChrootBuildStep.Downloaded -> "Install"
                        is ChrootBuildStep.Failed -> "Try again"
                        else -> "Download and install the chroot build"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)
            ) { Text("Open the releases page (AntiGem-v…-chroot.apk)") }
        }
    }
}

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
