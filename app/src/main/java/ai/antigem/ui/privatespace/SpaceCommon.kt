package ai.antigem.ui.privatespace

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.nativeClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import ai.antigem.data.privatespace.PrivateSpace
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.privatespace.PasswordTools
import ai.antigem.data.privatespace.SpaceField
import ai.antigem.data.privatespace.SpaceFieldKind
import ai.antigem.data.privatespace.SpaceItemType
import ai.antigem.theme.ClaudeTerracotta
import ai.antigem.i18n.L

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Keeps a field out of Android autofill (Google Password Manager and other autofill apps): they are never offered its
 * value to save to their cloud, and never asked to fill it.
 */
internal fun Modifier.noAutofill(): Modifier = semantics { contentDataType = ContentDataType.None }

// ---------------- kept from tools ----------------

/**
 * True while the page's text is kept out of the accessibility tree: no screen reader or other real accessibility tool
 * is on. Then nothing reading that tree sees the page: AntiGem's phone-control service, `uiautomator dump` run through
 * ADB, Shizuku or root by an agent, or another app's accessibility service. Screenshots are already black (FLAG_SECURE),
 * so such a tool sees nothing at all. With TalkBack, Switch Access, Voice Access… on, the page reads as normal.
 */
internal val LocalSpaceHideFromTools = compositionLocalOf { false }

/** Clears this part's accessibility text while [LocalSpaceHideFromTools] (see there). */
@Composable
internal fun Modifier.hiddenFromTools(): Modifier = if (LocalSpaceHideFromTools.current) this.clearAndSetSemantics { } else this

/**
 * A screen reader or another real accessibility tool the user turned on (TalkBack; on Android 12+ also Switch Access,
 * Voice Access and other services marked as accessibility tools). Not AntiGem's own phone-control service, and never
 * uiautomator or other shell tools (they are no accessibility service).
 */
internal fun realAccessibilityOn(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    if (!am.isEnabled) return false
    if (am.isTouchExplorationEnabled) return true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            info.isAccessibilityTool && info.resolveInfo?.serviceInfo?.packageName != context.packageName
        }
    }
    return false
}

/** [realAccessibilityOn], kept up to date while the page is shown. */
@Composable
internal fun rememberRealAccessibilityOn(): Boolean {
    val context = LocalContext.current
    val pageShows by PrivateSpace.pageShows.collectAsState()
    var on by remember { mutableStateOf(realAccessibilityOn(context)) }
    DisposableEffect(context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val update = { on = realAccessibilityOn(context) }
        val state = AccessibilityManager.AccessibilityStateChangeListener { update() }
        val touch = AccessibilityManager.TouchExplorationStateChangeListener { update() }
        am?.addAccessibilityStateChangeListener(state)
        am?.addTouchExplorationStateChangeListener(touch)
        // a service switched on or off while accessibility stays on (Android 13+ can tell)
        var services: Any? = null
        if (Build.VERSION.SDK_INT >= 33 && am != null) {
            val l = AccessibilityManager.AccessibilityServicesStateChangeListener { update() }
            am.addAccessibilityServicesStateChangeListener(context.mainExecutor, l)
            services = l
        }
        onDispose {
            am?.removeAccessibilityStateChangeListener(state)
            am?.removeTouchExplorationStateChangeListener(touch)
            if (Build.VERSION.SDK_INT >= 33 && am != null) {
                (services as? AccessibilityManager.AccessibilityServicesStateChangeListener)?.let { am.removeAccessibilityServicesStateChangeListener(it) }
            }
        }
    }
    LaunchedEffect(pageShows) { on = realAccessibilityOn(context) }
    return on
}

/** Marks the window this runs in (a dialog's own window) as data-sensitive on Android 14+, like the page itself. */
@Composable
private fun MarkWindowSensitive() {
    if (Build.VERSION.SDK_INT < 34) return
    val view = LocalView.current
    DisposableEffect(view) {
        view.rootView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        onDispose { }
    }
}

/**
 * Private Space's dialogs. A dialog is a window of its own, so the page's protections are set on it again: always
 * FLAG_SECURE (black in screenshots and recordings), data-sensitive on Android 14+, and kept from tools like the page.
 */
@Composable
internal fun SpaceAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            MarkWindowSensitive()
            confirmButton()
        },
        modifier = Modifier.hiddenFromTools(),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
    )
}

/**
 * The page's clipboard: whatever is copied on it (selected text, a text field's Copy or Cut) is copied like a secret
 * ([PrivateSpace.copySecret]: marked sensitive and Private Space's, cleared after the set time), not left on the
 * clipboard for good. Pasting works as usual.
 *
 * It must be an [AndroidClipboard]: Compose's text toolbar (long press in a field) reads the system clipboard through
 * `nativeClipboardManager`, which throws for any other Clipboard.
 */
internal class SpaceClipboard(private val context: Context, private val platform: Clipboard) : AndroidClipboard {
    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text == null) platform.setClipEntry(clipEntry) else PrivateSpace.copySecret(context, text)
    }

    override val clipboardManager: android.content.ClipboardManager get() = platform.nativeClipboardManager
}

/**
 * Keyboards don't learn or keep history from anything typed on the page (Gboard and others type incognito). The flag
 * exists since Android 8; older keyboards may ignore it.
 */
internal val NoLearningTextInput = PlatformTextInputInterceptor { request, nextHandler ->
    val modified = PlatformTextInputMethodRequest { outAttributes ->
        request.createInputConnection(outAttributes).also {
            outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }
    nextHandler.startInputMethod(modified)
}

/**
 * File picker places on this phone. AntiGem has all-files access and agents in its terminal run as the app, so they can
 * read anything saved there (the phone's storage, Downloads, SD cards and USB drives while plugged in, AntiGem's and
 * Termux's own folders). Anything else (Google Drive, other cloud apps) is off the phone.
 */
private val PHONE_STORAGE_AUTHORITIES = setOf(
    "com.android.externalstorage.documents",
    "com.android.providers.downloads.documents",
    "com.android.providers.media.documents",
    "com.termux.documents"
)

internal fun isOnThisPhone(context: Context, uri: Uri): Boolean {
    val authority = uri.authority ?: return true
    return authority in PHONE_STORAGE_AUTHORITIES || authority == context.packageName || authority.startsWith(context.packageName + ".")
}

internal fun typeIcon(type: SpaceItemType): ImageVector = when (type) {
    SpaceItemType.LOGIN -> Icons.Outlined.Password
    SpaceItemType.API_KEY -> Icons.Outlined.VpnKey
    SpaceItemType.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
    SpaceItemType.SSH_KEY -> Icons.Outlined.Terminal
    SpaceItemType.SERVER -> Icons.Outlined.Dns
    SpaceItemType.CARD -> Icons.Outlined.CreditCard
    SpaceItemType.WIFI -> Icons.Outlined.Wifi
}

internal fun typeColor(type: SpaceItemType): Color = when (type) {
    SpaceItemType.LOGIN -> Color(0xFF3B82F6)
    SpaceItemType.API_KEY -> Color(0xFFF59E0B)
    SpaceItemType.NOTE -> Color(0xFF8B5CF6)
    SpaceItemType.SSH_KEY -> Color(0xFF10B981)
    SpaceItemType.SERVER -> Color(0xFF06B6D4)
    SpaceItemType.CARD -> Color(0xFFEC4899)
    SpaceItemType.WIFI -> Color(0xFF6366F1)
}

internal fun typeLabel(type: SpaceItemType): String = when (type) {
    SpaceItemType.LOGIN -> L.privateSpace.type.login
    SpaceItemType.API_KEY -> L.privateSpace.type.apiKey
    SpaceItemType.NOTE -> L.privateSpace.type.note
    SpaceItemType.SSH_KEY -> L.privateSpace.type.sshKey
    SpaceItemType.SERVER -> L.privateSpace.type.server
    SpaceItemType.CARD -> L.privateSpace.type.card
    SpaceItemType.WIFI -> L.privateSpace.type.wifi
}

/** A built-in field's label in the app's language (or null for an unknown key). */
internal fun builtInFieldLabel(key: String?): String? = when (key) {
    "username" -> L.privateSpace.field.username
    "password" -> L.privateSpace.field.password
    "website" -> L.privateSpace.field.website
    "totp" -> L.privateSpace.field.totp
    "service" -> L.privateSpace.field.service
    "key" -> L.privateSpace.field.key
    "envVar" -> L.privateSpace.field.envVar
    "expires" -> L.privateSpace.field.expires
    "host" -> L.privateSpace.field.host
    "port" -> L.privateSpace.field.port
    "database" -> L.privateSpace.field.database
    "privateKey" -> L.privateSpace.field.privateKey
    "publicKey" -> L.privateSpace.field.publicKey
    "passphrase" -> L.privateSpace.field.passphrase
    "cardholder" -> L.privateSpace.field.cardholder
    "cardNumber" -> L.privateSpace.field.cardNumber
    "expiry" -> L.privateSpace.field.expiry
    "cvv" -> L.privateSpace.field.cvv
    "cardPin" -> L.privateSpace.field.cardPin
    "network" -> L.privateSpace.field.network
    "security" -> L.privateSpace.field.security
    else -> null
}

internal fun fieldLabel(field: SpaceField): String =
    field.label.ifBlank { builtInFieldLabel(field.key) ?: L.privateSpace.field.unnamed }

internal fun historyLabel(stored: String): String = builtInFieldLabel(stored) ?: stored.ifBlank { L.privateSpace.field.unnamed }

internal fun kindLabel(kind: SpaceFieldKind): String = when (kind) {
    SpaceFieldKind.TEXT -> L.privateSpace.kind.text
    SpaceFieldKind.SECRET -> L.privateSpace.kind.secret
    SpaceFieldKind.URL -> L.privateSpace.kind.url
    SpaceFieldKind.MULTILINE -> L.privateSpace.kind.multiline
    SpaceFieldKind.SECRET_MULTILINE -> L.privateSpace.kind.secretMultiline
    SpaceFieldKind.TOTP -> L.privateSpace.kind.totp
}

internal fun relativeTime(ms: Long): String =
    if (System.currentTimeMillis() - ms < DateUtils.MINUTE_IN_MILLIS) L.common.justNow
    else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

internal fun dateTime(context: Context, ms: Long): String =
    DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)

internal fun dots(value: String): String = if (value.isEmpty()) "" else "•".repeat(value.length.coerceIn(8, 16))

@Composable
internal fun SpaceTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L.common.back) }
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }
            )
            actions()
        }
    }
}

/**
 * A password / secret input: dots until the eye is tapped; a password keyboard (no suggestions, no learning).
 */
@Composable
internal fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    autoFocus: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    trailingExtra: (@Composable () -> Unit)? = null,
    monospace: Boolean = false
) {
    var shown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        textStyle = LocalTextStyle.current.copy(fontFamily = if (monospace) FontFamily.Monospace else LocalTextStyle.current.fontFamily),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailingExtra?.invoke()
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (shown) L.privateSpace.common.hideValue else L.privateSpace.common.showValue
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth().focusRequester(focus).noAutofill()
    )
}

internal fun strengthLabel(score: Int): String = when (score) {
    0 -> L.privateSpace.strength.veryWeak
    1 -> L.privateSpace.strength.weak
    2 -> L.privateSpace.strength.fair
    3 -> L.privateSpace.strength.strong
    else -> L.privateSpace.strength.veryStrong
}

internal fun strengthColor(score: Int): Color = when (score) {
    0 -> Color(0xFFEF4444)
    1 -> Color(0xFFF97316)
    2 -> Color(0xFFEAB308)
    3 -> Color(0xFF22C55E)
    else -> Color(0xFF10B981)
}

@Composable
internal fun StrengthBar(password: String, modifier: Modifier = Modifier) {
    val s = remember(password) { PasswordTools.strength(password) }
    val fraction by animateFloatAsState(if (password.isEmpty()) 0f else (s.score + 1) / 5f, label = "strength")
    val color = strengthColor(s.score)
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        }
        if (password.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                L.privateSpace.strength.label(level = strengthLabel(s.score), bits = s.bits),
                fontSize = 12.sp,
                color = color,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/**
 * The PIN pad: AntiGem's own buttons, so no keyboard app ever sees the PIN. [length] dots (0 = unknown length).
 */
@Composable
internal fun PinPad(
    entered: Int,
    length: Int,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    enabled: Boolean = true,
    onFingerprint: (() -> Unit)? = null,
    onSubmit: (() -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(20.dp).semantics(mergeDescendants = true) {
                contentDescription = L.privateSpace.lock.pinDigitsEntered(entered)
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            val shown = if (length > 0) length else maxOf(entered, PasswordTools.PIN_MIN)
            repeat(shown) { i ->
                Box(
                    Modifier.size(14.dp).clip(CircleShape).background(
                        if (i < entered) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                    )
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'))
        rows.forEachIndexed { r, row ->
            Row {
                row.forEachIndexed { c, d -> PinKey(d.toString(), enabled, pinCell(c, r)) { onDigit(d) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                onFingerprint != null -> PinIconKey(Icons.Outlined.Fingerprint, L.privateSpace.lock.useFingerprint, enabled, pinCell(0, 3), onFingerprint)
                onSubmit != null -> PinIconKey(Icons.Outlined.Check, L.common.done, enabled && entered >= PasswordTools.PIN_MIN, pinCell(0, 3), onSubmit)
                else -> Spacer(Modifier.padding(pinCell(0, 3)).size(PIN_KEY_SIZE))
            }
            PinKey("0", enabled, pinCell(1, 3)) { onDigit('0') }
            PinIconKey(Icons.AutoMirrored.Outlined.Backspace, L.privateSpace.lock.deleteDigit, enabled && entered > 0, pinCell(2, 3), onBackspace)
        }
    }
}

private val PIN_KEY_SIZE = 72.dp

/**
 * The part of the gaps around key [col], [row] (4 rows of 3) that belongs to it: half of the 20dp gap left and right,
 * half of the 14dp gap above and below, none on the pad's outer edges. The pad looks the same as with plain gaps.
 */
private fun pinCell(col: Int, row: Int) = PaddingValues(
    start = if (col > 0) 10.dp else 0.dp,
    end = if (col < 2) 10.dp else 0.dp,
    top = if (row > 0) 7.dp else 0.dp,
    bottom = if (row < 3) 7.dp else 0.dp
)

/**
 * One key of the pad. It takes taps on its whole cell ([cell] = its share of the gaps), not only on the round key: a
 * round touch area left about half the pad dead (the gaps and the corners around each circle), so fast typing lost
 * digits. The press still shows on the round key.
 */
@Composable
private fun PinCell(enabled: Boolean, cell: PaddingValues, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick).padding(cell),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(PIN_KEY_SIZE).clip(CircleShape).indication(interaction, LocalIndication.current),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun PinKey(text: String, enabled: Boolean, cell: PaddingValues, onClick: () -> Unit) {
    PinCell(enabled, cell, onClick) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PinIconKey(icon: ImageVector, description: String, enabled: Boolean, cell: PaddingValues, onClick: () -> Unit) {
    PinCell(enabled, cell, onClick) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.8f else 0.3f))
    }
}

@Composable
internal fun BusyButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White),
        modifier = modifier.fillMaxWidth().height(50.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 16.sp)
    }
}

@Composable
internal fun InfoCard(icon: ImageVector, text: String, color: Color = MaterialTheme.colorScheme.primary, onDismiss: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.1f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = L.common.dismiss, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
internal fun BulletRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Icon(icon, null, tint = ClaudeTerracotta, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
    }
}
