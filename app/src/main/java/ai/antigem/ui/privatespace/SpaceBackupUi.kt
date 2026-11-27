package ai.antigem.ui.privatespace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.privatespace.OpenedBackup
import ai.antigem.data.privatespace.PrivateSpace
import ai.antigem.data.privatespace.SpaceBackupFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.antigem.i18n.L

/**
 * Making a backup: the master password first (so the user knows what opens it), then where to save the file.
 * The file opens only with the master password or the recovery key, on any phone; never with the PIN or fingerprint.
 */
@Composable
internal fun ExportBackupFlow(onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        PrivateSpace.endExternalPick()
        if (uri == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val chars = password.toCharArray()
            val ok = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { PrivateSpace.exportBackup(chars, it) } ?: false
                }
            } catch (e: Exception) {
                false
            } finally {
                chars.fill('\u0000')
            }
            password = ""
            busy = false
            onMessage(if (ok) L.privateSpace.backup.saved else L.privateSpace.backup.saveFailed)
            onDismiss()
        }
    }

    fun next() {
        if (busy || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val chars = password.toCharArray()
            val ok = PrivateSpace.verifyPassword(chars)
            chars.fill('\u0000')
            if (!ok) {
                error = L.privateSpace.lock.wrongPassword
                busy = false
            } else {
                PrivateSpace.beginExternalPick()
                createFile.launch(PrivateSpace.suggestedBackupName())
            }
        }
    }

    SpaceAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Outlined.Backup, null) },
        title = { Text(L.privateSpace.backup.createTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L.privateSpace.backup.createText, fontSize = 14.sp)
                SecretField(password, { password = it }, L.privateSpace.setup.masterPassword, autoFocus = true, onImeAction = { next() }, isError = error != null, supportingText = error)
            }
        },
        confirmButton = {
            Button(onClick = { next() }, enabled = !busy && password.isNotEmpty()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(L.privateSpace.backup.chooseWhere)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(L.common.cancel) } }
    )
}

/**
 * Restoring a backup file. [adopt] (no space on this phone yet): the backup becomes the space, with its own master
 * password and recovery key. Otherwise its items are merged into this space by id, or replace this space's items.
 */
@Composable
internal fun RestoreBackupFlow(adopt: Boolean, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<SpaceBackupFile?>(null) }
    var opened by remember { mutableStateOf<OpenedBackup?>(null) }
    var openedWithRecovery by remember { mutableStateOf(false) }
    var notABackup by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        PrivateSpace.endExternalPick()
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val f = try {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { PrivateSpace.readBackup(it) } }
            } catch (e: Exception) {
                null
            }
            if (f == null) notABackup = true else file = f
        }
    }
    LaunchedEffect(Unit) {
        PrivateSpace.beginExternalPick()
        pick.launch(arrayOf("*/*"))
    }

    val f = file
    val o = opened
    when {
        notABackup -> SpaceAlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.ErrorOutline, null) },
            title = { Text(L.privateSpace.backup.notABackupTitle) },
            text = { Text(L.privateSpace.backup.notABackupText) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(L.common.ok) } }
        )
        f != null && o == null -> OpenBackupDialog(
            file = f,
            onDismiss = onDismiss,
            onOpened = { backup, withRecovery ->
                if (adopt) {
                    PrivateSpace.adoptBackup(backup, withRecovery)
                    onMessage(L.privateSpace.backup.restored)
                    onDismiss()
                } else {
                    openedWithRecovery = withRecovery
                    opened = backup
                }
            }
        )
        o != null && !confirmReplace -> SpaceAlertDialog(
            onDismissRequest = {
                PrivateSpace.discardBackup(o)
                onDismiss()
            },
            icon = { Icon(Icons.Outlined.SettingsBackupRestore, null) },
            title = { Text(L.privateSpace.backup.openedTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(L.privateSpace.backup.openedCount(o.activeCount), fontSize = 14.sp)
                    Text(
                        if (o.isThisSpace) L.privateSpace.backup.fromThisSpace else L.privateSpace.backup.fromOtherSpace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    ChoiceRow(
                        icon = Icons.AutoMirrored.Outlined.MergeType,
                        title = L.privateSpace.backup.merge,
                        subtitle = L.privateSpace.backup.mergeHint,
                        onClick = {
                            val r = PrivateSpace.mergeBackup(o)
                            onMessage(L.privateSpace.backup.mergeResult(added = r.added, updated = r.updated, unchanged = r.unchanged))
                            onDismiss()
                        }
                    )
                    ChoiceRow(
                        icon = Icons.Outlined.SwapHoriz,
                        title = L.privateSpace.backup.replace,
                        subtitle = L.privateSpace.backup.replaceHint,
                        danger = true,
                        onClick = { confirmReplace = true }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    PrivateSpace.discardBackup(o)
                    onDismiss()
                }) { Text(L.common.cancel) }
            }
        )
        o != null && confirmReplace -> TypedConfirmDialog(
            title = L.privateSpace.backup.replaceConfirmTitle,
            text = L.privateSpace.backup.replaceConfirmText,
            word = L.privateSpace.backup.replaceWord,
            confirmLabel = L.privateSpace.backup.replace,
            onDismiss = { confirmReplace = false },
            onConfirm = {
                PrivateSpace.replaceWithBackup(o)
                onMessage(L.privateSpace.backup.replaced)
                onDismiss()
            }
        )
    }
}

@Composable
private fun ChoiceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, color = color, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
private fun OpenBackupDialog(file: SpaceBackupFile, onDismiss: () -> Unit, onOpened: (OpenedBackup, Boolean) -> Unit) {
    val context = LocalContext.current
    var useRecovery by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun open() {
        if (busy || secret.isBlank()) return
        busy = true
        error = null
        scope.launch {
            val chars = secret.toCharArray()
            val backup = PrivateSpace.openBackup(file, chars, useRecovery)
            chars.fill('\u0000')
            busy = false
            if (backup == null) {
                error = if (useRecovery) L.privateSpace.lock.wrongRecovery else L.privateSpace.backup.wrongPassword
            } else {
                secret = ""
                onOpened(backup, useRecovery)
            }
        }
    }

    SpaceAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Outlined.Lock, null) },
        title = { Text(L.privateSpace.backup.openTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(L.privateSpace.backup.openText(dateTime(context, file.exportedAt)), fontSize = 14.sp)
                if (useRecovery) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it.uppercase() },
                        label = { Text(L.privateSpace.lock.recoveryKey) },
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { open() }),
                        modifier = Modifier.fillMaxWidth().noAutofill()
                    )
                } else {
                    SecretField(secret, { secret = it }, L.privateSpace.backup.itsMasterPassword, autoFocus = true, onImeAction = { open() }, isError = error != null, supportingText = error)
                }
                if (file.hasRecoveryKey) {
                    TextButton(onClick = {
                        useRecovery = !useRecovery
                        secret = ""
                        error = null
                    }) {
                        Text(if (useRecovery) L.privateSpace.backup.usePasswordInstead else L.privateSpace.backup.useRecoveryInstead)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { open() }, enabled = !busy && secret.isNotBlank()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(L.common.open)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(L.common.cancel) } }
    )
}
