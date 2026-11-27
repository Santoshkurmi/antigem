package ai.antigem.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.i18n.L
import ai.antigem.i18n.LanguageManager
import ai.antigem.i18n.LanguageRepo
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.mergedIntoRow
import ai.antigem.ui.components.rememberBouncyLazyScroll
import ai.antigem.ui.components.toggleRowSemantics
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

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** What went wrong with the community list or a download, in the app's language. */
internal fun repoErrorText(e: Throwable?): String = when ((e as? LanguageRepo.RepoException)?.kind) {
    LanguageRepo.Kind.NOT_PUBLISHED -> L.settings.language.errNotPublished
    LanguageRepo.Kind.BAD_DATA -> L.settings.language.errBadData
    LanguageRepo.Kind.NEWER_APP -> L.settings.language.errNewerApp
    LanguageRepo.Kind.CHANGED -> L.settings.language.errChanged
    LanguageRepo.Kind.TOO_BIG -> L.settings.language.errTooBig
    else -> L.settings.language.errOffline
}

/** "by Sita, Ram +2". */
internal fun byLine(authors: List<LanguageManager.Author>): String? = authors.takeIf { it.isNotEmpty() }?.let { list ->
    L.settings.language.byAuthors(list.take(3).joinToString(", ") { it.name } + if (list.size > 3) " +${list.size - 3}" else "")
}

internal fun shortDate(day: String): String = runCatching {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(day) ?: return day
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(d)
}.getOrDefault(day)

/**
 * Settings → Language: which language the app shows; community languages to download (and their updates) from the
 * antigem_languages repository; the user's own languages (new, edit, import, export, share, send to the community);
 * the language agents reply in, and how to have an agent translate the app through language_mcp.
 * Downloaded and built-in languages are read-only: duplicate one to change it, so an update never overwrites edits.
 *
 * A lazy list (only what's on screen is drawn). Its lists are read ahead when Settings opens ([LanguageRepo.prewarm]),
 * so the page opens complete; the community list is fetched again once the page has slid in.
 */
@Composable
fun LanguageScreen(onOpenTexts: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val languages = LanguageManager.languages
    val current = LanguageManager.currentId
    var loading by remember { mutableStateOf(languages.isEmpty()) }
    // the spinner only when reading the list takes a while: normally Settings has read it already
    var slowLoad by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editingInfo by remember { mutableStateOf<LanguageManager.Info?>(null) }
    var deleting by remember { mutableStateOf<LanguageManager.Info?>(null) }
    var imported by remember { mutableStateOf<Pair<LanguageManager.Language?, LanguageManager.Report>?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var importMenu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<LanguageDetails?>(null) }
    var sending by remember { mutableStateOf<LanguageManager.Info?>(null) }
    var ready by remember { mutableStateOf<LanguageManager.Language?>(null) }
    var query by remember { mutableStateOf("") }

    suspend fun refresh() {
        withContext(Dispatchers.IO) { runCatching { LanguageManager.refreshList() } }
        loading = false
    }
    LaunchedEffect(Unit) {
        launch { refresh() }
        launch { delay(300); slowLoad = true }
        // once the page has slid in: the community list is fetched each time the page opens (that's when updates show
        // up), unless it was fetched moments ago (the check on launch, or the last visit)
        delay(400)
        LanguageRepo.refresh(maxAgeMs = 2 * 60_000L)
    }

    fun select(id: String) {
        if (id == current || switching != null) return
        switching = id
        scope.launch {
            val ok = withContext(Dispatchers.IO) { LanguageManager.apply(id) }
            switching = null
            if (!ok) toast(context, L.settings.language.couldNotLoad(languages.firstOrNull { it.id == id }?.name ?: id))
        }
    }

    fun download(pack: LanguageRepo.Pack, fromSheet: Boolean = false) {
        val wasInstalled = LanguageRepo.stateOf(pack.id) != LanguageRepo.State.AVAILABLE
        scope.launch {
            LanguageRepo.install(pack)
                .onSuccess { lang ->
                    refresh()
                    when {
                        wasInstalled -> toast(context, L.settings.language.updatedToast(lang.label))
                        // the open sheet now offers "Use" itself
                        fromSheet && details != null -> toast(context, L.settings.language.downloadedToast(lang.label))
                        LanguageManager.currentId != lang.id -> ready = lang
                        else -> toast(context, L.settings.language.downloadedToast(lang.label))
                    }
                }
                .onFailure { toast(context, repoErrorText(it)) }
        }
    }

    fun updateAll() {
        scope.launch {
            var failed: Throwable? = null
            LanguageRepo.updates().forEach { p -> LanguageRepo.install(p).onFailure { failed = it } }
            refresh()
            toast(context, failed?.let { repoErrorText(it) } ?: L.settings.language.allUpdated)
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

    fun saveFile(info: LanguageManager.Info) {
        exportId = info.id
        saveLauncher.launch(L.settings.language.antigemLanguageIdJson(info.id))
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

    fun duplicate(id: String) {
        scope.launch {
            val copy = withContext(Dispatchers.IO) { LanguageManager.duplicate(id) }
            refresh()
            copy?.let { onOpenTexts(it.id) }
        }
    }

    val packs = LanguageRepo.packs
    val states = LanguageRepo.states
    val error = LanguageRepo.error
    val loadingList = LanguageRepo.loading
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // the community list offers what isn't on the phone yet: downloaded ones are in the list above, with their updates
    val available = remember(packs, states) { packs.filter { (states[it.id] ?: LanguageRepo.State.AVAILABLE) == LanguageRepo.State.AVAILABLE } }
    val shown = remember(available, query) {
        val q = query.trim()
        if (q.isEmpty()) available else available.filter { p ->
            listOf(p.name, p.nativeName, p.code, p.variant).any { it.contains(q, true) } || p.authors.any { it.name.contains(q, true) || it.github.contains(q, true) }
        }
    }
    val updates = remember(packs, states) { packs.count { states[it.id] == LanguageRepo.State.UPDATE } }
    // nothing known yet: "Looking for languages…" from the first frame until the list is in, instead of a blank first
    val checking = packs.isEmpty() && error == null && (loadingList || LanguageRepo.fetchedAt == 0L)
    val communityNote = when {
        checking -> null
        packs.isEmpty() && error == null -> L.settings.language.noLanguagesYet
        packs.isNotEmpty() && available.isEmpty() -> L.settings.language.allInstalled
        available.isNotEmpty() && shown.isEmpty() -> L.settings.language.noMatch(query.trim())
        else -> null
    }

    val listState = rememberLazyListState()
    val bounce = rememberBouncyLazyScroll(listState)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().then(bounce.modifier),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = bounce.userScrollEnabled,
        overscrollEffect = bounce.overscrollEffect
    ) {
        item(key = "head", contentType = "head") {
            SectionTitle(Icons.Outlined.Translate, L.settings.language.appLanguage, L.settings.language.appLanguageAbout)
        }
        if (languages.isEmpty()) item(key = "loading", contentType = "loading") {
            // the height of a row, so the page doesn't jump when the list comes in
            Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
                if (loading && slowLoad) CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
        items(languages, key = { "lang:${it.id}" }, contentType = { "language" }) { info ->
            val update = if (info.origin == LanguageManager.Origin.REPO && states[info.packId] == LanguageRepo.State.UPDATE)
                packs.firstOrNull { it.id == info.packId } else null
            LanguageRow(
                info = info,
                selected = info.id == current,
                busy = switching == info.id || info.packId in LanguageRepo.downloading,
                update = update,
                onSelect = { select(info.id) },
                onUpdate = { update?.let { download(it) } },
                onAbout = { details = LanguageDetails(info = info, pack = packs.firstOrNull { it.id == info.packId && info.packId.isNotBlank() }) },
                onOpenTexts = { onOpenTexts(info.id) },
                onDuplicate = { duplicate(info.id) },
                onDetails = { editingInfo = info },
                onSave = { saveFile(info) },
                onShare = { share(info) },
                onCopy = { copyJson(info) },
                onSend = { sending = info },
                onDelete = { deleting = info },
                modifier = Modifier.animateItem()
            )
        }
        item(key = "actions", contentType = "actions") {
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
        }

        item(key = "community", contentType = "community") {
            CommunityHeader(
                updates = updates,
                checking = checking,
                showSearch = available.size > 6,
                query = query,
                onQuery = { query = it },
                onUpdateAll = ::updateAll
            )
        }
        items(shown, key = { "pack:${it.id}" }, contentType = { "pack" }) { p ->
            PackRow(
                pack = p,
                busy = p.id in LanguageRepo.downloading,
                onDownload = { download(p) },
                onClick = { details = LanguageDetails(info = null, pack = p) },
                modifier = Modifier.animateItem()
            )
        }
        communityNote?.let { note ->
            item(key = "community-note", contentType = "note") {
                Text(note, fontSize = 13.sp, lineHeight = 18.sp, color = muted, modifier = Modifier.padding(horizontal = 4.dp).animateItem())
            }
        }
        item(key = "community-links", contentType = "links") { CommunityLinks() }

        item(key = "ai", contentType = "ai") { Box(Modifier.padding(top = 8.dp)) { AiReplyCard() } }
        item(key = "translate", contentType = "translate") { TranslateWithAiCard() }
        item(key = "note", contentType = "note") {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = muted)
                Spacer(Modifier.width(8.dp))
                Text(L.settings.language.shareNote, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
            }
        }
    }

    if (creating) {
        LanguageInfoDialog(
            title = L.settings.language.newLanguage,
            confirm = L.settings.language.create,
            languages = languages,
            onDismiss = { creating = false },
            onConfirm = { r ->
                creating = false
                scope.launch {
                    val lang = withContext(Dispatchers.IO) { LanguageManager.create(r.code, r.name, r.nativeName, r.rtl, r.from, variant = r.variant) }
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
            onConfirm = { r ->
                editingInfo = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LanguageManager.update(
                            info.id, emptyMap(), name = r.name, nativeName = r.nativeName, code = r.code, rtl = r.rtl,
                            variant = r.variant, description = r.description, authors = r.authors
                        )
                    }
                    refresh()
                    // back to the send dialog when the names were added from there
                    if (sending?.id == info.id) sending = LanguageManager.languages.firstOrNull { it.id == info.id }
                }
            }
        )
    }
    deleting?.let { info ->
        val community = info.origin == LanguageManager.Origin.REPO
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (community) L.settings.language.removeTitle(info.label) else L.settings.language.deleteTitle(info.name)) },
            text = { Text(if (community) L.settings.language.removeText else L.settings.language.deleteText) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            LanguageManager.delete(info.id)
                            if (community) LanguageRepo.removed(info.packId)
                        }
                        refresh()
                    }
                }) { Text(if (community) L.common.remove else L.common.delete, color = MaterialTheme.colorScheme.error) }
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
    ready?.let { lang ->
        AlertDialog(
            onDismissRequest = { ready = null },
            icon = { Icon(Icons.Outlined.CheckCircle, null, tint = ai.antigem.theme.AppColors.success) },
            title = { Text(L.settings.language.readyTitle(lang.label), fontWeight = FontWeight.SemiBold) },
            text = { lang.authors.takeIf { it.isNotEmpty() }?.let { a -> Text(L.settings.language.readyThanks(a.joinToString(", ") { it.name }), fontSize = 13.5.sp) } },
            confirmButton = { TextButton(onClick = { ready = null; select(lang.id) }) { Text(L.settings.language.useNow) } },
            dismissButton = { TextButton(onClick = { ready = null }) { Text(L.settings.language.later) } }
        )
    }
    details?.let { d ->
        // the installed language as it is now: after a download from the sheet it's there, with "Use"
        val localId = d.info?.id ?: d.pack?.localId
        val info = languages.firstOrNull { it.id == localId } ?: d.info
        val pack = d.pack ?: info?.packId?.takeIf { it.isNotBlank() }?.let { id -> packs.firstOrNull { it.id == id } }
        LanguageDetailsSheet(
            details = LanguageDetails(info = info, pack = pack),
            onDismiss = { details = null },
            onDownload = { p -> download(p, fromSheet = true) },
            onUse = { id -> details = null; select(id) },
            onDuplicate = { id -> details = null; duplicate(id) },
            onRemove = { i -> details = null; deleting = i },
            onSend = { i -> details = null; sending = i }
        )
    }
    // hidden while its "Add names" dialog is open, then back with the names
    if (editingInfo == null) sending?.let { info ->
        SendToCommunityDialog(
            info = info,
            basedOn = info.basedOn.takeIf { it.isNotBlank() }?.let { b -> packs.firstOrNull { it.id == b }?.label ?: b },
            onAddNames = { editingInfo = info },
            onSave = { saveFile(info) },
            onShare = { share(info) },
            onOpenForm = {
                openUrl(context, LanguageRepo.SHARE_FORM + "&title=" + android.net.Uri.encode("Translation: ${LanguageManager.labelOf(info.name, info.nativeName, info.variant)} (${info.code})"))
            },
            onGuide = { openUrl(context, LanguageRepo.CONTRIBUTING) },
            onDismiss = { sending = null }
        )
    }
}

/** A section's title: an icon, the title (a heading for TalkBack), what's in it, and anything at its end. */
@Composable
private fun SectionTitle(icon: ImageVector, title: String, about: String?, trailing: @Composable RowScope.() -> Unit = {}) {
    val primary = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = primary, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            trailing()
        }
        if (about != null) {
            Spacer(Modifier.height(2.dp))
            Text(about, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LanguageRow(
    info: LanguageManager.Info,
    selected: Boolean,
    busy: Boolean,
    update: LanguageRepo.Pack?,
    onSelect: () -> Unit,
    onUpdate: () -> Unit,
    onAbout: () -> Unit,
    onOpenTexts: () -> Unit,
    onDuplicate: () -> Unit,
    onDetails: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val english = info.id == LanguageManager.ENGLISH
    val community = info.origin == LanguageManager.Origin.REPO
    var menu by remember { mutableStateOf(false) }
    // the chosen row fades into its colors instead of switching in one frame
    val background by animateColorAsState(
        if (selected) primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surfaceContainerLow, label = "languageRowBackground"
    )
    val edge by animateColorAsState(if (selected) primary else primary.copy(alpha = 0f), label = "languageRowEdge")
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        color = background,
        border = BorderStroke(1.5.dp, edge),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CodeBadge(info.code)
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
                        community -> L.settings.language.communityProgress(info.translated, info.total)
                        info.origin == LanguageManager.Origin.APP -> L.settings.language.builtInProgress(info.translated, info.total)
                        else -> L.settings.language.yoursProgress(info.translated, info.total)
                    },
                    fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                byLine(info.authors)?.let { Text(it, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                if (info.variant.isNotBlank() || info.outdated > 0 || update != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (info.variant.isNotBlank()) SmallChip(info.variant, primary)
                        if (info.outdated > 0) SmallChip(L.settings.language.outdatedCount(info.outdated), ai.antigem.theme.AppColors.warning)
                        if (update != null) SmallChip(L.settings.language.updateAvailable, ai.antigem.theme.AppColors.info)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // one slot for the row's state, the same width on every row: the texts don't move when it changes
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    update != null -> IconButton(onClick = onUpdate) { Icon(Icons.Outlined.Update, L.common.update, tint = ai.antigem.theme.AppColors.info) }
                    selected -> Icon(Icons.Outlined.CheckCircle, L.settings.language.onScreen, tint = primary)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, L.settings.language.more) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!english) DropdownMenuItem(
                        text = { Text(L.settings.language.aboutLanguage) },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        onClick = { menu = false; onAbout() }
                    )
                    if (update != null) DropdownMenuItem(
                        text = { Text(L.common.update) },
                        leadingIcon = { Icon(Icons.Outlined.Update, null) },
                        onClick = { menu = false; onUpdate() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (info.readOnly) L.settings.language.viewTexts else L.settings.language.editTexts) },
                        leadingIcon = { Icon(if (info.readOnly) Icons.Outlined.Visibility else Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onOpenTexts() }
                    )
                    if (!english) DropdownMenuItem(
                        text = { Text(L.settings.language.duplicate) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() }
                    )
                    if (!info.readOnly) DropdownMenuItem(
                        text = { Text(L.settings.language.nameAndDetails) },
                        leadingIcon = { Icon(Icons.Outlined.Translate, null) },
                        onClick = { menu = false; onDetails() }
                    )
                    HorizontalDivider()
                    if (!info.readOnly) DropdownMenuItem(
                        text = { Text(L.settings.language.sendToCommunity) },
                        leadingIcon = { Icon(Icons.Outlined.Upload, null) },
                        onClick = { menu = false; onSend() }
                    )
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
                    if (info.origin == LanguageManager.Origin.USER || community) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (community) L.common.remove else L.common.delete, color = MaterialTheme.colorScheme.error) },
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
internal fun CodeBadge(code: String, size: androidx.compose.ui.unit.Dp = 42.dp) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Text(code.uppercase().take(5), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = primary, maxLines = 1)
    }
}

// ============================================================ community languages

/**
 * The head of "Get more languages": title with refresh, what it is, why the list couldn't be fetched, the updates
 * waiting, "Looking for languages…" before the first list, and a search once the list grows. The packs follow as
 * rows of their own. The list is fetched when the page opens ([LanguageRepo.refresh]).
 */
@Composable
private fun CommunityHeader(
    updates: Int,
    checking: Boolean,
    showSearch: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onUpdateAll: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val packs = LanguageRepo.packs
    val error = LanguageRepo.error
    val loadingList = LanguageRepo.loading
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // a banner coming or going slides the rows below instead of making them jump
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(Icons.Outlined.Public, L.settings.language.getMore, L.settings.language.getMoreAbout) {
            // the same size while loading, so the title doesn't move
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (loadingList) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { scope.launch { LanguageRepo.refresh() } }) { Icon(Icons.Outlined.Refresh, L.common.refresh) }
            }
        }

        // kept while a new try runs (its Retry waits), so it doesn't blink away and back
        if (error != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        repoErrorText(error) + if (packs.isNotEmpty()) "\n" + L.settings.language.showingSaved else "",
                        fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { scope.launch { LanguageRepo.refresh() } }, enabled = !loadingList) { Text(L.common.retry) }
                }
            }
        }

        if (updates > 0) {
            Surface(shape = RoundedCornerShape(12.dp), color = ai.antigem.theme.AppColors.info.copy(alpha = 0.12f)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Update, null, tint = ai.antigem.theme.AppColors.info, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(L.settings.language.updatesReady(updates), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onUpdateAll, enabled = LanguageRepo.downloading.isEmpty()) { Text(L.settings.language.updateAll) }
                }
            }
        }

        if (checking) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(L.settings.language.checking, fontSize = 13.sp, color = muted)
            }
        }

        if (showSearch) {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, null) } },
                placeholder = { Text(L.settings.language.searchLanguages) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Under the community list: add a language, ask for one, the repository. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommunityLinks() {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { openUrl(context, LanguageRepo.CONTRIBUTING) }) {
            Icon(Icons.Outlined.Translate, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(L.settings.language.addYourLanguage)
        }
        TextButton(onClick = { openUrl(context, LanguageRepo.REQUEST_FORM) }) {
            Text(L.settings.language.askForLanguage)
        }
        TextButton(onClick = { openUrl(context, LanguageRepo.PAGE) }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(L.settings.language.viewOnGitHub)
        }
    }
}

/** A community language that isn't on the phone yet: tap for its details, or download it. */
@Composable
private fun PackRow(
    pack: LanguageRepo.Pack,
    busy: Boolean,
    onDownload: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CodeBadge(pack.code, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pack.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (pack.nativeName.isNotBlank() && pack.nativeName != pack.name) {
                        Text("  ${pack.nativeName}", fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pack.variant.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    SmallChip(pack.variant, primary)
                }
                byLine(pack.authors)?.let { Text(it, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pack.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = if (pack.percent >= 100) ai.antigem.theme.AppColors.success else primary,
                    trackColor = primary.copy(alpha = 0.12f),
                    drawStopIndicator = {}
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    L.settings.language.percentAndSize(pack.percent, android.text.format.Formatter.formatShortFileSize(context, pack.size)),
                    fontSize = 11.sp, color = muted, maxLines = 1
                )
            }
            Spacer(Modifier.width(10.dp))
            // the button keeps its size while downloading (its icon turns into a spinner)
            FilledTonalButton(onClick = onDownload, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(L.common.download, fontSize = 13.sp)
            }
        }
    }
}

/** What the details sheet shows: an installed language ([info]), a pack of the repository ([pack]), or both. */
private data class LanguageDetails(val info: LanguageManager.Info?, val pack: LanguageRepo.Pack?)

/**
 * Everything about a language and the people who made it, with what can be done with it. Opens at its full height
 * (no half-open stop to get past) and closes with a drag down. When it's taller than the screen its content scrolls,
 * without the page's bounce: a drag always moves either the content or the sheet, never a spring inside it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LanguageDetailsSheet(
    details: LanguageDetails,
    onDismiss: () -> Unit,
    onDownload: (LanguageRepo.Pack) -> Unit,
    onUse: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onRemove: (LanguageManager.Info) -> Unit,
    onSend: (LanguageManager.Info) -> Unit
) {
    val context = LocalContext.current
    val info = details.info
    val pack = details.pack
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val success = ai.antigem.theme.AppColors.success
    val name = info?.name ?: pack?.name.orEmpty()
    val nativeName = info?.nativeName ?: pack?.nativeName.orEmpty()
    val variant = info?.variant ?: pack?.variant.orEmpty()
    val code = info?.code ?: pack?.code.orEmpty()
    val description = info?.description?.takeIf { it.isNotBlank() } ?: pack?.description.orEmpty()
    val authors = info?.authors?.takeIf { it.isNotEmpty() } ?: pack?.authors.orEmpty()
    val rtl = info?.rtl ?: pack?.rtl ?: false
    val state = pack?.let { LanguageRepo.stateOf(it.id) }
    val busy = pack != null && pack.id in LanguageRepo.downloading
    val onScreen = info != null && info.id == LanguageManager.currentId
    // progress: on the phone's English when installed, else the repository's count
    val translated = info?.translated ?: pack?.translated ?: 0
    val total = info?.total ?: pack?.total ?: 0
    val outdated = info?.outdated ?: pack?.outdated ?: 0
    val percent = if (total <= 0) 0 else (translated * 100L / total).toInt().coerceIn(0, 100)
    val complete = total > 0 && translated >= total

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), overscrollEffect = null)
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // which language
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodeBadge(code, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    if (nativeName.isNotBlank() && nativeName != name) Text(nativeName, fontSize = 15.sp, color = muted)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip(
                    when (info?.origin) {
                        LanguageManager.Origin.USER -> L.settings.language.originYours
                        LanguageManager.Origin.APP -> L.settings.language.originBuiltIn
                        else -> L.settings.language.originCommunity
                    },
                    primary
                )
                if (onScreen) SmallChip(L.settings.language.onScreen, success)
                if (variant.isNotBlank()) SmallChip(variant, primary)
                SmallChip(code, muted)
                if (rtl) SmallChip(L.settings.language.rtl, muted)
                if (state == LanguageRepo.State.UPDATE) SmallChip(L.settings.language.updateAvailable, ai.antigem.theme.AppColors.info)
            }
            if (description.isNotBlank()) Text(description, fontSize = 14.sp, lineHeight = 20.sp)

            // how much of the app it covers
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$percent%", fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = if (complete) success else primary)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            L.settings.languageTexts.progress(translated, total) + if (outdated > 0) "  ·  " + L.settings.language.outdatedCount(outdated) else "",
                            fontSize = 12.5.sp, lineHeight = 16.sp, color = muted, modifier = Modifier.weight(1f).padding(bottom = 4.dp)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else translated.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = if (complete) success else primary,
                        trackColor = primary.copy(alpha = 0.12f),
                        drawStopIndicator = {}
                    )
                    val facts = listOfNotNull(
                        pack?.updated?.takeIf { it.isNotBlank() }?.let { L.settings.language.updatedOn(shortDate(it)) },
                        (info?.appVersion?.takeIf { it.isNotBlank() } ?: pack?.appVersion?.takeIf { it.isNotBlank() })?.let { L.settings.language.forVersion(it) },
                        pack?.let { android.text.format.Formatter.formatShortFileSize(context, it.size) }
                    )
                    if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), fontSize = 12.sp, color = muted)
                    val basedOn = info?.basedOn?.takeIf { it.isNotBlank() } ?: pack?.basedOn?.takeIf { it.isNotBlank() }
                    basedOn?.let { b ->
                        Text(L.settings.language.basedOnPack(LanguageRepo.packs.firstOrNull { it.id == b }?.label ?: b), fontSize = 12.sp, color = muted)
                    }
                }
            }

            // what to do with it: the main step full width, the rest below
            val canDownload = pack != null && (state == LanguageRepo.State.AVAILABLE || state == LanguageRepo.State.UPDATE)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pack != null && canDownload) {
                    Button(
                        onClick = { onDownload(pack) }, enabled = !busy, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        when {
                            busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                            state == LanguageRepo.State.UPDATE -> Icon(Icons.Outlined.Update, null, Modifier.size(18.dp))
                            else -> Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                busy -> L.settings.language.downloading
                                state == LanguageRepo.State.UPDATE -> L.common.update
                                else -> L.common.download
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (info != null && !onScreen) {
                    val use: @Composable RowScope.() -> Unit = {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(L.settings.language.use, fontWeight = FontWeight.SemiBold)
                    }
                    val useModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    if (canDownload) FilledTonalButton(onClick = { onUse(info.id) }, shape = RoundedCornerShape(14.dp), modifier = useModifier, content = use)
                    else Button(onClick = { onUse(info.id) }, shape = RoundedCornerShape(14.dp), modifier = useModifier, content = use)
                }
                if (info != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onDuplicate(info.id) }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.language.duplicate)
                    }
                    if (info.origin == LanguageManager.Origin.USER) OutlinedButton(onClick = { onSend(info) }) {
                        Icon(Icons.Outlined.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.language.sendToCommunity)
                    }
                }
            }

            // who made it
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L.settings.language.authorLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                if (authors.isEmpty()) Text(L.settings.language.noAuthors, fontSize = 13.sp, color = muted)
                authors.forEach { a -> AuthorRow(a) }
            }

            if (pack != null || info?.origin == LanguageManager.Origin.REPO) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pack != null) {
                        TextButton(onClick = { openUrl(context, LanguageRepo.PROBLEM_FORM + "&title=" + android.net.Uri.encode("Translation problem: ${pack.label}")) }) {
                            Icon(Icons.Outlined.Flag, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.language.reportProblem)
                        }
                        TextButton(onClick = { openUrl(context, pack.pageUrl) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.language.viewFile)
                        }
                    }
                    if (info != null && info.origin == LanguageManager.Origin.REPO) TextButton(onClick = { onRemove(info) }) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(L.common.remove, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorRow(a: LanguageManager.Author) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val link = a.profileUrl
    Surface(
        onClick = { link?.let { openUrl(context, it) } },
        enabled = link != null,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text(a.name.trim().take(1).uppercase(), fontWeight = FontWeight.Bold, color = primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(a.role.takeIf { it.isNotBlank() }, a.github.takeIf { it.isNotBlank() }?.let { "@$it" }).joinToString("  ·  ")
                if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (link != null) Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = muted)
        }
    }
}

/** How to get a language of the user's into the community repository: names first, then the file and the GitHub form. */
@Composable
private fun SendToCommunityDialog(
    info: LanguageManager.Info,
    basedOn: String?,
    onAddNames: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onOpenForm: () -> Unit,
    onGuide: () -> Unit,
    onDismiss: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Upload, null) },
        title = { Text(L.settings.language.sendTitle(info.label), fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.bouncyVerticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L.settings.language.sendAbout, fontSize = 13.5.sp, lineHeight = 19.sp)
                if (info.authors.isEmpty()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = ai.antigem.theme.AppColors.warning.copy(alpha = 0.12f)) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(L.settings.language.sendNoAuthors, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = onAddNames) { Text(L.settings.language.addNames) }
                        }
                    }
                } else {
                    Text(byLine(info.authors).orEmpty(), fontSize = 12.5.sp, color = muted)
                }
                basedOn?.let { Text(L.settings.language.sendBasedOn(it), fontSize = 12.5.sp, lineHeight = 17.sp, color = muted) }
                Text(L.settings.language.sendStep1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onSave) { Icon(Icons.Outlined.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.language.saveToFile) }
                    OutlinedButton(onClick = onShare) { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.language.share) }
                }
                Text(L.settings.language.sendStep2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FilledTonalButton(onClick = onOpenForm) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.settings.language.openForm)
                }
                Text(L.settings.language.sendGit(info.code), fontSize = 12.sp, lineHeight = 16.sp, color = muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L.common.close) } },
        dismissButton = { TextButton(onClick = onGuide) { Text(L.settings.language.howToTranslate) } }
    )
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

/** What [LanguageInfoDialog] gives back. */
private class LanguageInfoResult(
    val code: String, val name: String, val nativeName: String, val rtl: Boolean, val from: String?,
    val variant: String, val description: String, val authors: List<LanguageManager.Author>
)

/** One person being edited in [LanguageInfoDialog]. */
private class AuthorDraft(name: String = "", github: String = "", role: String = "", val url: String = "") {
    var name by mutableStateOf(name)
    var github by mutableStateOf(github)
    var role by mutableStateOf(role)
}

/**
 * New language, or the name and details of one: names, code, direction, variant, description and the people who made
 * it. [languages]: offer "start from" (new languages only).
 */
@Composable
private fun LanguageInfoDialog(
    title: String,
    confirm: String,
    languages: List<LanguageManager.Info>?,
    initial: LanguageManager.Info? = null,
    onDismiss: () -> Unit,
    onConfirm: (LanguageInfoResult) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var native by remember { mutableStateOf(initial?.nativeName.orEmpty()) }
    var code by remember { mutableStateOf(initial?.code.orEmpty()) }
    var rtl by remember { mutableStateOf(initial?.rtl ?: false) }
    var variant by remember { mutableStateOf(initial?.variant.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    val authors = remember {
        mutableStateListOf<AuthorDraft>().apply { initial?.authors?.forEach { add(AuthorDraft(it.name, it.github, it.role, it.url)) } }
    }
    var from by remember { mutableStateOf<LanguageManager.Info?>(null) }
    var fromMenu by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
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
                OutlinedTextField(variant, { variant = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(L.settings.language.variantLabel) }, placeholder = { Text(L.settings.language.variantHint) },
                    supportingText = { Text(L.settings.language.variantAbout) })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.toggleRowSemantics(rtl, { rtl = it })) {
                    Text(L.settings.language.rtl, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = rtl, onCheckedChange = { rtl = it }, modifier = Modifier.mergedIntoRow())
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
                    OutlinedTextField(description, { description = it.take(600) }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                        label = { Text(L.settings.language.aboutLabel) }, placeholder = { Text(L.settings.language.descriptionHint) })
                    Text(L.settings.language.authorLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(L.settings.language.authorsAbout, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                    authors.forEachIndexed { i, a ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(a.name, { a.name = it.take(80) }, singleLine = true, modifier = Modifier.weight(1f),
                                        label = { Text(L.common.name) })
                                    IconButton(onClick = { authors.removeAt(i) }) { Icon(Icons.Outlined.Close, L.common.remove) }
                                }
                                OutlinedTextField(a.github, { a.github = it.trim().removePrefix("@").take(39) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                                    label = { Text(L.settings.language.githubLabel) }, prefix = { Text("@") })
                                OutlinedTextField(a.role, { a.role = it.take(40) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                                    label = { Text(L.settings.language.roleLabel) }, placeholder = { Text(L.settings.language.roleHint) })
                            }
                        }
                    }
                    TextButton(onClick = { authors.add(AuthorDraft()) }) {
                        Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L.settings.language.addPerson)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onConfirm(LanguageInfoResult(
                    code.trim(), name.trim(), native.trim(), rtl, from?.id, variant.trim(), description.trim(),
                    authors.filter { it.name.isNotBlank() }.map { LanguageManager.Author(it.name.trim(), it.github.trim(), it.url, it.role.trim()) }
                ))
            }) { Text(confirm) }
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
    val editable = !l.readOnly
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
                            Text(
                                if (l.origin == LanguageManager.Origin.REPO) L.settings.languageTexts.communityReadOnly(l.name) else L.settings.languageTexts.builtInReadOnly(l.name),
                                fontSize = 12.5.sp, modifier = Modifier.weight(1f)
                            )
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
