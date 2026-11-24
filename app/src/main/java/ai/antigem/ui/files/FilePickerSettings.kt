package ai.antigem.ui.files

import ai.antigem.data.files.AntiGemDocumentsProvider
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.chroot.ChrootManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.i18n.L

/**
 * Settings → Files in other apps: AntiGem's home and the Ubuntu chroot's home in Android's file picker
 * (see [AntiGemDocumentsProvider]).
 */
@Composable
fun FilePickerSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AntiGemDocumentsProvider.isEnabled(context)) }
    var chrootShown by remember { mutableStateOf(AntiGemDocumentsProvider.isChrootShown(context)) }
    remember { runCatching { ChrootManager.init(context) } }
    val installs by ChrootManager.installs.collectAsState()
    val activePath by ChrootManager.activePath.collectAsState()
    val chroot = remember(installs, activePath) { installs.firstOrNull { it.path == activePath } }
    val homeDir = remember { LocalEnvironmentManager.getHomeDir(context) }
    val homeReady = remember { homeDir.isDirectory }

    fun open(rootId: String, documentId: String) {
        if (!AntiGemDocumentsProvider.openInFilesApp(context, rootId, documentId)) {
            Toast.makeText(
                context,
                L.files.filePickerSettings.thisPhoneHasNoFilesApp,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(Icons.Outlined.FolderShared, L.files.filePickerSettings.systemFilePicker) {
            Hint(
                L.files.filePickerSettings.otherAppsCanOpenAndSave
            )
            Spacer(Modifier.height(4.dp))
            SwitchLine(L.files.filePickerSettings.showAntigemInTheFilePicker, null, enabled) {
                enabled = it
                AntiGemDocumentsProvider.setEnabled(context, it)
            }
        }

        Section(Icons.Outlined.Home, L.files.filePickerSettings.antigemHome) {
            Hint(L.files.filePickerSettings.theHomeFolderOfAntigemsOwn)
            Spacer(Modifier.height(6.dp))
            PathText(homeDir.absolutePath)
            if (!homeReady) {
                Spacer(Modifier.height(6.dp))
                Text(L.files.filePickerSettings.notSetUpYetItShows, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { open(AntiGemDocumentsProvider.ROOT_HOME, "${AntiGemDocumentsProvider.ROOT_HOME}:") },
                    enabled = enabled
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(L.files.filePickerSettings.openInFiles)
                }
            }
        }

        if (chroot != null) {
            val home = AntiGemDocumentsProvider.chrootHome(chroot.user.ifBlank { "ubuntu" })
            Section(Icons.Outlined.Terminal, L.common.ubuntuChroot) {
                Hint(
                    "Ubuntu's home folder, read and written through the chroot's IDE bridge as your Ubuntu user. " +
                        "It works while the chroot runs (with \"Keep running\", also when AntiGem is closed); otherwise the " +
                        "picker says that Ubuntu is not running."
                )
                Spacer(Modifier.height(6.dp))
                PathText(home)
                Spacer(Modifier.height(4.dp))
                SwitchLine(L.files.filePickerSettings.showUbuntusHome, L.files.filePickerSettings.asAntigemUbuntuInThePicker, chrootShown && enabled, enabled = enabled) {
                    chrootShown = it
                    AntiGemDocumentsProvider.setChrootShown(context, it)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { open(AntiGemDocumentsProvider.ROOT_CHROOT, "${AntiGemDocumentsProvider.ROOT_CHROOT}:$home") },
                    enabled = enabled && chrootShown
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(L.files.filePickerSettings.openInFiles)
                }
            }
        }

        Section(Icons.Outlined.Info, L.files.filePickerSettings.whereToFindIt) {
            Hint(
                L.files.filePickerSettings.inAnyAppTapOpenAttach
            )
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
}

@Composable
private fun PathText(path: String) {
    Text(path, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
}

@Composable
private fun SwitchLine(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f)
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.65f else 0.35f))
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
