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
                "This phone has no Files app that shows other apps' folders. Use the picker in any app (Open, Attach, Save as).",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(Icons.Outlined.FolderShared, "System file picker") {
            Hint(
                "Other apps can open and save files in AntiGem's folders through Android's file picker (Open, Attach, " +
                    "Save as, Choose folder) and the Files app. An app only gets the files or the folder you pick."
            )
            Spacer(Modifier.height(4.dp))
            SwitchLine("Show AntiGem in the file picker", null, enabled) {
                enabled = it
                AntiGemDocumentsProvider.setEnabled(context, it)
            }
        }

        Section(Icons.Outlined.Home, "AntiGem home") {
            Hint("The home folder of AntiGem's own environment: projects, configs and everything the agents keep there.")
            Spacer(Modifier.height(6.dp))
            PathText(homeDir.absolutePath)
            if (!homeReady) {
                Spacer(Modifier.height(6.dp))
                Text("Not set up yet: it shows once AntiGem's environment is installed.", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
            } else {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { open(AntiGemDocumentsProvider.ROOT_HOME, "${AntiGemDocumentsProvider.ROOT_HOME}:") },
                    enabled = enabled
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open in Files")
                }
            }
        }

        if (chroot != null) {
            val home = AntiGemDocumentsProvider.chrootHome(chroot.user.ifBlank { "ubuntu" })
            Section(Icons.Outlined.Terminal, "Ubuntu chroot") {
                Hint(
                    "Ubuntu's home folder, read and written through the chroot's IDE bridge as your Ubuntu user. " +
                        "It works while the chroot runs (with \"Keep running\", also when AntiGem is closed); otherwise the " +
                        "picker says that Ubuntu is not running."
                )
                Spacer(Modifier.height(6.dp))
                PathText(home)
                Spacer(Modifier.height(4.dp))
                SwitchLine("Show Ubuntu's home", "As \"AntiGem Ubuntu\" in the picker", chrootShown && enabled, enabled = enabled) {
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
                    Text("Open in Files")
                }
            }
        }

        Section(Icons.Outlined.Info, "Where to find it") {
            Hint(
                "In any app, tap Open, Attach or Save as, then open the ☰ menu of the picker: AntiGem is in the list. " +
                    "Some phone makers' file managers (like Samsung My Files) only show their own places; Android's picker " +
                    "always lists AntiGem."
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
