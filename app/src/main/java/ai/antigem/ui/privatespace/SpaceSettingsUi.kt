package ai.antigem.ui.privatespace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.privatespace.PrivateSpace
import ai.antigem.data.privatespace.SpaceSettings
import ai.antigem.theme.ClaudeTerracotta
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

private sealed interface SettingsSub {
    data object ChangePassword : SettingsSub
    data class NewRecoveryKey(val key: String) : SettingsSub
}

internal fun autoLockLabel(seconds: Int): String = when {
    seconds <= 0 -> L.privateSpace.settings.immediately
    seconds < 60 -> L.privateSpace.settings.seconds(seconds)
    seconds < 3600 -> L.privateSpace.settings.minutes(seconds / 60)
    else -> L.privateSpace.settings.hours(seconds / 3600)
}

@Composable
internal fun SpaceSettingsScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val settings by PrivateSpace.settings.collectAsState()
    val quick by PrivateSpace.quick.collectAsState()
    val items by PrivateSpace.items.collectAsState()
    var sub by remember { mutableStateOf<SettingsSub?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var askRecoveryPassword by remember { mutableStateOf(false) }
    var askDeletePassword by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }
    var showHow by remember { mutableStateOf(false) }

    when (val s = sub) {
        SettingsSub.ChangePassword -> {
            ChangePasswordScreen(onBack = { sub = null }, onDone = { newRecoveryKey ->
                // keys replaced too: the new recovery key is shown once, like after setup
                sub = newRecoveryKey?.let { SettingsSub.NewRecoveryKey(it) }
                showMessage(L.privateSpace.settings.passwordChanged)
            })
            return
        }
        is SettingsSub.NewRecoveryKey -> {
            RecoveryKeyScreen(recoveryKey = s.key, title = L.privateSpace.settings.newRecoveryTitle, onBack = null, onContinue = { sub = null })
            return
        }
        null -> Unit
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(L.privateSpace.settings.title, onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                L.privateSpace.settings.summary(active = items.count { !it.inTrash }, trash = items.count { it.inTrash }),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            SettingsHeader(L.privateSpace.settings.unlockSection)
            QuickUnlockCards()
            ChoiceBlock(
                title = L.privateSpace.settings.masterEvery,
                subtitle = L.privateSpace.settings.masterEveryHint,
                choices = PrivateSpace.MASTER_EVERY_CHOICES,
                selected = quick.masterEveryDays,
                label = { L.privateSpace.settings.days(it) },
                onSelect = { PrivateSpace.setMasterEveryDays(it) }
            )

            SettingsHeader(L.privateSpace.settings.lockSection)
            ChoiceBlock(
                title = L.privateSpace.settings.autoLock,
                subtitle = L.privateSpace.settings.autoLockHint,
                choices = SpaceSettings.AUTO_LOCK_CHOICES,
                selected = settings.autoLockSeconds,
                label = { autoLockLabel(it) },
                onSelect = { v -> PrivateSpace.updateSettings { it.copy(autoLockSeconds = v) } }
            )
            ChoiceBlock(
                title = L.privateSpace.settings.clipboard,
                subtitle = L.privateSpace.settings.clipboardHint,
                choices = SpaceSettings.CLIPBOARD_CHOICES,
                selected = settings.clipboardSeconds,
                label = { autoLockLabel(it) },
                onSelect = { v -> PrivateSpace.updateSettings { it.copy(clipboardSeconds = v) } }
            )

            SettingsHeader(L.privateSpace.settings.backupSection)
            ActionRow(Icons.Outlined.Backup, L.privateSpace.settings.createBackup, L.privateSpace.settings.createBackupHint) { exporting = true }
            ActionRow(Icons.Outlined.SettingsBackupRestore, L.privateSpace.settings.restoreBackup, L.privateSpace.settings.restoreBackupHint) { restoring = true }

            SettingsHeader(L.privateSpace.settings.passwordSection)
            ActionRow(Icons.Outlined.Password, L.privateSpace.settings.changePassword, L.privateSpace.settings.changePasswordHint) { sub = SettingsSub.ChangePassword }
            ActionRow(Icons.Outlined.Key, L.privateSpace.settings.newRecovery, L.privateSpace.settings.newRecoveryHint) { askRecoveryPassword = true }
            ActionRow(Icons.Outlined.Autorenew, L.privateSpace.gen.title, L.privateSpace.settings.generatorHint) { showGenerator = true }

            SettingsHeader(L.privateSpace.settings.aboutSection)
            ActionRow(Icons.Outlined.Shield, L.privateSpace.settings.howProtected, null) { showHow = !showHow }
            if (showHow) {
                Surface(shape = RoundedCornerShape(12.dp), color = ClaudeTerracotta.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                    Text(L.privateSpace.settings.howProtectedText, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                }
            }

            SettingsHeader(L.privateSpace.settings.dangerSection)
            ActionRow(Icons.Outlined.DeleteForever, L.privateSpace.settings.deleteSpace, L.privateSpace.settings.deleteSpaceHint, danger = true) { askDeletePassword = true }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (exporting) ExportBackupFlow(onDismiss = { exporting = false }, onMessage = showMessage)
    if (restoring) RestoreBackupFlow(adopt = false, onDismiss = { restoring = false }, onMessage = showMessage)
    if (showGenerator) GeneratorDialog(onDismiss = { showGenerator = false }, onUse = null)
    if (askRecoveryPassword) {
        PasswordPromptDialog(
            title = L.privateSpace.settings.newRecovery,
            text = L.privateSpace.settings.newRecoveryText,
            confirmLabel = L.common.continueText,
            danger = false,
            onDismiss = { askRecoveryPassword = false },
            onPassword = { pw ->
                val key = PrivateSpace.newRecoveryKey(pw)
                if (key != null) {
                    askRecoveryPassword = false
                    sub = SettingsSub.NewRecoveryKey(key)
                }
                key != null
            }
        )
    }
    if (askDeletePassword) {
        PasswordPromptDialog(
            title = L.privateSpace.settings.deleteTitle,
            text = L.privateSpace.settings.deleteText,
            confirmLabel = L.privateSpace.settings.deleteConfirm,
            danger = true,
            onDismiss = { askDeletePassword = false },
            onPassword = { pw ->
                val ok = PrivateSpace.deleteSpace(pw)
                if (ok) askDeletePassword = false
                ok
            }
        )
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() }
    )
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String?, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (danger) color else ClaudeTerracotta, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
                if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
private fun ChoiceBlock(title: String, subtitle: String, choices: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { c ->
                    FilterChip(selected = c == selected, onClick = { onSelect(c) }, label = { Text(label(c)) })
                }
            }
        }
    }
}

/** Asks for the master password before [onPassword] (which returns false for a wrong one). */
@Composable
private fun PasswordPromptDialog(
    title: String,
    text: String,
    confirmLabel: String,
    danger: Boolean,
    onDismiss: () -> Unit,
    onPassword: suspend (CharArray) -> Boolean
) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun go() {
        if (busy || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val chars = password.toCharArray()
            val ok = runCatching { onPassword(chars) }.getOrDefault(false)
            chars.fill('\u0000')
            busy = false
            if (!ok) error = L.privateSpace.lock.wrongPassword else password = ""
        }
    }

    SpaceAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(if (danger) Icons.Outlined.WarningAmber else Icons.Outlined.Lock, null, tint = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, fontSize = 14.sp)
                SecretField(password, { password = it }, L.privateSpace.setup.masterPassword, autoFocus = true, onImeAction = { go() }, isError = error != null, supportingText = error)
            }
        },
        confirmButton = {
            Button(
                onClick = { go() },
                enabled = !busy && password.isNotEmpty(),
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(L.common.cancel) } }
    )
}

@Composable
private fun ChangePasswordScreen(onBack: () -> Unit, onDone: (newRecoveryKey: String?) -> Unit) {
    var current by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var replaceKeys by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = current.isNotEmpty() && masterPasswordReady(password, confirm) && password != current

    fun save() {
        if (!ready || busy) return
        busy = true
        error = null
        scope.launch {
            val c = current.toCharArray()
            val n = password.toCharArray()
            // the old password may be known: a new space key and recovery key too (PrivateSpace.replaceKeys)
            val recovery = if (replaceKeys) runCatching { PrivateSpace.replaceKeys(c, n) }.getOrNull() else null
            val ok = if (replaceKeys) recovery != null else runCatching { PrivateSpace.changePassword(c, n) }.getOrDefault(false)
            c.fill('\u0000')
            n.fill('\u0000')
            busy = false
            if (ok) onDone(recovery) else error = L.privateSpace.settings.currentWrong
        }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(L.privateSpace.settings.changePassword, onBack = onBack)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(L.privateSpace.settings.changePasswordText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            SecretField(current, { current = it }, L.privateSpace.settings.currentPassword, imeAction = ImeAction.Next, isError = error != null, supportingText = error)
            MasterPasswordFields(password, { password = it }, confirm, { confirm = it }, passwordLabel = L.privateSpace.settings.newPassword, onSubmit = { save() })
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { replaceKeys = !replaceKeys }.padding(vertical = 4.dp)
            ) {
                Checkbox(checked = replaceKeys, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.privateSpace.settings.replaceKeys, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(L.privateSpace.settings.replaceKeysHint, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
            }
            BusyButton(L.privateSpace.settings.changePassword, busy = busy, enabled = ready, onClick = { save() })
        }
    }
}
