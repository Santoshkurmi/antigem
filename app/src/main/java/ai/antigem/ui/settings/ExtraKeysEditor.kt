package ai.antigem.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.local.ExtraKeySpec
import ai.antigem.data.local.TerminalExtraKeys
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * Settings → Terminal → Extra keys and shortcuts: the terminal's keys above the keyboard (any number of rows, any keys,
 * Termux's swipe-up popups, labels and macros) and the CTRL shortcuts for sessions. Saved in Termux's format, so a
 * termux.properties can be pasted in and the result copied back out. Every change applies to the terminal at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtraKeysEditorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AuthPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val defaultRows = remember { TerminalExtraKeys.parseOrDefault(null) }
    var rows by remember { mutableStateOf(TerminalExtraKeys.parseOrDefault(prefs.getTerminalExtraKeysSync())) }
    var shortcuts by remember { mutableStateOf(TerminalExtraKeys.parseShortcuts(prefs.getTerminalSessionShortcutsSync())) }

    fun saveRows(new: List<List<ExtraKeySpec>>) {
        val cleaned = new.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) {
            AppToastHelper.showToast(L.settings.extraKeys.keepAtLeastOneKey, ChatToastType.INFO)
            return
        }
        rows = cleaned
        scope.launch { prefs.saveTerminalExtraKeys(if (cleaned == defaultRows) "" else TerminalExtraKeys.toJson(cleaned)) }
    }

    fun saveShortcuts(new: Map<String, String>) {
        shortcuts = new
        scope.launch {
            prefs.saveTerminalSessionShortcuts(
                if (new == TerminalExtraKeys.DEFAULT_SHORTCUTS) "" else TerminalExtraKeys.shortcutsJson(new)
            )
        }
    }

    // the key being edited: row, index (index == row size: a new key; row == rows size: a new row)
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var importing by remember { mutableStateOf(false) }
    var askReset by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L.common.back) }
                    Text(L.settings.extraKeys.extraKeysAndShortcuts, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, L.settings.extraKeys.moreOptions) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(L.settings.extraKeys.importFromTermux) }, onClick = { menuOpen = false; importing = true })
                            DropdownMenuItem(text = { Text(L.settings.extraKeys.copyAsTermuxSettings) }, onClick = {
                                menuOpen = false
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("termux.properties", TerminalExtraKeys.exportText(rows, shortcuts)))
                                AppToastHelper.showToast(L.settings.extraKeys.copiedPasteItIntoTermux, ChatToastType.SUCCESS)
                            })
                            DropdownMenuItem(text = { Text(L.settings.extraKeys.resetToDefault) }, onClick = { menuOpen = false; askReset = true })
                        }
                    }
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(L.settings.extraKeys.extraKeys, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(L.settings.extraKeys.tapAKeyToChangeIt, fontSize = 12.5.sp, color = subtle())

                    rows.forEachIndexed { r, row ->
                        var rowMenu by remember(r) { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // the row as the terminal shows it
                            Row(
                                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(8.dp)).background(AppColors.terminalBg).padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                row.forEachIndexed { i, spec ->
                                    KeyCell(
                                        label = TerminalExtraKeys.label(spec),
                                        popup = spec.popup?.let { TerminalExtraKeys.label(it) },
                                        modifier = Modifier.weight(1f)
                                    ) { editing = r to i }
                                }
                                Box(
                                    Modifier.width(30.dp).height(38.dp).clip(RoundedCornerShape(5.dp)).clickable(
                                        onClickLabel = L.settings.extraKeys.addAKey, role = Role.Button
                                    ) { editing = r to row.size },
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Filled.Add, L.settings.extraKeys.addAKey, tint = keyTextColor().copy(alpha = 0.7f), modifier = Modifier.size(18.dp)) }
                            }
                            Box {
                                IconButton(onClick = { rowMenu = true }) { Icon(Icons.Filled.MoreVert, L.settings.extraKeys.rowNumber(r + 1)) }
                                DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                    if (r > 0) DropdownMenuItem(text = { Text(L.settings.extraKeys.moveUp) }, onClick = {
                                        rowMenu = false
                                        saveRows(rows.toMutableList().apply { add(r - 1, removeAt(r)) })
                                    })
                                    if (r < rows.size - 1) DropdownMenuItem(text = { Text(L.settings.extraKeys.moveDown) }, onClick = {
                                        rowMenu = false
                                        saveRows(rows.toMutableList().apply { add(r + 1, removeAt(r)) })
                                    })
                                    DropdownMenuItem(text = { Text(L.settings.extraKeys.deleteRow) }, onClick = {
                                        rowMenu = false
                                        saveRows(rows.toMutableList().apply { removeAt(r) })
                                    })
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { editing = rows.size to 0 }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L.settings.extraKeys.addRow)
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(L.settings.extraKeys.sessionShortcuts, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(L.settings.extraKeys.pressCtrlThenTheKey, fontSize = 12.5.sp, color = subtle())
                    TerminalExtraKeys.SHORTCUT_ACTIONS.forEach { action ->
                        val key = shortcuts[action].orEmpty()
                        val clash = key.isNotEmpty() && TerminalExtraKeys.SHORTCUT_ACTIONS.any { it != action && shortcuts[it] == key }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(shortcutName(action), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                if (clash) Text(L.settings.extraKeys.thisKeyIsUsedTwice, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
                            }
                            Text(L.settings.extraKeys.ctrlPlus, fontSize = 13.sp, color = subtle())
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(
                                value = key,
                                onValueChange = { v ->
                                    // the last character typed (one key per shortcut)
                                    val last = if (v.isEmpty()) "" else String(Character.toChars(v.codePointBefore(v.length)))
                                    saveShortcuts(shortcuts + (action to TerminalExtraKeys.cleanShortcutKey(last)))
                                },
                                singleLine = true,
                                placeholder = { Text("–", color = subtle()) },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                                modifier = Modifier.width(64.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    editing?.let { (r, i) ->
        val newRow = r >= rows.size
        val row = rows.getOrNull(r).orEmpty()
        val existing = row.getOrNull(i)
        KeyEditDialog(
            initial = existing,
            onDismiss = { editing = null },
            onSave = { spec ->
                editing = null
                saveRows(
                    if (newRow) rows + listOf(listOf(spec))
                    else rows.mapIndexed { ri, rr -> if (ri != r) rr else if (existing != null) rr.toMutableList().apply { set(i, spec) } else rr + spec }
                )
            },
            onDelete = existing?.let {
                {
                    editing = null
                    saveRows(rows.mapIndexed { ri, rr -> if (ri == r) rr.toMutableList().apply { removeAt(i) } else rr })
                }
            },
            onMove = existing?.let {
                { dir: Int ->
                    val j = i + dir
                    if (j in row.indices) {
                        saveRows(rows.mapIndexed { ri, rr -> if (ri == r) rr.toMutableList().apply { add(j, removeAt(i)) } else rr })
                        editing = r to j
                    }
                }
            },
            canMoveLeft = existing != null && i > 0,
            canMoveRight = existing != null && i < row.size - 1
        )
    }

    if (importing) {
        ImportDialog(onDismiss = { importing = false }) { imported ->
            importing = false
            imported.rows?.let { saveRows(it) }
            imported.shortcuts?.let { saveShortcuts(shortcuts + it) }
            AppToastHelper.showToast(L.settings.extraKeys.imported, ChatToastType.SUCCESS)
        }
    }

    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text(L.settings.extraKeys.resetToDefault, fontSize = 18.sp) },
            text = { Text(L.settings.extraKeys.resetTheKeysAndShortcutsTo) },
            confirmButton = {
                TextButton(onClick = {
                    askReset = false
                    saveRows(defaultRows)
                    saveShortcuts(TerminalExtraKeys.DEFAULT_SHORTCUTS)
                }) { Text(L.settings.extraKeys.reset) }
            },
            dismissButton = { TextButton(onClick = { askReset = false }) { Text(L.common.cancel) } }
        )
    }
}

/** One key of the preview, as on the terminal's bar (its swipe-up key small in the corner). */
@Composable
private fun KeyCell(label: String, popup: String?, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(38.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.06f))
            .clickable(onClickLabel = L.settings.extraKeys.changeTheKey, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (popup != null) {
            Text(popup, fontSize = 9.sp, color = keyTextColor().copy(alpha = 0.55f), maxLines = 1, modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = keyTextColor(), maxLines = 1, softWrap = false)
    }
}

/** Like the terminal's keys: white on a dark terminal theme. */
private fun keyTextColor(): Color = if (AppColors.terminalBg.luminance() < 0.5f) Color.White else AppColors.editorText

@Composable
private fun subtle() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

private fun shortcutName(action: String): String = when (action) {
    "create" -> L.settings.extraKeys.newSession
    "next" -> L.settings.extraKeys.nextSession
    "previous" -> L.settings.extraKeys.previousSession
    "rename" -> L.settings.extraKeys.renameSession
    "drawer" -> L.settings.extraKeys.openTheSessionsMenu
    else -> action
}

/** A key as the user typed it: a key name in any case ("esc", "page up") becomes the name, anything else is text. */
private fun keyFromInput(input: String): String {
    val upper = TerminalExtraKeys.normalize(input.trim().uppercase())
    return if (input.isNotBlank() && TerminalExtraKeys.isNamed(upper)) upper else TerminalExtraKeys.normalize(input)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyEditDialog(
    initial: ExtraKeySpec?,
    onDismiss: () -> Unit,
    onSave: (ExtraKeySpec) -> Unit,
    onDelete: (() -> Unit)?,
    onMove: ((Int) -> Unit)?,
    canMoveLeft: Boolean,
    canMoveRight: Boolean
) {
    var key by remember { mutableStateOf(initial?.key.orEmpty()) }
    var macro by remember { mutableStateOf(initial?.macro == true) }
    var popup by remember { mutableStateOf(initial?.popup?.key.orEmpty()) }
    var display by remember { mutableStateOf(initial?.display.orEmpty()) }
    // which field the key buttons below fill
    var pickInto by remember { mutableStateOf("key") }
    val groups = listOf(
        L.settings.extraKeys.modifiers to TerminalExtraKeys.MODIFIERS,
        L.settings.extraKeys.actions to TerminalExtraKeys.ACTIONS,
        L.settings.extraKeys.navigation to TerminalExtraKeys.NAVIGATION,
        L.settings.extraKeys.editing to TerminalExtraKeys.EDITING,
        L.settings.extraKeys.functionKeys to TerminalExtraKeys.FUNCTION
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) L.settings.extraKeys.addAKey else L.settings.extraKeys.changeTheKey, fontSize = 18.sp) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (macro) L.settings.extraKeys.keysInARow else L.settings.extraKeys.keyOrText) },
                    supportingText = { Text(if (macro) L.settings.extraKeys.keysSeparatedBySpacesCtrl else L.settings.extraKeys.pickAKeyBelowOrType) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().clickableFocus { pickInto = "key" }
                )
                Row(
                    Modifier.fillMaxWidth().toggleable(value = macro, role = Role.Checkbox) { macro = it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = macro, onCheckedChange = null)
                    Spacer(Modifier.width(6.dp))
                    Text(L.settings.extraKeys.severalKeysInARowMacro, fontSize = 13.5.sp)
                }
                OutlinedTextField(
                    value = popup,
                    onValueChange = { popup = it },
                    label = { Text(L.settings.extraKeys.swipeUpOptional) },
                    supportingText = { Text(L.settings.extraKeys.whatASwipeUpOnThe) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().clickableFocus { pickInto = "popup" }
                )
                OutlinedTextField(
                    value = display,
                    onValueChange = { display = it },
                    label = { Text(L.settings.extraKeys.labelOptional) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (pickInto == "popup") L.settings.extraKeys.keysForSwipeUp else L.settings.extraKeys.keys,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                groups.forEach { (title, names) ->
                    Text(title, fontSize = 12.sp, color = subtle())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        names.forEach { name ->
                            val label = TerminalExtraKeys.labelOf(name)
                            val text = when (name) {
                                "DRAWER" -> "$label ${L.components.a11y.keySessionsMenu}"
                                "KEYBOARD" -> "$label ${L.components.a11y.keyKeyboard}"
                                "PASTE" -> "$label ${L.components.a11y.keyPaste}"
                                else -> if (label != name) "$label $name" else name
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                                    when {
                                        pickInto == "popup" -> popup = name
                                        macro -> key = (key.trim() + " " + name).trim()
                                        else -> key = name
                                    }
                                }
                            ) { Text(text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                        }
                    }
                }
                if (onMove != null || onDelete != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onMove != null) {
                            IconButton(onClick = { onMove(-1) }, enabled = canMoveLeft) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, L.settings.extraKeys.moveLeft) }
                            IconButton(onClick = { onMove(1) }, enabled = canMoveRight) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, L.settings.extraKeys.moveRight) }
                        }
                        Spacer(Modifier.weight(1f))
                        if (onDelete != null) TextButton(onClick = onDelete) { Text(L.common.delete, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = key.isNotBlank(),
                onClick = {
                    val k = if (macro) key.trim().replace(Regex("\\s+"), " ") else keyFromInput(key)
                    val p = popup.takeIf { it.isNotBlank() }?.let { ExtraKeySpec(keyFromInput(it)) }
                    onSave(ExtraKeySpec(k, p, display.takeIf { it.isNotBlank() }, macro))
                }
            ) { Text(L.common.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/** Notes which text field was used last, so the key buttons fill that one. */
private fun Modifier.clickableFocus(onFocus: () -> Unit): Modifier = this.onFocusChanged { if (it.isFocused) onFocus() }

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (TerminalExtraKeys.Imported) -> Unit) {
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.settings.extraKeys.importFromTermux, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.settings.extraKeys.pasteYourTermuxPropertiesOr, fontSize = 13.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; problem = null },
                    placeholder = { Text("extra-keys = [['ESC','/','-','HOME','UP','END','PGUP'],['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]", fontSize = 12.sp) },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                problem?.let { Text(it, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                runCatching { TerminalExtraKeys.importText(text) }
                    .onSuccess(onImport)
                    .onFailure { problem = L.settings.extraKeys.couldNotReadThatMessage(it.message ?: it.toString()) }
            }) { Text(L.settings.extraKeys.importButton) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}
