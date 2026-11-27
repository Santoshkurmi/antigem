package ai.antigem.ui.privatespace

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.antigem.data.privatespace.PasswordTools
import ai.antigem.data.privatespace.PinResult
import ai.antigem.data.privatespace.PrivateSpace
import ai.antigem.data.privatespace.QuickNotice
import ai.antigem.data.privatespace.QuickUnlockState
import ai.antigem.data.privatespace.SpaceHardwareKeys
import ai.antigem.data.privatespace.SpaceStatus
import ai.antigem.theme.ClaudeTerracotta
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * The Private Space page (a page of the floating switcher, like Notes). Composed only while it is the page on screen:
 * nothing of it exists off screen. While shown, the window is FLAG_SECURE (screenshots, screen recording, adb
 * screencap and the Recents picture come out black) and, on Android 14+, marked accessibility-data-sensitive (only
 * real accessibility tools such as TalkBack read it).
 */
@Composable
fun PrivateSpaceScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { PrivateSpace.init(context) }
    val status by PrivateSpace.status.collectAsState()
    val needsNewPassword by PrivateSpace.needsNewPassword.collectAsState()
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        // Autofill apps (Google Password Manager…) never see this page: no "save password?" with a space's secret.
        // The fields are also marked not autofillable (noAutofill); this ends any session already open.
        val autofill = if (Build.VERSION.SDK_INT >= 26) context.getSystemService(android.view.autofill.AutofillManager::class.java) else null
        val autofillBefore = if (Build.VERSION.SDK_INT >= 26) view.importantForAutofill else 0
        if (Build.VERSION.SDK_INT >= 26) {
            autofill?.cancel()
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        PrivateSpace.onPageShown()
        // the app going to the background counts as leaving the page (auto-lock); coming back as returning to it
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    PrivateSpace.endExternalPick()
                    PrivateSpace.onPageShown()
                }
                Lifecycle.Event.ON_STOP -> PrivateSpace.onPageHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_AUTO)
            if (Build.VERSION.SDK_INT >= 26) {
                autofill?.cancel()
                view.importantForAutofill = autofillBefore
            }
            PrivateSpace.onPageHidden()
        }
    }

    // Setup keeps its own steps on screen after the space exists (the recovery key and quick unlock come after)
    var setupActive by remember { mutableStateOf(false) }

    // Kept from tools unless a screen reader or other real accessibility tool is on (LocalSpaceHideFromTools); copies
    // made on the page are secrets (SpaceClipboard); keyboards don't learn what is typed here (NoLearningTextInput).
    // Dialogs inherit all three.
    val hideFromTools = !rememberRealAccessibilityOn()
    val platformClipboard = LocalClipboard.current
    val spaceClipboard = remember(platformClipboard) { SpaceClipboard(context.applicationContext, platformClipboard) }
    CompositionLocalProvider(LocalClipboard provides spaceClipboard, LocalSpaceHideFromTools provides hideFromTools) {
        InterceptPlatformTextInput(NoLearningTextInput) {
            Surface(modifier.fillMaxSize().hiddenFromTools(), color = MaterialTheme.colorScheme.background) {
                when {
                    status == SpaceStatus.NOT_SET_UP || setupActive -> SetupFlow(
                        onClose = onClose,
                        onStarted = { setupActive = true },
                        onFinished = { setupActive = false }
                    )
                    status == SpaceStatus.LOCKED -> LockScreen(onClose)
                    needsNewPassword -> NewPasswordAfterRecovery(onClose)
                    else -> SpaceHome(onClose)
                }
            }
        }
    }
}

/**
 * Warnings that something else on the phone touched Private Space: a chip key replaced, or PIN tries made past AntiGem's
 * PIN pad. Shown on the lock screen and, after unlocking, above the list, until dismissed.
 */
@Composable
internal fun SecurityNotice(quick: QuickUnlockState) {
    val red = Color(0xFFEF4444)
    when (quick.notice) {
        QuickNotice.KEYS_TAMPERED -> InfoCard(Icons.Outlined.GppMaybe, L.privateSpace.lock.noticeTampered, color = red, onDismiss = { PrivateSpace.dismissNotice() })
        QuickNotice.PIN_OUTSIDE_TRIES -> InfoCard(Icons.Outlined.GppMaybe, L.privateSpace.lock.noticePinOutside(quick.noticeCount), color = red, onDismiss = { PrivateSpace.dismissNotice() })
        else -> Unit
    }
}

// ---------------- setup ----------------

private enum class SetupStep { INTRO, PASSWORD, RECOVERY, QUICK }

@Composable
private fun SetupFlow(onClose: () -> Unit, onStarted: () -> Unit, onFinished: () -> Unit) {
    var step by remember { mutableStateOf(SetupStep.INTRO) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val status by PrivateSpace.status.collectAsState()

    BackHandler(enabled = step == SetupStep.PASSWORD) { step = SetupStep.INTRO }
    // locked meanwhile (auto-lock while away): the rest of setup needs it open
    LaunchedEffect(status, step) {
        if (status == SpaceStatus.LOCKED && step != SetupStep.INTRO && step != SetupStep.PASSWORD) onFinished()
    }

    when (step) {
        SetupStep.INTRO -> SetupIntro(onClose = onClose, onCreate = { step = SetupStep.PASSWORD }, onRestore = { restoring = true })
        SetupStep.PASSWORD -> SetupPassword(
            onBack = { step = SetupStep.INTRO },
            onStarted = onStarted,
            onCreated = { key ->
                recoveryKey = key
                step = SetupStep.RECOVERY
            }
        )
        SetupStep.RECOVERY -> RecoveryKeyScreen(
            recoveryKey = recoveryKey.orEmpty(),
            title = L.privateSpace.recovery.title,
            onBack = null,
            onContinue = {
                recoveryKey = null
                step = SetupStep.QUICK
            }
        )
        SetupStep.QUICK -> Column(Modifier.fillMaxSize()) {
            SpaceTopBar(L.privateSpace.setup.quickTitle, onBack = onFinished)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(L.privateSpace.setup.quickIntro, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                QuickUnlockCards()
                Spacer(Modifier.height(8.dp))
                BusyButton(L.common.done, busy = false, enabled = true, onClick = onFinished)
            }
        }
    }

    if (restoring) RestoreBackupFlow(adopt = true, onDismiss = { restoring = false }, onMessage = {})
}

@Composable
private fun SetupIntro(onClose: () -> Unit, onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(L.privateSpace.title, onBack = onClose)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(88.dp).clip(CircleShape).background(ClaudeTerracotta.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Shield, null, tint = ClaudeTerracotta, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(L.privateSpace.setup.headline, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(
                L.privateSpace.setup.subtitle,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(20.dp))
            BulletRow(Icons.Outlined.Lock, L.privateSpace.setup.pointEncrypted)
            BulletRow(Icons.Outlined.SmartToy, L.privateSpace.setup.pointNoAi)
            BulletRow(Icons.Outlined.Fingerprint, L.privateSpace.setup.pointQuick)
            BulletRow(Icons.Outlined.Backup, L.privateSpace.setup.pointBackups)
            BulletRow(Icons.Outlined.Key, L.privateSpace.setup.pointTypes)
            BulletRow(Icons.Outlined.WarningAmber, L.privateSpace.setup.pointNoRecovery)
            Spacer(Modifier.height(24.dp))
            BusyButton(L.privateSpace.setup.create, busy = false, enabled = true, onClick = onCreate)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Outlined.SettingsBackupRestore, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(L.privateSpace.setup.restore)
            }
        }
    }
}

@Composable
internal fun MasterPasswordFields(
    password: String,
    onPassword: (String) -> Unit,
    confirm: String,
    onConfirm: (String) -> Unit,
    passwordLabel: String = L.privateSpace.setup.masterPassword,
    onSubmit: () -> Unit
) {
    val long = password.length >= PasswordTools.MASTER_MIN_LENGTH
    val strong = PasswordTools.strength(password).score >= 3
    val matches = password.isNotEmpty() && password == confirm
    SecretField(password, onPassword, passwordLabel, imeAction = ImeAction.Next, autoFocus = true)
    StrengthBar(password)
    SecretField(
        confirm, onConfirm, L.privateSpace.setup.confirmPassword,
        imeAction = ImeAction.Done,
        onImeAction = onSubmit,
        isError = confirm.isNotEmpty() && !matches
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RuleRow(long, L.privateSpace.setup.ruleLength(PasswordTools.MASTER_MIN_LENGTH))
        RuleRow(strong, L.privateSpace.setup.ruleStrong)
        RuleRow(matches, L.privateSpace.setup.ruleMatch)
    }
    InfoCard(Icons.Outlined.Lightbulb, L.privateSpace.setup.passwordTip, color = ClaudeTerracotta)
}

internal fun masterPasswordReady(password: String, confirm: String): Boolean =
    password == confirm && PasswordTools.masterPasswordOk(password.toCharArray())

@Composable
private fun RuleRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (ok) L.privateSpace.setup.ruleMet else L.privateSpace.setup.ruleNotMet,
            tint = if (ok) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (ok) 0.9f else 0.6f))
    }
}

@Composable
private fun SetupPassword(onBack: () -> Unit, onStarted: () -> Unit, onCreated: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = masterPasswordReady(password, confirm)

    fun create() {
        if (!ready || busy) return
        busy = true
        error = null
        onStarted()
        scope.launch {
            val chars = password.toCharArray()
            try {
                val key = PrivateSpace.create(chars)
                password = ""
                confirm = ""
                onCreated(key)
            } catch (e: Exception) {
                error = L.privateSpace.setup.createFailed(e.message ?: e.javaClass.simpleName)
            } finally {
                chars.fill('\u0000')
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(L.privateSpace.setup.passwordTitle, onBack = onBack)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(L.privateSpace.setup.passwordIntro, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            MasterPasswordFields(password, { password = it }, confirm, { confirm = it }, onSubmit = { create() })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            BusyButton(if (busy) L.privateSpace.setup.creating else L.privateSpace.setup.create, busy = busy, enabled = ready, onClick = { create() })
        }
    }
}

/** Shows a recovery key once: copy, save as a file, and confirm it was kept. */
@Composable
internal fun RecoveryKeyScreen(recoveryKey: String, title: String, onBack: (() -> Unit)?, onContinue: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var fileSaved by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        PrivateSpace.endExternalPick()
        fileError = null
        if (uri != null && isOnThisPhone(context, uri)) {
            // agents in the terminal can read the phone's files: with this key and the space file they'd open it all.
            // The picker already made an empty file there: removed again, and nothing of the key is written.
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            fileSaved = false
            fileError = L.privateSpace.recovery.fileOnPhone
        } else if (uri != null) {
            scope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(L.privateSpace.recovery.fileText(key = recoveryKey, date = dateTime(context, System.currentTimeMillis())).toByteArray())
                        }
                    }.isSuccess
                }
                fileSaved = ok
            }
        }
    }
    if (onBack != null) BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(title, onBack = onBack ?: onContinue)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(L.privateSpace.recovery.intro, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    recoveryKey.split('-').chunked(4).joinToString("\n") { it.joinToString("-") },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 12.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    PrivateSpace.copySecret(context, recoveryKey)
                    copied = true
                }, modifier = Modifier.weight(1f)) {
                    Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) L.common.copied else L.common.copy)
                }
                OutlinedButton(onClick = {
                    PrivateSpace.beginExternalPick()
                    saveFile.launch(L.privateSpace.recovery.fileName + ".txt")
                }, modifier = Modifier.weight(1f)) {
                    Icon(if (fileSaved) Icons.Outlined.Check else Icons.Outlined.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (fileSaved) L.privateSpace.recovery.fileSaved else L.privateSpace.recovery.saveFile)
                }
            }
            fileError?.let { InfoCard(Icons.Outlined.ErrorOutline, it, color = Color(0xFFEF4444), onDismiss = { fileError = null }) }
            InfoCard(Icons.Outlined.WarningAmber, L.privateSpace.recovery.warning, color = Color(0xFFF59E0B))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { saved = !saved }.padding(vertical = 4.dp)
            ) {
                Checkbox(checked = saved, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(L.privateSpace.recovery.confirmSaved, fontSize = 14.sp)
            }
            BusyButton(L.common.continueText, busy = false, enabled = saved, onClick = onContinue)
        }
    }
}

// ---------------- quick unlock (setup and settings) ----------------

@Composable
internal fun QuickUnlockCards() {
    val context = LocalContext.current
    val quick by PrivateSpace.quick.collectAsState()
    val availability = remember { SpaceHardwareKeys.bioAvailability(context) }
    var settingPin by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun turnOnFingerprint() {
        val activity = context.findActivity() ?: return
        val cipher = try {
            PrivateSpace.bioEnableCipher()
        } catch (e: Exception) {
            message = L.privateSpace.quick.fingerprintFailed(e.message ?: e.javaClass.simpleName)
            return
        }
        SpaceBiometricPrompt.show(
            activity, cipher,
            title = L.privateSpace.quick.fingerprintPromptTitle,
            subtitle = L.privateSpace.quick.fingerprintPromptSubtitle,
            negativeText = L.common.cancel,
            onSuccess = { c ->
                message = if (PrivateSpace.finishBioEnable(c)) L.privateSpace.quick.fingerprintOn else L.privateSpace.quick.fingerprintFailed("")
            },
            onError = { err -> if (err != null) message = err }
        )
    }

    QuickCard(
        icon = Icons.Outlined.Fingerprint,
        title = L.privateSpace.quick.fingerprint,
        subtitle = when {
            quick.bioEnabled -> L.privateSpace.quick.fingerprintOnHint
            availability == SpaceHardwareKeys.BioAvailability.AVAILABLE -> L.privateSpace.quick.fingerprintOffHint
            availability == SpaceHardwareKeys.BioAvailability.NONE_ENROLLED -> L.privateSpace.quick.fingerprintNoneEnrolled
            availability == SpaceHardwareKeys.BioAvailability.NO_SCREEN_LOCK -> L.privateSpace.quick.fingerprintNoScreenLock
            availability == SpaceHardwareKeys.BioAvailability.ANDROID_TOO_OLD -> L.privateSpace.quick.fingerprintOldAndroid
            else -> L.privateSpace.quick.fingerprintNoHardware
        },
        checked = quick.bioEnabled,
        enabled = quick.bioEnabled || availability == SpaceHardwareKeys.BioAvailability.AVAILABLE,
        onToggle = { on -> if (on) turnOnFingerprint() else PrivateSpace.disableBio() }
    )
    QuickCard(
        icon = Icons.Outlined.Pin,
        title = L.privateSpace.quick.pin,
        subtitle = when {
            !SpaceHardwareKeys.pinSupported -> L.privateSpace.quick.pinOldAndroid
            quick.pinEnabled -> L.privateSpace.quick.pinOnHint(SpaceHardwareKeys.PIN_TRIES)
            else -> L.privateSpace.quick.pinOffHint(SpaceHardwareKeys.PIN_TRIES)
        },
        checked = quick.pinEnabled,
        enabled = SpaceHardwareKeys.pinSupported,
        onToggle = { on -> if (on) settingPin = true else PrivateSpace.removePin() },
        extra = if (quick.pinEnabled) ({
            TextButton(onClick = { settingPin = true }) { Text(L.privateSpace.quick.changePin) }
        }) else null
    )
    message?.let { InfoCard(Icons.Outlined.Info, it, onDismiss = { message = null }) }

    if (settingPin) {
        PinSetupDialog(
            onDismiss = { settingPin = false },
            onPin = { pin ->
                scope.launch {
                    val chars = pin.toCharArray()
                    val ok = PrivateSpace.setPin(chars)
                    chars.fill('\u0000')
                    message = if (ok) L.privateSpace.quick.pinSet else L.privateSpace.quick.pinFailed
                    settingPin = false
                }
            }
        )
    }
}

@Composable
private fun QuickCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    extra: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onToggle(!checked) }
            ) {
                Icon(icon, null, tint = if (enabled) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
            extra?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { it() } }
        }
    }
}

/** Pick a PIN (6+ digits) twice, on AntiGem's own PIN pad. */
@Composable
private fun PinSetupDialog(onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (pin.length < PasswordTools.PIN_MIN) return
        val f = first
        if (f == null) {
            if (!PasswordTools.pinOk(pin.toCharArray())) {
                error = L.privateSpace.quick.pinTooSimple
                pin = ""
                return
            }
            first = pin
            pin = ""
            error = null
        } else if (f == pin) {
            busy = true
            onPin(pin)
        } else {
            error = L.privateSpace.quick.pinMismatch
            first = null
            pin = ""
        }
    }

    SpaceAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (first == null) L.privateSpace.quick.choosePin else L.privateSpace.quick.confirmPin) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    error ?: L.privateSpace.quick.pinRules(min = PasswordTools.PIN_MIN, max = PasswordTools.PIN_MAX),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(16.dp))
                PinPad(
                    entered = pin.length,
                    length = first?.length ?: 0,
                    enabled = !busy,
                    onDigit = { d ->
                        if (pin.length < PasswordTools.PIN_MAX) pin += d
                        if (first != null && pin.length == first!!.length) submit()
                    },
                    onBackspace = { pin = pin.dropLast(1) },
                    onSubmit = { submit() }
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(L.common.cancel) } }
    )
}

// ---------------- lock screen ----------------

private enum class LockMode { PIN, PASSWORD, RECOVERY }

@Composable
private fun LockScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val quick by PrivateSpace.quick.collectAsState()
    val pinUsable = quick.pinEnabled && !quick.masterDue
    val bioUsable = quick.bioEnabled && !quick.masterDue
    var mode by remember { mutableStateOf(if (pinUsable) LockMode.PIN else LockMode.PASSWORD) }
    var pin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmErase by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pinUsable) { if (!pinUsable && mode == LockMode.PIN) mode = LockMode.PASSWORD }

    fun startFingerprint() {
        val activity = context.findActivity() ?: return
        when (val c = PrivateSpace.bioUnlockCipher()) {
            is PrivateSpace.BioCipher.Ready -> SpaceBiometricPrompt.show(
                activity, c.cipher,
                title = L.privateSpace.lock.fingerprintTitle,
                subtitle = null,
                negativeText = if (pinUsable) L.privateSpace.lock.usePin else L.privateSpace.lock.usePassword,
                onSuccess = { cipher ->
                    scope.launch {
                        busy = true
                        if (!PrivateSpace.finishBioUnlock(cipher)) error = L.privateSpace.lock.fingerprintFailed
                        busy = false
                    }
                },
                onError = { err -> if (err != null) error = err }
            )
            else -> Unit // the notice card says why
        }
    }

    val pageShows by PrivateSpace.pageShows.collectAsState()
    LaunchedEffect(pageShows) { if (bioUsable && PrivateSpace.consumeAutoPrompt()) startFingerprint() }

    fun submitPin() {
        if (busy || pin.length < PasswordTools.PIN_MIN) return
        busy = true
        error = null
        val chars = pin.toCharArray()
        pin = ""
        scope.launch {
            when (val r = PrivateSpace.unlockWithPin(chars)) {
                PinResult.Ok -> Unit
                is PinResult.Wrong -> error = L.privateSpace.lock.wrongPin(r.triesLeft)
                PinResult.LockedOut -> { mode = LockMode.PASSWORD }
                PinResult.Unavailable -> mode = LockMode.PASSWORD
            }
            chars.fill('\u0000')
            busy = false
        }
    }

    fun submitPassword() {
        if (busy || password.isEmpty()) return
        busy = true
        error = null
        val chars = password.toCharArray()
        scope.launch {
            val ok = PrivateSpace.unlockWithPassword(chars)
            chars.fill('\u0000')
            if (ok) password = "" else error = L.privateSpace.lock.wrongPassword
            busy = false
        }
    }

    fun submitRecovery() {
        if (busy || recovery.isBlank()) return
        busy = true
        error = null
        scope.launch {
            val ok = PrivateSpace.unlockWithRecoveryKey(recovery)
            if (ok) recovery = "" else error = L.privateSpace.lock.wrongRecovery
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(L.privateSpace.title, onBack = onClose)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(ClaudeTerracotta.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, null, tint = ClaudeTerracotta, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(L.privateSpace.lock.locked, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(
                when (mode) {
                    LockMode.PIN -> L.privateSpace.lock.enterPin
                    LockMode.PASSWORD -> L.privateSpace.lock.enterPassword
                    LockMode.RECOVERY -> L.privateSpace.lock.enterRecovery
                },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))

            when (quick.notice) {
                QuickNotice.FINGERPRINT_CHANGED -> InfoCard(Icons.Outlined.Fingerprint, L.privateSpace.lock.noticeFingerprintChanged, color = Color(0xFFF59E0B), onDismiss = { PrivateSpace.dismissNotice() })
                QuickNotice.PIN_LOCKED_OUT -> InfoCard(Icons.Outlined.Pin, L.privateSpace.lock.noticePinLockedOut(SpaceHardwareKeys.PIN_TRIES), color = Color(0xFFEF4444), onDismiss = { PrivateSpace.dismissNotice() })
                QuickNotice.KEYS_TAMPERED, QuickNotice.PIN_OUTSIDE_TRIES -> SecurityNotice(quick)
                null -> Unit
            }
            // the chip counted PIN tries that weren't typed here (since the last PIN unlock): shown before unlocking
            if (quick.pinEnabled && quick.pinOutsideTries > 0 && quick.notice != QuickNotice.PIN_OUTSIDE_TRIES) {
                Spacer(Modifier.height(8.dp))
                InfoCard(Icons.Outlined.GppMaybe, L.privateSpace.lock.noticePinOutside(quick.pinOutsideTries), color = Color(0xFFEF4444))
            }
            if (quick.masterDue && (quick.pinEnabled || quick.bioEnabled)) {
                Spacer(Modifier.height(8.dp))
                InfoCard(Icons.Outlined.Schedule, L.privateSpace.lock.masterDue(quick.masterEveryDays))
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))

            when (mode) {
                LockMode.PIN -> {
                    if (quick.pinTriesLeft in 1 until SpaceHardwareKeys.PIN_TRIES) {
                        Text(L.privateSpace.lock.triesLeft(quick.pinTriesLeft), fontSize = 13.sp, color = Color(0xFFF59E0B))
                        Spacer(Modifier.height(10.dp))
                    }
                    PinPad(
                        entered = pin.length,
                        length = quick.pinLength,
                        enabled = !busy,
                        onDigit = { d ->
                            if (pin.length < PasswordTools.PIN_MAX) pin += d
                            if (quick.pinLength > 0 && pin.length == quick.pinLength) submitPin()
                        },
                        onBackspace = { pin = pin.dropLast(1) },
                        onFingerprint = if (bioUsable) ({ startFingerprint() }) else null,
                        onSubmit = if (quick.pinLength == 0) ({ submitPin() }) else null
                    )
                }
                LockMode.PASSWORD -> {
                    SecretField(password, { password = it }, L.privateSpace.setup.masterPassword, autoFocus = !bioUsable, onImeAction = { submitPassword() })
                    Spacer(Modifier.height(14.dp))
                    BusyButton(if (busy) L.privateSpace.lock.unlocking else L.privateSpace.lock.unlock, busy = busy, enabled = password.isNotEmpty(), onClick = { submitPassword() })
                    if (bioUsable) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { startFingerprint() }) {
                            Icon(Icons.Outlined.Fingerprint, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(L.privateSpace.lock.useFingerprint)
                        }
                    }
                }
                LockMode.RECOVERY -> {
                    OutlinedTextField(
                        value = recovery,
                        onValueChange = { recovery = it.uppercase() },
                        label = { Text(L.privateSpace.lock.recoveryKey) },
                        placeholder = { Text("XXXX-XXXX-XXXX-…") },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submitRecovery() }),
                        modifier = Modifier.fillMaxWidth().noAutofill()
                    )
                    Spacer(Modifier.height(14.dp))
                    BusyButton(if (busy) L.privateSpace.lock.unlocking else L.privateSpace.lock.unlock, busy = busy, enabled = recovery.isNotBlank(), onClick = { submitRecovery() })
                }
            }

            Spacer(Modifier.height(20.dp))
            if (mode != LockMode.PIN && pinUsable) TextButton(onClick = { mode = LockMode.PIN; error = null }) { Text(L.privateSpace.lock.usePin) }
            if (mode != LockMode.PASSWORD) TextButton(onClick = { mode = LockMode.PASSWORD; error = null }) { Text(L.privateSpace.lock.usePassword) }
            if (mode != LockMode.RECOVERY) TextButton(onClick = { mode = LockMode.RECOVERY; error = null }) { Text(L.privateSpace.lock.forgotPassword) }
            if (mode == LockMode.RECOVERY) {
                TextButton(onClick = { confirmErase = true }) {
                    Text(L.privateSpace.lock.lostBoth, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmErase) {
        TypedConfirmDialog(
            title = L.privateSpace.lock.eraseTitle,
            text = L.privateSpace.lock.eraseText,
            word = L.privateSpace.lock.eraseWord,
            confirmLabel = L.privateSpace.lock.eraseConfirm,
            onDismiss = { confirmErase = false },
            onConfirm = {
                confirmErase = false
                PrivateSpace.eraseForgotten()
            }
        )
    }
}

/** A dangerous action: the user types [word] to turn the red button on. */
@Composable
internal fun TypedConfirmDialog(title: String, text: String, word: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    SpaceAlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, fontSize = 14.sp)
                Text(L.privateSpace.common.typeToConfirm(word), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().noAutofill()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = typed.trim().equals(word, ignoreCase = true),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

// ---------------- after the recovery key ----------------

@Composable
private fun NewPasswordAfterRecovery(onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ready = masterPasswordReady(password, confirm)

    fun save() {
        if (!ready || busy) return
        busy = true
        scope.launch {
            val chars = password.toCharArray()
            runCatching { PrivateSpace.setNewPasswordAfterRecovery(chars) }
            chars.fill('\u0000')
            busy = false
        }
    }

    // leaving without a new password locks the space again (the recovery key still opens it)
    val leave = {
        PrivateSpace.lock()
        onClose()
    }
    BackHandler(onBack = leave)
    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(L.privateSpace.recovery.newPasswordTitle, onBack = leave)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(Icons.Outlined.Key, L.privateSpace.recovery.newPasswordIntro, color = ClaudeTerracotta)
            MasterPasswordFields(password, { password = it }, confirm, { confirm = it }, passwordLabel = L.privateSpace.settings.newPassword, onSubmit = { save() })
            BusyButton(L.common.save, busy = busy, enabled = ready, onClick = { save() })
        }
    }
}
