package ai.antigem.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.i18n.L
import ai.antigem.i18n.LanguageManager
import ai.antigem.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Language names offered for "AI replies in" (sent to the agents as written). */
private val COMMON_LANGUAGES get() = listOf(
    L.settings.language.hindi, L.settings.language.nepali, L.settings.language.bengali, L.settings.language.urdu, L.settings.language.spanish, L.settings.language.portuguese, L.settings.language.french, L.settings.language.german, L.settings.language.italian, L.settings.language.russian,
    L.settings.language.arabic, L.settings.language.turkish, L.settings.language.indonesian, L.settings.language.chinese, L.settings.language.japanese, L.settings.language.korean
)

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

private fun clipboard(context: Context) = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

/**
 * Settings → Language: which language the app shows, the user's own languages (new, edit, import, export, share),
 * the language agents reply in, and how to have an agent translate the app through language_mcp.
 */
@Composable
fun LanguageScreen(onOpenTexts: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val languages = LanguageManager.languages
    val current = LanguageManager.currentId
    var loading by remember { mutableStateOf(languages.isEmpty()) }
    var switching by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editingInfo by remember { mutableStateOf<LanguageManager.Info?>(null) }
    var deleting by remember { mutableStateOf<LanguageManager.Info?>(null) }
    var imported by remember { mutableStateOf<Pair<LanguageManager.Language?, LanguageManager.Report>?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var importMenu by remember { mutableStateOf(false) }

    suspend fun refresh() {
        withContext(Dispatchers.IO) { runCatching { LanguageManager.refreshList() } }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    fun select(id: String) {
        if (id == current || switching != null) return
        switching = id
        scope.launch {
            val ok = withContext(Dispatchers.IO) { LanguageManager.apply(id) }
            switching = null
            if (!ok) toast(context, L.settings.language.couldNotLoad(languages.firstOrNull { it.id == id }?.name ?: id))
        }
    }

    fun importText(text: String?) {
        if (text.isNullOrBlank()) { toast(context, L.settings.language.nothingToImport); return }
        scope.launch {
            imported = withContext(Dispatchers.IO) { LanguageManager.import(text) }
            refresh()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId
        exportId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = LanguageManager.export(id) ?: return@runCatching false
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } != null
                }.getOrDefault(false)
            }
            toast(context, if (ok) L.settings.language.savedToFile else L.settings.language.couldNotSave)
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            importText(text)
        }
    }

    fun share(info: LanguageManager.Info) {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val json = LanguageManager.export(info.id) ?: return@runCatching null
                    val f = File(context.cacheDir, "share/antigem-language-${info.id}.json")
                    f.parentFile?.mkdirs()
                    f.writeText(json)
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
                }.getOrNull()
            }
            if (uri == null) { toast(context, L.settings.language.couldNotSave); return@launch }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, L.settings.language.shareSubject(info.label))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, L.settings.language.share).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun copyJson(info: LanguageManager.Info) {
        scope.launch {
            val json = withContext(Dispatchers.IO) { LanguageManager.export(info.id) }
            if (json == null) { toast(context, L.settings.language.couldNotSave); return@launch }
            runCatching { clipboard(context).setPrimaryClip(ClipData.newPlainText("AntiGem language", json)) }
                .onSuccess { toast(context, L.settings.language.copied) }
                .onFailure { toast(context, L.settings.language.tooBigToCopy) }
        }
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(L.settings.language.appLanguage, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(L.settings.language.appLanguageAbout, fontSize = 12.5.sp, lineHeight = 17.sp, color = muted)

        if (loading && languages.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        }
        languages.forEach { info ->
            LanguageRow(
                info = info,
                selected = info.id == current,
                busy = switching == info.id,
                onSelect = { select(info.id) },
                onOpenTexts = { onOpenTexts(info.id) },
                onDuplicate = {
                    scope.launch {
                        val copy = withContext(Dispatchers.IO) { LanguageManager.duplicate(info.id) }
                        refresh()
                        copy?.let { onOpenTexts(it.id) }
                    }
                },
                onDetails = { editingInfo = info },
                onSave = { exportId = info.id; saveLauncher.launch(L.settings.language.antigemLanguageIdJson(info.id)) },
                onShare = { share(info) },
                onCopy = { copyJson(info) },
                onDelete = { deleting = info }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { creating = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(L.settings.language.newLanguage, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { importMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(L.settings.language.importLanguage, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(L.settings.language.importFromFile) },
                        leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                        onClick = { importMenu = false; openLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                    )
                    DropdownMenuItem(
                        text = { Text(L.settings.language.importFromClipboard) },
                        leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                        onClick = {
                            importMenu = false
                            importText(runCatching { clipboard(context).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull())
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        AiReplyCard()
        TranslateWithAiCard()

        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = muted)
            Spacer(Modifier.width(8.dp))
            Text(L.settings.language.shareNote, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (creating) {
        LanguageInfoDialog(
            title = L.settings.language.newLanguage,
            confirm = L.settings.language.create,
            languages = languages,
            onDismiss = { creating = false },
            onConfirm = { code, name, native, rtl, from, _, _ ->
                creating = false
                scope.launch {
                    val lang = withContext(Dispatchers.IO) { LanguageManager.create(code, name, native, rtl, from) }
                    refresh()
                    onOpenTexts(lang.id)
                }
            }
        )
    }
    editingInfo?.let { info ->
        LanguageInfoDialog(
            title = L.settings.language.nameAndDetails,
            confirm = L.common.save,
            languages = null,
            initial = info,
            onDismiss = { editingInfo = null },
            onConfirm = { code, name, native, rtl, _, author, about ->
                editingInfo = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LanguageManager.update(info.id, emptyMap(), name = name, nativeName = native, code = code, rtl = rtl, author = author, about = about)
                    }
                    refresh()
                }
            }
        )
    }
    deleting?.let { info ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(L.settings.language.deleteTitle(info.name)) },
            text = { Text(L.settings.language.deleteText) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { withContext(Dispatchers.IO) { LanguageManager.delete(info.id) }; refresh() }
                }) { Text(L.common.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(L.common.cancel) } }
        )
    }
    imported?.let { (lang, report) ->
        ImportResultDialog(
            lang = lang,
            report = report,
            onUse = {
                imported = null
                lang?.let { l -> select(l.id) }
            },
            onDismiss = { imported = null }
        )
    }
}

@Composable
private fun LanguageRow(
    info: LanguageManager.Info,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onOpenTexts: () -> Unit,
    onDuplicate: () -> Unit,
    onDetails: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val english = info.id == LanguageManager.ENGLISH
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.5.dp, primary) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(info.code.uppercase().take(5), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = primary, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (info.nativeName.isNotBlank() && info.nativeName != info.name) {
                        Text("  ${info.nativeName}", fontSize = 13.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    when {
                        english -> L.settings.language.englishSubtitle
                        info.builtIn -> L.settings.language.builtInProgress(info.translated, info.total)
                        else -> L.settings.language.yoursProgress(info.translated, info.total)
                    },
                    fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (!english) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (info.total == 0) 0f else info.translated.toFloat() / info.total },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = if (info.translated == info.total) ai.antigem.theme.AppColors.success else primary,
                        trackColor = primary.copy(alpha = 0.12f),
                        drawStopIndicator = {}
                    )
                }
                if (info.outdated > 0) {
                    Spacer(Modifier.height(6.dp))
                    SmallChip(L.settings.language.outdatedCount(info.outdated), ai.antigem.theme.AppColors.warning)
                }
            }
            Spacer(Modifier.width(6.dp))
            when {
                busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                selected -> Icon(Icons.Outlined.CheckCircle, L.settings.language.onScreen, tint = primary)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, L.settings.language.more) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (info.builtIn) L.settings.language.viewTexts else L.settings.language.editTexts) },
                        leadingIcon = { Icon(if (info.builtIn) Icons.Outlined.Visibility else Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onOpenTexts() }
                    )
                    if (!english) DropdownMenuItem(
                        text = { Text(L.settings.language.duplicate) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() }
                    )
                    if (!info.builtIn) DropdownMenuItem(
                        text = { Text(L.settings.language.nameAndDetails) },
                        leadingIcon = { Icon(Icons.Outlined.Translate, null) },
                        onClick = { menu = false; onDetails() }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (english) L.settings.language.saveTemplate else L.settings.language.saveToFile) },
                        leadingIcon = { Icon(Icons.Outlined.Save, null) },
                        onClick = { menu = false; onSave() }
                    )
                    DropdownMenuItem(
                        text = { Text(L.settings.language.share) },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = { menu = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(L.settings.language.copyJson) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menu = false; onCopy() }
                    )
                    if (!info.builtIn) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(L.common.delete, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, color: Color) {
    Text(
        label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** "AI replies in": off, the app's language, or a language the user names. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AiReplyCard() {
    val saved = LanguageManager.aiReply
    var choice by remember { mutableIntStateOf(when (saved) { "" -> 0; LanguageManager.AI_REPLY_APP -> 1; else -> 2 }) }
    var custom by remember { mutableStateOf(if (choice == 2) saved else "") }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(L.settings.language.aiReplies, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(L.settings.language.aiRepliesAbout, fontSize = 12.5.sp, lineHeight = 17.sp, color = muted)
            Spacer(Modifier.height(10.dp))
            val options = listOf(L.settings.language.aiOff, L.settings.language.aiApp, L.settings.language.aiOther)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = choice == i,
                        onClick = {
                            choice = i
                            LanguageManager.chooseAiReply(when (i) { 0 -> ""; 1 -> LanguageManager.AI_REPLY_APP; else -> custom })
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size)
                    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
                }
            }
            if (choice == 2) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it; LanguageManager.chooseAiReply(it) },
                    singleLine = true,
                    label = { Text(L.settings.language.aiLanguageLabel) },
                    placeholder = { Text(L.settings.language.aiLanguageHint) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    COMMON_LANGUAGES.forEach { name ->
                        FilterChip(
                            selected = custom.equals(name, true),
                            onClick = { custom = name; LanguageManager.chooseAiReply(name) },
                            label = { Text(name, fontSize = 12.sp) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val now = LanguageManager.aiReplyLanguage()
            Text(
                when {
                    now != null -> L.settings.language.aiNow(now)
                    choice == 1 -> L.settings.language.aiAppEnglish
                    else -> L.settings.language.aiNotSet
                },
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = if (now != null) MaterialTheme.colorScheme.primary else muted
            )
        }
    }
}

/** How to have an agent translate the app: the Language MCP server and a ready prompt. */
@Composable
private fun TranslateWithAiCard() {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Translate, null, tint = ai.antigem.theme.AppColors.accentTeal, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(L.settings.language.translateWithAi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(L.settings.language.translateWithAiAbout, fontSize = 12.5.sp, lineHeight = 17.sp, color = muted)
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = {
                runCatching { clipboard(context).setPrimaryClip(ClipData.newPlainText("prompt", L.settings.language.aiPrompt)) }
                toast(context, L.settings.language.promptCopied)
            }) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(L.settings.language.copyPrompt)
            }
        }
    }
}

/** New language, or the name and details of one. [languages]: offer "start from" (new languages only). */
@Composable
private fun LanguageInfoDialog(
    title: String,
    confirm: String,
    languages: List<LanguageManager.Info>?,
    initial: LanguageManager.Info? = null,
    onDismiss: () -> Unit,
    onConfirm: (code: String, name: String, nativeName: String, rtl: Boolean, from: String?, author: String, about: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var native by remember { mutableStateOf(initial?.nativeName.orEmpty()) }
    var code by remember { mutableStateOf(initial?.code.orEmpty()) }
    var rtl by remember { mutableStateOf(initial?.rtl ?: false) }
    var author by remember { mutableStateOf(initial?.author.orEmpty()) }
    var about by remember { mutableStateOf(initial?.about.orEmpty()) }
    var from by remember { mutableStateOf<LanguageManager.Info?>(null) }
    var fromMenu by remember { mutableStateOf(false) }
    val valid = name.isNotBlank() && code.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.bouncyVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(L.settings.language.nameLabel) }, placeholder = { Text(L.settings.language.nameHint) })
                OutlinedTextField(native, { native = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(L.settings.language.nativeNameLabel) }, placeholder = { Text(L.settings.language.nativeNameHint) })
                OutlinedTextField(code, { code = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(L.settings.language.codeLabel) }, placeholder = { Text(L.settings.language.codeHint) },
                    supportingText = { Text(L.settings.language.codeAbout) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.settings.language.rtl, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = rtl, onCheckedChange = { rtl = it })
                }
                if (languages != null) {
                    Text(L.settings.language.startFrom, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Box {
                        OutlinedButton(onClick = { fromMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(from?.let { L.settings.language.copyOf(it.label) } ?: L.settings.language.startEmpty,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        DropdownMenu(expanded = fromMenu, onDismissRequest = { fromMenu = false }) {
                            DropdownMenuItem(text = { Text(L.settings.language.startEmpty) }, onClick = { from = null; fromMenu = false })
                            languages.filter { it.id != LanguageManager.ENGLISH }.forEach { l ->
                                DropdownMenuItem(text = { Text(L.settings.language.copyOf(l.label)) }, onClick = {
                                    from = l; fromMenu = false
                                    if (name.isBlank()) name = l.name
                                    if (native.isBlank()) native = l.nativeName
                                    if (code.isBlank()) code = l.code
                                    rtl = l.rtl
                                })
                            }
                        }
                    }
                } else {
                    OutlinedTextField(author, { author = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(L.settings.language.authorLabel) })
                    OutlinedTextField(about, { about = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(L.settings.language.aboutLabel) })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(code.trim(), name.trim(), native.trim(), rtl, from?.id, author.trim(), about.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

@Composable
private fun ImportResultDialog(lang: LanguageManager.Language?, report: LanguageManager.Report, onUse: () -> Unit, onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (lang != null) L.settings.language.importedTitle(lang.label) else L.settings.language.notALanguage, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.bouncyVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lang == null) {
                    Text(report.problems.joinToString("\n"), fontSize = 13.sp, color = muted)
                } else {
                    Text(L.settings.language.importedTexts(report.saved), fontSize = 14.sp)
                    if (report.unknownKeys.isNotEmpty()) Text(L.settings.language.unknownKept(report.unknownKeys.size), fontSize = 13.sp, color = muted)
                    if (report.problems.isNotEmpty()) {
                        Text(L.settings.language.refused(report.problems.size), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        SelectionContainer {
                            Text(report.problems.take(30).joinToString("\n"), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (lang != null) TextButton(onClick = onUse) { Text(L.settings.language.useNow) }
            else TextButton(onClick = onDismiss) { Text(L.common.ok) }
        },
        dismissButton = { if (lang != null) TextButton(onClick = onDismiss) { Text(L.settings.language.later) } }
    )
}

// ============================================================ the texts of one language

private enum class TextFilter { ALL, MISSING, OUTDATED, TRANSLATED }

/** Edits are saved in the background, one key at a time after a short pause, and all at once when the page closes. */
private object TextSaver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = LinkedHashMap<String, Any?>()
    private var languageId: String? = null
    private var job: Job? = null
    /** Called on the main thread after a save with the language as it now is. */
    var onSaved: ((LanguageManager.Language?) -> Unit)? = null

    @Synchronized
    fun put(id: String, key: String, value: Any?) {
        if (languageId != null && languageId != id) flushNow()
        languageId = id
        pending[key] = value
        job?.cancel()
        job = scope.launch { delay(700); flush() }
    }

    private fun flush() {
        val (id, batch) = synchronized(this) {
            val id = languageId ?: return
            val b = LinkedHashMap(pending)
            pending.clear()
            id to b
        }
        if (batch.isEmpty()) return
        val report = LanguageManager.update(id, batch, redraw = false)
        if (report.problems.isNotEmpty() || report.unknownKeys.isNotEmpty())
            android.util.Log.w("LanguageTexts", "not saved: ${report.problems + report.unknownKeys}")
        val lang = LanguageManager.load(id)
        scope.launch(Dispatchers.Main) { onSaved?.invoke(lang) }
    }

    @Synchronized
    private fun flushNow() {
        job?.cancel()
        flush()
    }

    /** The page closed: save what is left, then redraw the screens in the edited language. */
    fun close() {
        scope.launch {
            synchronized(this@TextSaver) { job?.cancel() }
            flush()
            LanguageManager.flushRedraw()
        }
    }
}

/**
 * Settings → Language → the texts of one language: every key with its English and the translation, filtered by
 * status, part of the app or a search. Built-in languages and English are read-only (duplicate to change).
 */
@Composable
fun LanguageTextsScreen(id: String, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<LanguageManager.Source?>(null) }
    var lang by remember(id) { mutableStateOf<LanguageManager.Language?>(null) }
    // the list is filtered from the language as it was when the filter was set, so a row doesn't vanish while typing
    var base by remember(id) { mutableStateOf<LanguageManager.Language?>(null) }
    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val s = LanguageManager.source()
            val l = LanguageManager.load(id)
            src = s; lang = l; base = l
        }
    }
    DisposableEffect(id) {
        TextSaver.onSaved = { l -> if (l?.id == id) lang = l }
        onDispose { TextSaver.onSaved = null; TextSaver.close() }
    }
    val s = src
    val l = lang
    val b = base
    if (s == null || l == null || b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        return
    }
    val editable = !l.builtIn
    val english = l.id == LanguageManager.ENGLISH
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(if (editable) TextFilter.MISSING else TextFilter.ALL) }
    var area by remember { mutableStateOf<String?>(null) }
    var areaMenu by remember { mutableStateOf(false) }
    val areas = remember(s) { s.keys.groupingBy { it.substringBefore('.') }.eachCount() }
    val rows = remember(s, b, query, filter, area) {
        val q = query.trim()
        s.keys.indices.filter { i ->
            val key = s.keys[i]
            (area == null || key.startsWith("$area.")) &&
                (q.isEmpty() || key.contains(q, true) || s.text(i).contains(q, true) || b.strings[key]?.toString()?.contains(q, true) == true) &&
                (english || when (filter) {
                    TextFilter.ALL -> true
                    TextFilter.MISSING -> LanguageManager.status(b, s, i) == LanguageManager.Status.MISSING
                    TextFilter.OUTDATED -> LanguageManager.status(b, s, i) == LanguageManager.Status.OUTDATED
                    TextFilter.TRANSLATED -> LanguageManager.status(b, s, i) == LanguageManager.Status.TRANSLATED
                })
        }
    }
    val info = remember(l, s) { LanguageManager.info(l, s) }
    val forms = remember(l.code) { LanguageManager.pluralForms(l.code) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(l.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (!english) {
                    Text(
                        L.settings.languageTexts.progress(info.translated, info.total) +
                            (if (info.outdated > 0) "  ·  " + L.settings.language.outdatedCount(info.outdated) else ""),
                        fontSize = 12.5.sp, color = muted
                    )
                    LinearProgressIndicator(
                        progress = { if (info.total == 0) 0f else info.translated.toFloat() / info.total },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        trackColor = primary.copy(alpha = 0.12f),
                        drawStopIndicator = {}
                    )
                } else {
                    Text(L.settings.languageTexts.englishAbout(info.total), fontSize = 12.5.sp, color = muted)
                }
                if (!editable && !english) {
                    Surface(shape = RoundedCornerShape(12.dp), color = ai.antigem.theme.AppColors.info.copy(alpha = 0.12f)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(L.settings.languageTexts.builtInReadOnly(l.name), fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    val copy = withContext(Dispatchers.IO) { LanguageManager.duplicate(l.id) }
                                    copy?.let { onOpen(it.id) }
                                }
                            }) { Text(L.settings.languageTexts.duplicateToEdit) }
                        }
                    }
                }
                if (editable) Text(L.settings.languageTexts.placeholdersNote, fontSize = 11.5.sp, lineHeight = 15.sp, color = muted)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(L.settings.languageTexts.search) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!english) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                TextFilter.MISSING to L.settings.languageTexts.filterMissing,
                                TextFilter.OUTDATED to L.settings.languageTexts.filterOutdated,
                                TextFilter.TRANSLATED to L.settings.languageTexts.filterTranslated,
                                TextFilter.ALL to L.settings.languageTexts.filterAll
                            ).forEach { (f, label) ->
                                FilterChip(selected = filter == f, onClick = { filter = f; base = lang }, label = { Text(label, fontSize = 12.sp) })
                            }
                        }
                    } else Spacer(Modifier.weight(1f))
                    Box {
                        AssistChip(
                            onClick = { areaMenu = true },
                            label = { Text(area ?: L.settings.languageTexts.allParts, fontSize = 12.sp, maxLines = 1) },
                            leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(16.dp)) }
                        )
                        DropdownMenu(expanded = areaMenu, onDismissRequest = { areaMenu = false }) {
                            DropdownMenuItem(text = { Text(L.settings.languageTexts.allParts) }, onClick = { area = null; areaMenu = false })
                            areas.forEach { (a, n) ->
                                DropdownMenuItem(text = { Text("$a  ·  $n") }, onClick = { area = a; areaMenu = false; base = lang })
                            }
                        }
                    }
                }
                Text(L.settings.languageTexts.shown(rows.size), fontSize = 11.5.sp, color = muted)
            }
        }
        if (rows.isEmpty()) item(key = "empty") {
            Text(L.settings.languageTexts.nothingHere, fontSize = 13.sp, color = muted, modifier = Modifier.padding(vertical = 24.dp))
        }
        items(rows, key = { s.keys[it] }) { i ->
            TextRow(s, l, i, editable, english, forms)
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TextRow(
    s: LanguageManager.Source,
    lang: LanguageManager.Language,
    i: Int,
    editable: Boolean,
    english: Boolean,
    forms: List<String>
) {
    val key = s.keys[i]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val status = LanguageManager.status(lang, s, i)
    val statusColor = when (status) {
        LanguageManager.Status.TRANSLATED -> ai.antigem.theme.AppColors.success
        LanguageManager.Status.OUTDATED -> ai.antigem.theme.AppColors.warning
        LanguageManager.Status.MISSING -> muted.copy(alpha = 0.5f)
    }
    val plural = s.isPlural(i)
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!english) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor))
                if (!english) Spacer(Modifier.width(6.dp))
                Text(key, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (status == LanguageManager.Status.OUTDATED) SmallChip(L.settings.languageTexts.englishChanged, ai.antigem.theme.AppColors.warning)
            }
            SelectionContainer {
                Text(
                    if (plural) (s.values[i] as Map<*, *>).entries.joinToString("\n") { "${it.key}: ${it.value}" } else s.text(i),
                    fontSize = 13.5.sp, lineHeight = 18.sp
                )
            }
            s.notes[key]?.let { Text(it, fontSize = 11.5.sp, color = muted) }
            if (english) return@Column
            if (!editable) {
                val v = lang.strings[key]
                Text(
                    when (v) {
                        null -> L.settings.languageTexts.notTranslated
                        is Map<*, *> -> v.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        else -> v.toString()
                    },
                    fontSize = 13.5.sp, lineHeight = 18.sp, color = if (v == null) muted else MaterialTheme.colorScheme.primary
                )
                return@Column
            }
            if (plural) {
                @Suppress("UNCHECKED_CAST")
                val start = (lang.strings[key] as? Map<String, String>).orEmpty()
                val values = remember(key) { mutableStateMapOf<String, String>().apply { forms.forEach { f -> put(f, start[f].orEmpty()) } } }
                forms.forEach { f ->
                    OutlinedTextField(
                        value = values[f].orEmpty(),
                        onValueChange = { t ->
                            values[f] = t
                            val map = values.filterValues { it.isNotEmpty() }
                            if (map.isEmpty() || LanguageManager.checkText(s, i, map) == null) TextSaver.put(lang.id, key, map.ifEmpty { null })
                        },
                        label = { Text(f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val map = values.filterValues { it.isNotEmpty() }
                if (map.isNotEmpty()) Problem(s, i, map)
            } else {
                var text by remember(key) { mutableStateOf(lang.strings[key] as? String ?: "") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { t ->
                        text = t
                        if (t.isEmpty() || LanguageManager.checkText(s, i, t) == null) TextSaver.put(lang.id, key, t.ifEmpty { null })
                    },
                    placeholder = { Text(L.settings.languageTexts.translationHint, color = muted.copy(alpha = 0.6f)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (text.isNotEmpty()) Problem(s, i, text)
            }
        }
    }
}

/** Under a field: a text that can't be saved (unknown placeholder), or one that leaves a placeholder out. */
@Composable
private fun Problem(s: LanguageManager.Source, i: Int, value: Any) {
    val bad = LanguageManager.placeholdersOf(value) - s.placeholders(i)
    val missing = LanguageManager.missingPlaceholders(s, i, value)
    when {
        LanguageManager.checkText(s, i, value) != null -> Text(
            L.settings.languageTexts.notSaved(bad.joinToString(" ") { "{$it}" }),
            fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error
        )
        missing.isNotEmpty() -> Text(
            L.settings.languageTexts.leavesOut(missing.joinToString(" ") { "{$it}" }),
            fontSize = 11.5.sp, color = ai.antigem.theme.AppColors.warning
        )
    }
}
