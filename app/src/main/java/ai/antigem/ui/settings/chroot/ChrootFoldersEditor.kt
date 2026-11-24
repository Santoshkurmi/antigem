package ai.antigem.ui.settings.chroot

import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.chroot.ChrootBind
import ai.antigem.data.local.chroot.ChrootManager
import ai.antigem.theme.AppColors
import ai.antigem.theme.ClaudeTerracotta
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.i18n.L

/**
 * The folders of the phone shared into the chroot: as many as the user likes, each with the path it gets inside
 * Ubuntu and optionally read-only. Used by the setup page and by the chroot's settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChrootFoldersEditor(user: String, binds: List<ChrootBind>, onChange: (List<ChrootBind>) -> Unit) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var targetEdited by remember { mutableStateOf(false) }
    var readOnly by remember { mutableStateOf(false) }
    var pickError by remember { mutableStateOf<String?>(null) }

    fun choose(path: String, inside: String? = null, readOnlyDefault: Boolean? = null) {
        source = path
        pickError = null
        if (inside != null || !targetEdited) {
            target = inside ?: suggestTarget(path, user, binds)
            targetEdited = inside != null
        }
        if (readOnlyDefault != null) readOnly = readOnlyDefault
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = treeUriToPath(uri)
        if (path != null) choose(path)
        else pickError = L.settings.chrootFoldersEditor.thatFolderIsNotOnThe
    }

    Text(L.settings.chrootFoldersEditor.sharedFolders, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
    ChrootNote(L.settings.chrootFoldersEditor.foldersOfThePhoneYoursOr)
    Spacer(Modifier.height(4.dp))
    if (binds.isEmpty()) Text(L.settings.chrootFoldersEditor.noneYet, fontSize = 12.sp, color = AppColors.textSecondary)
    binds.forEach { b ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.target, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
                    if (b.readOnly) { Spacer(Modifier.width(6.dp)); ChrootTag("read-only", AppColors.info) }
                }
                Text("← ${b.source}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = AppColors.textSecondary)
            }
            TextButton(onClick = { onChange(binds - b) }) { Text(L.common.remove, color = AppColors.danger, fontSize = 12.sp) }
        }
    }

    if (!adding) {
        OutlinedButton(
            onClick = { adding = true; source = ""; target = ""; targetEdited = false; readOnly = false; pickError = null },
            shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 6.dp)
        ) {
            Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(L.settings.chrootFoldersEditor.addAFolder)
        }
        return
    }

    val home = LocalEnvironmentManager.getHomeDir(context).absolutePath
    Surface(shape = RoundedCornerShape(12.dp), color = AppColors.surface, border = BorderStroke(1.dp, AppColors.border.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(L.settings.chrootFoldersEditor.n1TheFolderOnThePhone, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { choose(home, "/home/$user/antigem_home") }, label = { Text(L.settings.chrootFoldersEditor.antigemHome) })
                AssistChip(onClick = { choose("/storage/emulated/0/Download") }, label = { Text(L.common.download) })
                AssistChip(onClick = { choose("/storage/emulated/0/Documents") }, label = { Text(L.settings.chrootFoldersEditor.documents) })
                AssistChip(onClick = { choose("/storage/emulated/0/DCIM") }, label = { Text(L.settings.chrootFoldersEditor.dcim) })
                AssistChip(onClick = { choose("/storage/emulated/0/Pictures") }, label = { Text(L.settings.chrootFoldersEditor.pictures) })
                AssistChip(onClick = { picker.launch(null) }, label = { Text(L.settings.chrootFoldersEditor.browse) },
                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp)) })
            }
            // Android's own folders, at the same place inside Ubuntu (the system ones read-only)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.settings.chrootFoldersEditor.android, fontSize = 11.5.sp, color = AppColors.textSecondary, modifier = Modifier.padding(end = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("/system" to true, "/vendor" to true, "/apex" to true, "/product" to true, "/data/local/tmp" to false).forEach { (p, ro) ->
                        AssistChip(onClick = { choose(p, readOnlyDefault = ro) }, label = { Text(p, fontFamily = FontFamily.Monospace, fontSize = 12.sp) })
                    }
                }
            }
            OutlinedTextField(source, { choose(it) }, label = { Text(L.settings.chrootFoldersEditor.phoneFolderAnyPath) }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            pickError?.let { ChrootNote(it, AppColors.warning) }
            Spacer(Modifier.height(8.dp))
            Text(L.settings.chrootFoldersEditor.n2WhereItShowsUpInside, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onBackground)
            OutlinedTextField(target, { target = it.trim(); targetEdited = true }, label = { Text(L.settings.chrootFoldersEditor.pathInsideUbuntu) }, singleLine = true,
                placeholder = { Text("/home/$user/photos or /mnt/photos") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(readOnly, { readOnly = it }, colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta))
                Text(L.settings.chrootFoldersEditor.readOnlyUbuntuCanReadBut, fontSize = 12.sp, color = AppColors.onBackground)
            }
            val src = source.trim().trimEnd('/')
            val dst = target.trim().trimEnd('/')
            val problem = when {
                src.isEmpty() -> L.settings.chrootFoldersEditor.pickOrTypeThePhoneFolder
                !ChrootManager.isBindSource(src) -> L.settings.chrootFoldersEditor.useTheFoldersFullPathStarting
                dst.isEmpty() -> L.settings.chrootFoldersEditor.typeWhereItShowsUpInside
                else -> ChrootManager.bindTargetProblem(dst, user)
                    ?: if (binds.any { it.target.trimEnd('/') == dst }) L.settings.chrootFoldersEditor.somethingIsAlreadySharedAtDst(dst) else null
            }
            if (problem != null) ChrootNote(problem, AppColors.warning)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    onClick = { onChange(binds + ChrootBind(src, dst, readOnly)); adding = false },
                    enabled = problem == null,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta), shape = RoundedCornerShape(10.dp)
                ) { Text(L.common.add) }
                TextButton(onClick = { adding = false }) { Text(L.common.cancel) }
            }
        }
    }
}

/** Top folders of Android's own (not Ubuntu's): shared at the same place inside Ubuntu. */
private val ANDROID_TOPS = setOf("system", "vendor", "apex", "product", "odm", "system_ext", "data", "linkerconfig", "metadata",
    "oem", "efs", "persist", "cache", "acct", "config", "debug_ramdisk", "odm_dlkm", "vendor_dlkm", "system_dlkm")

/** Android's own folders at the same place, everything else at ~/<folder name>; unique among the shared folders. */
private fun suggestTarget(path: String, user: String, binds: List<ChrootBind>): String {
    val clean = path.trimEnd('/')
    val top = clean.removePrefix("/").substringBefore('/')
    if (top in ANDROID_TOPS && ChrootManager.isPlainPath(clean) && ChrootManager.bindTargetProblem(clean, user) == null &&
        binds.none { it.target == clean }) return clean
    val base = clean.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').ifBlank { "folder" }
    var candidate = "/home/$user/$base"
    var n = 2
    while (binds.any { it.target == candidate }) candidate = "/home/$user/$base-${n++}"
    return candidate
}

/** The file path of a folder picked with the system picker: the phone's storage and SD cards (null: another provider). */
private fun treeUriToPath(uri: Uri): String? {
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    return when (uri.authority) {
        "com.android.externalstorage.documents" -> {
            val volume = id.substringBefore(':')
            val rel = id.substringAfter(':', "").trim('/')
            val root = when (volume) {
                "primary" -> "/storage/emulated/0"
                "home" -> "/storage/emulated/0/Documents"
                else -> "/storage/$volume"
            }
            if (rel.isEmpty()) root else "$root/$rel"
        }
        "com.android.providers.downloads.documents" -> when {
            id.startsWith("raw:") -> id.removePrefix("raw:")
            id == "downloads" -> "/storage/emulated/0/Download"
            else -> null
        }
        else -> null
    }
}
