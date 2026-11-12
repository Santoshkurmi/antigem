package com.example.gemini.ui.miniapps

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.gemini.data.miniapps.MiniApp
import com.example.gemini.data.miniapps.MiniAppConsole
import com.example.gemini.data.miniapps.MiniAppMcpAccess
import com.example.gemini.data.miniapps.MiniAppPermissions
import com.example.gemini.data.miniapps.MiniAppRuntime
import com.example.gemini.data.miniapps.MiniAppScheduler
import com.example.gemini.data.miniapps.MiniApps
import com.example.gemini.data.miniapps.MiniAppNet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MiniAppShortcuts {
    /** Home-screen shortcuts made before the launcher activity existed opened the root directly (losing the page): point them at it. */
    fun migrate(context: Context) = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 25) return@runCatching
        val sm = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        val stale = sm.pinnedShortcuts.filter { it.id.startsWith("miniapp:") && it.intent?.component?.className != MiniAppLaunchActivity::class.java.name }
        if (stale.isEmpty()) return@runCatching
        val updated = stale.mapNotNull { s ->
            val app = MiniApps.find(context, s.id.removePrefix("miniapp:")) ?: return@mapNotNull null
            ShortcutInfoCompat.Builder(context, s.id).setShortLabel(app.name)
                .setIcon(IconCompat.createWithBitmap(MiniApps.iconBitmap(app, 192)))
                .setIntent(MiniAppLaunchActivity.intent(context, app.id)).build()
        }
        ShortcutManagerCompat.updateShortcuts(context, updated)
    }
    fun addToHome(context: Context, app: MiniApp) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, "This launcher can't add shortcuts", Toast.LENGTH_SHORT).show(); return
        }
        val info = ShortcutInfoCompat.Builder(context, "miniapp:${app.id}")
            .setShortLabel(app.name)
            .setIcon(IconCompat.createWithBitmap(MiniApps.iconBitmap(app, 192)))
            .setIntent(MiniAppLaunchActivity.intent(context, app.id))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }
}

/** Shows one pending page dialog and completes its result. */
@Composable
fun NativeDialogHost(d: NativeDialog) {
    when (d) {
        is NativeDialog.Alert -> AlertDialog(
            onDismissRequest = { d.result.complete(null) },
            title = d.title.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            text = { Text(d.message) },
            confirmButton = { TextButton(onClick = { d.result.complete(null) }) { Text(d.ok) } },
        )
        is NativeDialog.Confirm -> AlertDialog(
            onDismissRequest = { d.result.complete(false) },
            title = d.title.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            text = d.message.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            confirmButton = {
                TextButton(onClick = { d.result.complete(true) }) {
                    Text(d.ok, color = if (d.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { d.result.complete(false) }) { Text(d.cancel) } },
        )
        is NativeDialog.Prompt -> {
            var value by remember(d) { mutableStateOf(d.value) }
            AlertDialog(
                onDismissRequest = { d.result.complete(null) },
                title = d.title.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                text = {
                    Column {
                        if (d.message.isNotBlank()) Text(d.message, modifier = Modifier.padding(bottom = 12.dp))
                        OutlinedTextField(
                            value = value, onValueChange = { value = it }, singleLine = true,
                            placeholder = d.placeholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { d.result.complete(value) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { d.result.complete(value) }) { Text(d.ok) } },
                dismissButton = { TextButton(onClick = { d.result.complete(null) }) { Text(d.cancel) } },
            )
        }
        is NativeDialog.Sheet -> ActionSheet(d.title, d.items, onDismiss = { d.result.complete(null) }) { d.result.complete(it) }
        is NativeDialog.Permissions -> PermissionDialog(d)
        is NativeDialog.TypedConfirm -> TypedConfirmDialog(d.app, d.name) { d.result.complete(it) }
        is NativeDialog.McpCall -> McpCallDialog(d)
        is NativeDialog.MediaViewer -> MediaViewerDialog(d)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, items: List<NativeDialog.SheetItem>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(item.id) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.icon.isNotBlank()) Text(item.icon, fontSize = 20.sp, modifier = Modifier.width(36.dp))
                    Text(item.label, style = MaterialTheme.typography.bodyLarge, color = if (item.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * Asking for permissions. Each one (each storage folder too) has its own switch; required ones have no "Deny"
 * that lets the app run; broad file access (all files, ~/, /sdcard) only turns on after the typed confirmation.
 */
@Composable
private fun PermissionDialog(d: NativeDialog.Permissions) {
    val context = LocalContext.current
    val app = d.app
    val danger = remember(d) { d.names.filter { MiniAppPermissions.needsTypedConfirm(context, app, it) }.toSet() }
    // broad access starts off (it needs the phrase); everything else starts on
    val choice = remember(d) { mutableStateMapOf<String, Boolean>().apply { d.names.forEach { put(it, it !in danger) } } }
    var confirming by remember(d) { mutableStateOf<String?>(null) }
    val icon = remember(d) { MiniApps.iconBitmap(app, 128).asImageBitmap() }
    val single = d.names.size == 1 && !d.firstLaunch
    val missingRequired = d.names.filter { it in app.required && choice[it] != true }
    AlertDialog(
        onDismissRequest = { d.result.complete(d.names.associateWith { false }) },
        icon = { Image(icon, null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))) },
        title = { Text(if (d.firstLaunch) "${app.name} wants to" else "Allow ${app.name} to use this?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                d.names.forEach { name ->
                    val info = MiniAppPermissions.describe(app, name)
                    val required = name in app.required
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (info.dangerous || name in danger) Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                                Text(info.title, style = MaterialTheme.typography.titleSmall)
                            }
                            if (info.detail.isNotBlank()) Text(info.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (required) Text("Required: the app won't open without it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (!single) Switch(checked = choice[name] == true, onCheckedChange = { on ->
                            if (on && name in danger) confirming = name else choice[name] = on
                        })
                    }
                }
                if (d.firstLaunch) Text("You can change these later in App info.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = single || missingRequired.isEmpty(),
                onClick = {
                    if (single) { if (d.names[0] in danger) confirming = d.names[0] else d.result.complete(mapOf(d.names[0] to true)) }
                    else d.result.complete(choice.toMap())
                },
            ) { Text(if (single) "Allow" else "Continue") }
        },
        dismissButton = {
            TextButton(onClick = { d.result.complete(d.names.associateWith { false }) }) {
                Text(if (d.names.any { it in app.required }) "Don't allow (app can't run)" else if (d.firstLaunch) "Deny all" else "Don't allow")
            }
        },
    )
    confirming?.let { name ->
        TypedConfirmDialog(app, name, onResult = { ok ->
            confirming = null
            if (ok) { choice[name] = true; if (single) d.result.complete(mapOf(name to true)) }
        })
    }
}

/** "Type iknowiamstupid": the last gate before an app gets broad file access. */
/** "<app> wants to use <tool>": what it is about to do, with the arguments, before it runs. */
@Composable
private fun McpCallDialog(d: NativeDialog.McpCall) {
    AlertDialog(
        onDismissRequest = { d.result.complete(null) },
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = if (MiniAppMcpAccess.isSensitive(d.server)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text("${d.app.name} wants to use ${d.tool}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("AntiGem tools: ${d.server}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.description.isNotBlank()) Text(d.description.take(300), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (d.args.isNotBlank() && d.args != "{}") {
                    Text("With", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    Text(d.args.take(2000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { d.result.complete("always") }) { Text("Always allow") }
                TextButton(onClick = { d.result.complete("once") }) { Text("Allow") }
            }
        },
        dismissButton = { TextButton(onClick = { d.result.complete(null) }) { Text("Deny") } },
    )
}

@Composable
fun TypedConfirmDialog(app: MiniApp, name: String, onResult: (Boolean) -> Unit) {
    if (name.startsWith("mcp:")) return McpTypedConfirmDialog(app, name, onResult)
    var typed by remember { mutableStateOf("") }
    val scope = if (name == "storage:all") "all your files (shared storage and your home folder)"
        else app.permissions[name]?.optString("path")?.let { "everything in $it" } ?: name.removePrefix("picked:").let { "everything in $it" }
    val access = when (MiniApps.normalizeAccess(app.permissions[name]?.optString("access") ?: app.storagePick)) {
        "write" -> "change or delete"; "readwrite" -> "read, change or delete"; else -> "read"
    }
    val ok = typed.trim().equals(MiniAppPermissions.DANGER_PHRASE, ignoreCase = true)
    AlertDialog(
        onDismissRequest = { onResult(false) },
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp)) },
        title = { Text("Give ${app.name} access to $scope?") },
        text = {
            Column {
                Text(
                    "${app.name} could $access $scope: photos, documents, downloads, other apps' exports, and could send them anywhere it can reach. " +
                        "A mistake or a bad app can delete or leak them. Only continue if you trust this app completely.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Type ${MiniAppPermissions.DANGER_PHRASE} to confirm:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    placeholder = { Text(MiniAppPermissions.DANGER_PHRASE, fontFamily = FontFamily.Monospace) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    isError = typed.isNotEmpty() && !ok,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onResult(true) }) { Text("Give access", color = if (ok) MaterialTheme.colorScheme.error else Color.Unspecified) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSheet(appId: String, onEval: (String, (String) -> Unit) -> Unit, onDevtools: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version by MiniAppConsole.version.collectAsState()
    val entries = remember(version) { MiniAppConsole.entries(appId) }
    val list = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) list.scrollToItem(entries.size - 1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Console", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDevtools) { Text("DevTools") }
                IconButton(onClick = { MiniAppConsole.clear(appId) }) { Icon(Icons.Outlined.DeleteSweep, "Clear") }
            }
            HorizontalDivider()
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 460.dp)) {
                items(entries) { e ->
                    val color = when (e.level) {
                        "error" -> MaterialTheme.colorScheme.error
                        "warn" -> Color(0xFFE0A100)
                        "result" -> MaterialTheme.colorScheme.primary
                        "info" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    val bg = when (e.level) { "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f); "warn" -> Color(0x14E0A100); else -> Color.Transparent }
                    Column(Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(e.message, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text(fmt.format(Date(e.time)) + if (e.source.isNotBlank()) "  ${e.source}" else "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    placeholder = { Text("Run JavaScript…", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { run(context, appId, input, onEval); input = "" }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { run(context, appId, input, onEval); input = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Run") }
            }
        }
    }
}

private fun run(context: Context, appId: String, code: String, onEval: (String, (String) -> Unit) -> Unit) {
    if (code.isBlank()) return
    MiniAppConsole.add(context, appId, "input", "› $code", "")
    onEval(code) { MiniAppConsole.add(context, appId, "result", it, "") }
}

/** App info: version, permissions (switches), data, home screen shortcut. Same sheet in the launcher and in the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoSheet(app: MiniApp, onDismiss: () -> Unit, onClearData: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmOn by remember { mutableStateOf<String?>(null) }
    var confirmOff by remember { mutableStateOf<String?>(null) }
    val states = remember(tick) { MiniAppPermissions.all(context, app) }
    val icon = remember(app) { MiniApps.iconBitmap(app, 160).asImageBitmap() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(icon, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(app.name, style = MaterialTheme.typography.titleLarge)
                    Text("${app.version} · ${app.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (app.signed) "Installed · signed by ${app.developer} · ${com.example.gemini.data.miniapps.MiniAppSigning.shortFingerprint(app.signer.orEmpty())}"
                        else "Development app (unsigned) · ~/mini-apps/${app.folder.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (app.tampered) MaterialTheme.colorScheme.error else if (app.signed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (app.description.isNotBlank()) Text(app.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))

            Text("Permissions", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            if (states.isEmpty()) Text("This app asks for no permissions. It can only use its own storage.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            states.forEach { (name, state) ->
                val info = MiniAppPermissions.describe(app, name)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.title, style = MaterialTheme.typography.bodyLarge, color = if (info.dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(info.detail.ifBlank { state.js }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state == MiniAppPermissions.State.GRANTED, onCheckedChange = { on ->
                        when {
                            on && MiniAppPermissions.needsTypedConfirm(context, app, name) -> confirmOn = name
                            !on && name in app.required -> confirmOff = name
                            else -> { MiniAppPermissions.set(context, app, name, on); tick++ }
                        }
                    })
                }
                if (name in app.required) Text("Required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            val picked = remember(tick) { MiniAppPermissions.pickedFolders(context, app) }
            if (picked.isNotEmpty()) {
                Text("Folders you picked", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                picked.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.optString("path"), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            Text(MiniAppPermissions.accessText(f.optString("access")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { MiniAppPermissions.removePickedFolder(context, app, f.optString("path")); tick++ }) { Text("Remove") }
                    }
                }
            }

            // AntiGem tools (MCP): whether each call needs the user's OK first
            val mcpServers = remember(app) { MiniAppMcpAccess.servers(app) }
            if (mcpServers.isNotEmpty()) {
                Text("AntiGem tools", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                mcpServers.forEach { sv ->
                    val ask = remember(tick) { MiniAppMcpAccess.askSwitch(context, app, sv) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Ask before each call: $sv", style = MaterialTheme.typography.bodyLarge)
                            Text(if (MiniAppMcpAccess.isSensitive(sv)) "Recommended: these tools can act on your phone" else "You see each call and its details first",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = ask, onCheckedChange = { MiniAppMcpAccess.setAsk(context, app, sv, it); tick++ })
                    }
                }
            }

            BackgroundSection(app, tick) { tick++ }

            Text("Storage", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            Text((if (app.signed) "Code: installed by AntiGem (read-only, checked against its signature)" else "Code: ~/mini-apps/${app.folder.name}") + "\nData: ~/.antigem/mini-apps/${app.id}",
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            app.sharedId?.let { sid ->
                val group = remember(app) { MiniApps.sharedGroup(context, app, fresh = true) }
                Text(
                    "Shared data (\"$sid\"): " + if (group.isEmpty()) "no other app in this group yet" else
                        group.joinToString { it.name } + if (app.signed) ", same developer: each can read and change the other's files" else ", development apps",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Clear data") }
                OutlinedButton(onClick = { MiniAppPermissions.reset(context, app); tick++ }) { Text("Reset permissions") }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { MiniAppShortcuts.addToHome(context, app) }) { Text("Add to home screen") }
                if (app.signed) OutlinedButton(onClick = { confirmUninstall = true }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                else OutlinedButton(enabled = !exporting, onClick = {
                    if (!com.example.gemini.data.miniapps.MiniAppSigning.hasKey(context)) {
                        Toast.makeText(context, "Create a developer key first (Mini Apps → key icon)", Toast.LENGTH_LONG).show(); return@OutlinedButton
                    }
                    exporting = true
                    scope.launch {
                        runCatching { exportSignedPackage(context, app) }
                            .onSuccess { f -> Toast.makeText(context, "Signed: ~/mini-apps-dist/${f.name}", Toast.LENGTH_LONG).show(); MiniAppPackages.sharePackage(context, f) }
                            .onFailure { Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_LONG).show() }
                        exporting = false
                    }
                }) { Text(if (exporting) "Signing…" else "Export signed package") }
            }
            // an installed app is shared as the exact package it was installed from (still the developer's signature)
            if (app.signed) Row(Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = {
                    val f = runCatching { com.example.gemini.data.miniapps.MiniAppInstaller.shareablePackage(context, app) }.getOrNull()
                    if (f == null) Toast.makeText(context, "The package isn't kept for apps installed before sharing existed: install it again to share it", Toast.LENGTH_LONG).show()
                    else MiniAppPackages.sharePackage(context, f)
                }) { Text("Share app") }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
    confirmOn?.let { name -> TypedConfirmDialog(app, name) { ok -> if (ok) MiniAppPermissions.set(context, app, name, true); confirmOn = null; tick++ } }
    confirmOff?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmOff = null },
            title = { Text("${app.name} needs this") },
            text = { Text("\"${MiniAppPermissions.describe(app, name).title}\" is required: without it ${app.name} won't open. Turn it off anyway?") },
            confirmButton = { TextButton(onClick = { MiniAppPermissions.set(context, app, name, false); confirmOff = null; tick++ }) { Text("Turn off", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmOff = null }) { Text("Keep") } },
        )
    }
    if (confirmUninstall) AlertDialog(
        onDismissRequest = { confirmUninstall = false },
        title = { Text("Uninstall ${app.name}?") },
        text = { Text("The app and all its data (storage, files, reminders, background tasks, notifications) are removed.") },
        confirmButton = {
            TextButton(onClick = {
                confirmUninstall = false
                runCatching { com.example.gemini.data.miniapps.MiniAppInstaller.uninstall(context, app) }
                    .onSuccess { Toast.makeText(context, "${app.name} uninstalled", Toast.LENGTH_SHORT).show(); onDismiss() }
                    .onFailure { Toast.makeText(context, it.message ?: "Couldn't uninstall", Toast.LENGTH_LONG).show() }
            }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear ${app.name}'s data?") },
        text = { Text("Its storage, databases, files and logs are deleted. The app itself stays.") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                MiniAppNet.forgetApp(app.id)
                MiniAppRuntime.clearData(context, app)
                Toast.makeText(context, "Data cleared", Toast.LENGTH_SHORT).show()
                onClearData()
            }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

/** Reminders and background tasks of the app: what is scheduled, how the last runs went, "Run now". */
@Composable
private fun BackgroundSection(app: MiniApp, tick: Int, refresh: () -> Unit) {
    val context = LocalContext.current
    val data by androidx.compose.runtime.produceState<Triple<org.json.JSONArray, org.json.JSONArray, Int>?>(null, tick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Triple(MiniAppScheduler.listReminders(context, app.id), MiniAppScheduler.listTasks(context, app.id), MiniAppScheduler.runsLastDay(context, app.id)) }.getOrNull()
        }
    }
    val (reminders, tasks, runs) = data ?: return
    if (reminders.length() == 0 && tasks.length() == 0 && app.backgroundScript == null) return
    val time = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    Text("Background", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    if (reminders.length() > 0) {
        val next = reminders.optJSONObject(0)?.optLong("at") ?: 0L
        Text("${reminders.length()} reminder${if (reminders.length() == 1) "" else "s"} scheduled · next ${time.format(Date(next))}", style = MaterialTheme.typography.bodyMedium)
    }
    for (i in 0 until tasks.length()) {
        val t = tasks.getJSONObject(i)
        val last = t.optJSONObject("lastRun")
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${t.optString("name")} · ${t.optString("schedule")}", style = MaterialTheme.typography.bodyLarge)
                val lastText = if (last == null) "Not run yet" else
                    "Last: ${time.format(Date(last.optLong("at")))} · ${last.optString("status")}" + (if (last.optString("status") == "ok") " in ${last.optLong("ms")} ms" else "") +
                        last.optString("detail").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Text(lastText, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                    color = if (last?.optString("status") == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {
                MiniAppScheduler.runNow(context, app.id, t.optString("name"), "manual")
                Toast.makeText(context, "Running ${t.optString("name")}…", Toast.LENGTH_SHORT).show()
            }) { Text("Run now") }
        }
    }
    if ((reminders.length() > 0 || tasks.length() > 0) && !MiniAppScheduler.canExact(context)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reminders may come late: AntiGem has no \"Alarms & reminders\" access.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))) }
                MiniAppScheduler.rearmAsync(context)
                refresh()
            }) { Text("Fix") }
        }
    }
    if (tasks.length() > 0 || app.backgroundScript != null) {
        Text("$runs of ${MiniAppScheduler.MAX_RUNS_PER_DAY} background runs used in the last 24 h", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val pm = context.getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Android may delay or skip background tasks while AntiGem's battery use is optimized.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                @android.annotation.SuppressLint("BatteryLife")
                val allow = {
                    runCatching {
                        context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}")))
                    }
                    refresh()
                }
                TextButton(onClick = allow) { Text("Allow") }
            }
        }
    }
}

@Composable
fun AppIcon(app: MiniApp, size: Int) {
    val bmp = remember(app.id, app.iconFile?.lastModified()) { MiniApps.iconBitmap(app, 192).asImageBitmap() }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.26f).dp))) { Image(bmp, app.name, Modifier.size(size.dp)) }
}

/** The typed gate for tools that can run commands, drive the phone or read the browser. */
@Composable
private fun McpTypedConfirmDialog(app: MiniApp, name: String, onResult: (Boolean) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val server = name.removePrefix("mcp:").substringBefore('/')
    val tool = name.substringAfter('/', "")
    val what = if (tool.isNotEmpty()) "the $tool tool of $server" else "$server"
    val ok = typed.trim().equals(MiniAppPermissions.DANGER_PHRASE, ignoreCase = true)
    AlertDialog(
        onDismissRequest = { onResult(false) },
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp)) },
        title = { Text("Let ${app.name} use $what?") },
        text = {
            Column {
                Text(
                    "Through $server an app can run commands, tap and type on your phone, or read and act in the browser, depending on the tool. " +
                        "A mistake or a bad app can delete things, send messages or leak what's on screen. Only continue if you trust this app completely. " +
                        "By default AntiGem still asks you before each call (App info → Ask before each call).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Type ${MiniAppPermissions.DANGER_PHRASE} to confirm:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(MiniAppPermissions.DANGER_PHRASE, fontFamily = FontFamily.Monospace) })
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onResult(true) }) { Text("Allow", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Cancel") } },
    )
}
