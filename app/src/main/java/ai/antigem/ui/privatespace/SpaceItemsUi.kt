package ai.antigem.ui.privatespace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.privatespace.PasswordTools
import ai.antigem.data.privatespace.PrivateSpace
import ai.antigem.data.privatespace.QuickNotice
import ai.antigem.data.privatespace.SpaceField
import ai.antigem.data.privatespace.SpaceFieldKind
import ai.antigem.data.privatespace.SpaceHistoryEntry
import ai.antigem.data.privatespace.SpaceItem
import ai.antigem.data.privatespace.SpaceItemType
import ai.antigem.data.privatespace.Totp
import ai.antigem.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

private sealed interface SpacePage {
    data object List : SpacePage
    data class View(val id: String) : SpacePage
    data class Edit(val id: String?, val type: SpaceItemType) : SpacePage
    data object Settings : SpacePage
}

/** The open space: the list, one item, its editor, and the space's settings. */
@Composable
internal fun SpaceHome(onClose: () -> Unit) {
    var page by remember { mutableStateOf<SpacePage>(SpacePage.List) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { msg ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding().imePadding()) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val p = page) {
                SpacePage.List -> SpaceList(
                    onClose = onClose,
                    onOpen = { page = SpacePage.View(it) },
                    onNew = { page = SpacePage.Edit(null, it) },
                    onSettings = { page = SpacePage.Settings },
                    showMessage = showMessage
                )
                is SpacePage.View -> ItemViewer(
                    id = p.id,
                    onBack = { page = SpacePage.List },
                    onEdit = { type -> page = SpacePage.Edit(p.id, type) },
                    showMessage = showMessage
                )
                is SpacePage.Edit -> ItemEditor(
                    id = p.id,
                    type = p.type,
                    onSaved = { savedId -> page = SpacePage.View(savedId) },
                    onCancel = { page = if (p.id != null) SpacePage.View(p.id) else SpacePage.List }
                )
                SpacePage.Settings -> SpaceSettingsScreen(onBack = { page = SpacePage.List }, showMessage = showMessage)
            }
        }
    }
}

/** What a copy says; on older Android also who else can see the clipboard meanwhile (the system can't keep them out). */
internal fun copiedMessage(): String {
    val seconds = PrivateSpace.settings.value.clipboardSeconds
    return when {
        // Android 9 and older: any app, even in the background, can read the clipboard
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q -> L.privateSpace.common.copiedClearsOldAndroid(seconds)
        // before Android 13 keyboards ignore the "sensitive" mark and may keep it in their clipboard history
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU -> L.privateSpace.common.copiedClearsKeyboard(seconds)
        else -> L.privateSpace.common.copiedClears(seconds)
    }
}

// ---------------- list ----------------

private enum class SpaceFilter { ALL, FAVORITES, LOGIN, API_KEY, NOTE, SSH_KEY, SERVER, CARD, WIFI, TRASH }

private fun SpaceFilter.label(): String = when (this) {
    SpaceFilter.ALL -> L.privateSpace.list.all
    SpaceFilter.FAVORITES -> L.privateSpace.list.favorites
    SpaceFilter.TRASH -> L.privateSpace.list.trash
    else -> typeLabel(SpaceItemType.valueOf(name))
}

private fun SpaceFilter.matches(item: SpaceItem): Boolean = when (this) {
    SpaceFilter.TRASH -> item.inTrash
    SpaceFilter.ALL -> !item.inTrash
    SpaceFilter.FAVORITES -> !item.inTrash && item.favorite
    else -> !item.inTrash && item.type.name == name
}

private fun subtitleOf(item: SpaceItem): String {
    fun v(key: String) = item.fields.firstOrNull { it.key == key }?.value?.trim().orEmpty()
    return when (item.type) {
        SpaceItemType.LOGIN -> v("username").ifBlank { v("website") }
        SpaceItemType.API_KEY -> listOf(v("service"), v("envVar")).filter { it.isNotBlank() }.joinToString(" · ")
        SpaceItemType.NOTE -> item.notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        SpaceItemType.SSH_KEY -> listOf(v("username"), v("host")).filter { it.isNotBlank() }.joinToString("@")
        SpaceItemType.SERVER -> listOf(v("host"), v("port")).filter { it.isNotBlank() }.joinToString(":")
        SpaceItemType.CARD -> v("cardNumber").filter { it.isDigit() }.takeLast(4).let { if (it.isEmpty()) "" else "•••• $it" }
        SpaceItemType.WIFI -> v("network")
    }
}

/** What the list's copy button copies: the first secret with a value (a password, a key). */
private fun primarySecret(item: SpaceItem): SpaceField? =
    item.fields.firstOrNull { it.kind == SpaceFieldKind.SECRET && it.value.isNotEmpty() }

private fun searchable(item: SpaceItem): String = buildString {
    append(item.title).append('\n')
    item.fields.filter { !it.kind.secret }.forEach { append(it.value).append('\n') }
    item.tags.forEach { append(it).append('\n') }
    append(item.notes)
}.lowercase()

@Composable
private fun SpaceList(
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (SpaceItemType) -> Unit,
    onSettings: () -> Unit,
    showMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val items by PrivateSpace.items.collectAsState()
    val quick by PrivateSpace.quick.collectAsState()
    var filter by remember { mutableStateOf(SpaceFilter.ALL) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var byName by remember { mutableStateOf(false) }
    var showNewMenu by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }

    val q = query.trim().lowercase()
    val shown = items.asSequence()
        .filter { filter.matches(it) }
        .filter { q.isEmpty() || searchable(it).contains(q) }
        .let { seq -> if (byName) seq.sortedBy { it.title.lowercase() } else seq.sortedByDescending { it.updatedAt } }
        .toList()
    val favorites = if (filter == SpaceFilter.ALL && q.isEmpty() && !byName) shown.filter { it.favorite } else emptyList()
    val rest = if (favorites.isEmpty()) shown else shown.filterNot { it.favorite }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (searching) { searching = false; query = "" } else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L.common.back)
                    }
                    if (searching) {
                        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(L.privateSpace.list.search) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                            modifier = Modifier.weight(1f).focusRequester(searchFocus).noAutofill()
                        )
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = L.common.clear) }
                    } else {
                        Icon(Icons.Outlined.Shield, null, tint = ClaudeTerracotta, modifier = Modifier.padding(start = 4.dp).size(20.dp))
                        Text(
                            L.privateSpace.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() }
                        )
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, contentDescription = L.common.search) }
                        IconButton(onClick = { byName = !byName }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = if (byName) L.privateSpace.list.sortRecent else L.privateSpace.list.sortName)
                        }
                        IconButton(onClick = { PrivateSpace.lock() }) { Icon(Icons.Outlined.Lock, contentDescription = L.privateSpace.list.lockNow) }
                        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = L.common.settings) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpaceFilter.entries.forEach { f ->
                        val count = items.count { f.matches(it) }
                        if (count == 0 && f != SpaceFilter.ALL && f != filter) return@forEach
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(if (count > 0) "${f.label()} $count" else f.label()) },
                            leadingIcon = when (f) {
                                SpaceFilter.FAVORITES -> ({ Icon(Icons.Outlined.StarOutline, null, Modifier.size(16.dp)) })
                                SpaceFilter.TRASH -> ({ Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(16.dp)) })
                                else -> null
                            }
                        )
                    }
                }
            }
        }

        if (quick.notice == QuickNotice.KEYS_TAMPERED || quick.notice == QuickNotice.PIN_OUTSIDE_TRIES) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) { SecurityNotice(quick) }
        }

        Box(Modifier.fillMaxSize()) {
            if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (filter == SpaceFilter.TRASH) Icons.Outlined.DeleteOutline else Icons.Outlined.Shield, null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            q.isNotEmpty() -> L.privateSpace.list.noMatches(query)
                            filter == SpaceFilter.TRASH -> L.privateSpace.list.trashEmpty
                            else -> L.privateSpace.list.empty
                        },
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (q.isEmpty() && filter != SpaceFilter.TRASH) {
                        Spacer(Modifier.height(4.dp))
                        Text(L.privateSpace.list.emptyHint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filter == SpaceFilter.TRASH) {
                        item(key = "trash_info") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(L.privateSpace.list.trashInfo, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
                                TextButton(onClick = { confirmEmptyTrash = true }) { Text(L.privateSpace.list.emptyTrash, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    if (favorites.isNotEmpty()) {
                        item(key = "h_fav") { ListHeader(L.privateSpace.list.favorites) }
                        items(favorites, key = { "f_" + it.id }) { ItemRow(it, onClick = { onOpen(it.id) }, onCopy = { v -> PrivateSpace.copySecret(context, v); showMessage(copiedMessage()) }) }
                        item(key = "h_all") { ListHeader(L.privateSpace.list.everything) }
                    }
                    items(rest, key = { it.id }) { ItemRow(it, onClick = { onOpen(it.id) }, onCopy = { v -> PrivateSpace.copySecret(context, v); showMessage(copiedMessage()) }) }
                }
            }

            if (filter != SpaceFilter.TRASH) {
                Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
                    FloatingActionButton(onClick = { showNewMenu = true }, containerColor = ClaudeTerracotta, contentColor = Color.White) {
                        Icon(Icons.Outlined.Add, contentDescription = L.privateSpace.list.add)
                    }
                    DropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                        SpaceItemType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(typeLabel(t)) },
                                leadingIcon = { Icon(typeIcon(t), null, tint = typeColor(t)) },
                                onClick = {
                                    showNewMenu = false
                                    onNew(t)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmEmptyTrash) {
        SpaceAlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text(L.privateSpace.list.emptyTrashTitle) },
            text = { Text(L.privateSpace.list.emptyTrashText) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmptyTrash = false
                    PrivateSpace.emptyTrash()
                }) { Text(L.privateSpace.list.emptyTrash, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun ListHeader(label: String) {
    Text(
        label.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() }
    )
}

@Composable
private fun TypeBadge(type: SpaceItemType, size: Int = 40) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(typeColor(type).copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(typeIcon(type), null, tint = typeColor(type), modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun ItemRow(item: SpaceItem, onClick: () -> Unit, onCopy: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val subtitle = remember(item) { subtitleOf(item) }
    val secret = remember(item) { primarySecret(item) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(item.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title.ifBlank { L.common.untitled },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (item.title.isBlank()) muted else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.favorite) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.Star, contentDescription = L.privateSpace.list.favorite, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                    }
                }
                val line = when {
                    item.inTrash -> L.privateSpace.list.deletedAgo(relativeTime(item.deletedAt ?: item.updatedAt))
                    subtitle.isNotBlank() -> subtitle
                    else -> typeLabel(item.type)
                }
                Text(line, fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (secret != null && !item.inTrash) {
                IconButton(onClick = { onCopy(secret.value) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = L.privateSpace.common.copyField(fieldLabel(secret)), tint = muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ---------------- one item ----------------

@Composable
private fun ItemViewer(id: String, onBack: () -> Unit, onEdit: (SpaceItemType) -> Unit, showMessage: (String) -> Unit) {
    val context = LocalContext.current
    val items by PrivateSpace.items.collectAsState()
    val item = items.firstOrNull { it.id == id }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(item == null) { if (item == null) onBack() }
    BackHandler(onBack = onBack)
    if (item == null) return

    val copy: (String) -> Unit = { v ->
        PrivateSpace.copySecret(context, v)
        showMessage(copiedMessage())
    }

    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(item.title.ifBlank { L.common.untitled }, onBack = onBack) {
            if (!item.inTrash) {
                IconButton(onClick = { PrivateSpace.toggleFavorite(item.id) }) {
                    Icon(
                        if (item.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (item.favorite) L.privateSpace.item.unfavorite else L.privateSpace.item.makeFavorite,
                        tint = if (item.favorite) Color(0xFFF59E0B) else LocalContentColor.current
                    )
                }
                IconButton(onClick = { onEdit(item.type) }) { Icon(Icons.Outlined.Edit, contentDescription = L.common.edit) }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = L.common.more) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (item.inTrash) {
                        DropdownMenuItem(
                            text = { Text(L.privateSpace.item.restore) },
                            leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                            onClick = {
                                menu = false
                                PrivateSpace.restoreFromTrash(item.id)
                                showMessage(L.privateSpace.item.restored)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(L.privateSpace.item.deleteForever, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                confirmDelete = true
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(L.privateSpace.item.moveToTrash) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = {
                                menu = false
                                PrivateSpace.moveToTrash(item.id)
                                showMessage(L.privateSpace.item.movedToTrash)
                                onBack()
                            }
                        )
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(item.type, size = 48)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(item.title.ifBlank { L.common.untitled }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(typeLabel(item.type), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            if (item.inTrash) InfoCard(Icons.Outlined.DeleteOutline, L.privateSpace.item.inTrashNote, color = MaterialTheme.colorScheme.error)

            item.fields.filter { it.value.isNotEmpty() }.forEach { f -> FieldView(f, onCopy = copy) }

            if (item.notes.isNotBlank()) {
                SectionCard(L.privateSpace.item.notes) {
                    SelectionContainer { Text(item.notes, fontSize = 15.sp) }
                }
            }
            if (item.tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.forEach { t -> AssistChip(onClick = {}, label = { Text(t) }, leadingIcon = { Icon(Icons.Outlined.Sell, null, Modifier.size(14.dp)) }) }
                }
            }
            if (item.history.isNotEmpty()) HistorySection(item.history, onCopy = copy)

            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                Text(L.privateSpace.item.created(dateTime(context, item.createdAt)), fontSize = 12.sp, color = muted)
                Text(L.privateSpace.item.updated(dateTime(context, item.updatedAt)), fontSize = 12.sp, color = muted)
            }
        }
    }

    if (confirmDelete) {
        SpaceAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(L.privateSpace.item.deleteForeverTitle) },
            text = { Text(L.privateSpace.item.deleteForeverText) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    PrivateSpace.deleteForever(item.id)
                    onBack()
                }) { Text(L.privateSpace.item.deleteForever, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            content()
        }
    }
}

/** One field: secrets show dots until revealed (and hide again after 20 s); 2FA shows the live code. */
@Composable
private fun FieldView(field: SpaceField, onCopy: (String) -> Unit) {
    val label = fieldLabel(field)
    if (field.kind == SpaceFieldKind.TOTP) {
        TotpView(label, field.value, onCopy)
        return
    }
    var revealed by remember(field.id) { mutableStateOf(false) }
    LaunchedEffect(revealed) {
        if (revealed) {
            delay(20_000)
            revealed = false
        }
    }
    val hidden = field.kind.secret && !revealed
    val mono = field.kind.secret || field.key == "envVar"
    SectionCard(label) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f).padding(top = 2.dp)) {
                if (hidden) {
                    Text(
                        dots(field.value),
                        fontSize = 16.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.semantics { contentDescription = L.privateSpace.common.hiddenValue }
                    )
                } else {
                    SelectionContainer {
                        Text(
                            field.value,
                            fontSize = if (field.kind.multiline) 13.sp else 16.sp,
                            fontFamily = if (mono) FontFamily.Monospace else null
                        )
                    }
                }
            }
            if (field.kind.secret) {
                IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (revealed) L.privateSpace.common.hideValue else L.privateSpace.common.showValue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            IconButton(onClick = { onCopy(field.value) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = L.privateSpace.common.copyField(label), modifier = Modifier.size(20.dp))
            }
        }
        if (field.kind == SpaceFieldKind.SECRET && (field.key == "password" || field.key == null)) {
            val s = remember(field.value) { PasswordTools.strength(field.value) }
            Text(strengthLabel(s.score), fontSize = 11.5.sp, color = strengthColor(s.score))
        }
    }
}

@Composable
private fun TotpView(label: String, secret: String, onCopy: (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(secret) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val code = remember(secret, now / 1000) { Totp.code(secret, now) }
    SectionCard(label) {
        if (code == null) {
            Text(L.privateSpace.item.totpInvalid, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val grouped = if (code.code.length == 6) code.code.chunked(3).joinToString(" ") else code.code
                Text(grouped, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { code.secondsLeft / code.period.toFloat() },
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 3.dp,
                        color = if (code.secondsLeft <= 5) Color(0xFFEF4444) else ClaudeTerracotta
                    )
                    Text("${code.secondsLeft}", fontSize = 11.sp)
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { onCopy(code.code) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = L.privateSpace.common.copyField(label))
                }
            }
        }
    }
}

@Composable
private fun HistorySection(history: List<SpaceHistoryEntry>, onCopy: (String) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.History, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(L.privateSpace.item.history(history.size), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (open) L.common.collapse else L.common.expand)
            }
            if (open) {
                history.forEach { h ->
                    var shown by remember(h) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (shown) h.value else dots(h.value),
                                fontFamily = if (shown) FontFamily.Monospace else null,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(L.privateSpace.item.historyEntry(field = historyLabel(h.fieldLabel), date = dateTime(context, h.changedAt)), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                        IconButton(onClick = { shown = !shown }, modifier = Modifier.size(36.dp)) {
                            Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (shown) L.privateSpace.common.hideValue else L.privateSpace.common.showValue, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onCopy(h.value) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = L.common.copy, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------- editor ----------------

@Composable
private fun ItemEditor(id: String?, type: SpaceItemType, onSaved: (String) -> Unit, onCancel: () -> Unit) {
    val original = remember(id) { id?.let { PrivateSpace.get(it) } ?: SpaceItem.blank(type) }
    var title by remember { mutableStateOf(original.title) }
    val fields = remember { mutableStateListOf<SpaceField>().apply { addAll(original.fields) } }
    var notes by remember { mutableStateOf(original.notes) }
    var tags by remember { mutableStateOf(original.tags.joinToString(", ")) }
    var addingField by remember { mutableStateOf(false) }
    var generatorFor by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val tagList = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val changed = title != original.title || fields.toList() != original.fields || notes != original.notes || tagList != original.tags
    val canSave = title.isNotBlank() || fields.any { it.value.isNotBlank() } || notes.isNotBlank()

    fun save() {
        if (!canSave) return
        val item = original.copy(
            title = title.ifBlank { fields.firstOrNull { it.value.isNotBlank() && !it.kind.secret }?.value?.take(40) ?: typeLabel(original.type) },
            fields = fields.toList(),
            notes = notes,
            tags = tagList
        )
        PrivateSpace.save(item)
        onSaved(item.id)
    }

    val back = { if (changed) confirmDiscard = true else onCancel() }
    BackHandler(onBack = back)

    Column(Modifier.fillMaxSize()) {
        SpaceTopBar(if (id == null) L.privateSpace.item.newTitle(typeLabel(original.type)) else L.privateSpace.item.editTitle, onBack = back) {
            TextButton(onClick = { save() }, enabled = canSave) { Text(L.common.save, fontWeight = FontWeight.SemiBold) }
        }
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(L.privateSpace.item.title) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().noAutofill()
            )
            fields.forEachIndexed { index, f ->
                FieldEditor(
                    field = f,
                    onChange = { fields[index] = it },
                    onRemove = { fields.removeAt(index) },
                    onGenerate = { generatorFor = f.id }
                )
            }
            OutlinedButton(onClick = { addingField = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(L.privateSpace.item.addField)
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(L.privateSpace.item.notes) },
                minLines = if (original.type == SpaceItemType.NOTE) 8 else 3,
                modifier = Modifier.fillMaxWidth().noAutofill()
            )
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it },
                label = { Text(L.privateSpace.item.tags) },
                placeholder = { Text(L.privateSpace.item.tagsHint) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Sell, null) },
                modifier = Modifier.fillMaxWidth().noAutofill()
            )
        }
    }

    if (addingField) {
        AddFieldDialog(onDismiss = { addingField = false }, onAdd = { label, kind ->
            fields.add(SpaceField(label = label, kind = kind))
            addingField = false
        })
    }
    generatorFor?.let { fid ->
        GeneratorDialog(
            onDismiss = { generatorFor = null },
            onUse = { pw ->
                val i = fields.indexOfFirst { it.id == fid }
                if (i >= 0) fields[i] = fields[i].copy(value = pw)
                generatorFor = null
            }
        )
    }
    if (confirmDiscard) {
        SpaceAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(L.privateSpace.item.discardTitle) },
            text = { Text(L.privateSpace.item.discardText) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onCancel()
                }) { Text(L.privateSpace.item.discard, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(L.privateSpace.item.keepEditing) } }
        )
    }
}

@Composable
private fun FieldEditor(field: SpaceField, onChange: (SpaceField) -> Unit, onRemove: () -> Unit, onGenerate: () -> Unit) {
    val custom = field.key == null
    val label = fieldLabel(field)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (custom) {
                    OutlinedTextField(
                        value = field.label,
                        onValueChange = { onChange(field.copy(label = it)) },
                        label = { Text(L.privateSpace.item.fieldName) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).noAutofill()
                    )
                } else {
                    Text(
                        "$label · ${kindLabel(field.kind)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = L.privateSpace.item.removeField(label), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
            Box(Modifier.padding(end = 6.dp)) {
                when {
                    field.kind == SpaceFieldKind.SECRET -> SecretField(
                        value = field.value,
                        onValueChange = { onChange(field.copy(value = it)) },
                        label = label,
                        imeAction = ImeAction.Next,
                        monospace = true,
                        trailingExtra = {
                            IconButton(onClick = onGenerate) { Icon(Icons.Outlined.Autorenew, contentDescription = L.privateSpace.gen.title) }
                        }
                    )
                    field.kind == SpaceFieldKind.TOTP -> SecretField(
                        value = field.value,
                        onValueChange = { onChange(field.copy(value = it.trim())) },
                        label = label,
                        imeAction = ImeAction.Next,
                        monospace = true,
                        supportingText = if (field.value.isNotBlank() && Totp.parse(field.value) == null) L.privateSpace.item.totpInvalid else L.privateSpace.item.totpHint,
                        isError = field.value.isNotBlank() && Totp.parse(field.value) == null
                    )
                    field.kind.multiline -> MultilineSecretAware(field, label, onChange)
                    else -> OutlinedTextField(
                        value = field.value,
                        onValueChange = { onChange(field.copy(value = it)) },
                        label = { Text(label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when {
                                field.kind == SpaceFieldKind.URL -> KeyboardType.Uri
                                field.key == "port" -> KeyboardType.Number
                                field.key == "username" -> KeyboardType.Email
                                else -> KeyboardType.Text
                            },
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next
                        ),
                        textStyle = LocalTextStyle.current.copy(fontFamily = if (field.key == "envVar") FontFamily.Monospace else LocalTextStyle.current.fontFamily),
                        modifier = Modifier.fillMaxWidth().noAutofill()
                    )
                }
            }
        }
    }
}

@Composable
private fun MultilineSecretAware(field: SpaceField, label: String, onChange: (SpaceField) -> Unit) {
    var shown by remember { mutableStateOf(!field.kind.secret) }
    OutlinedTextField(
        value = field.value,
        onValueChange = { onChange(field.copy(value = it)) },
        label = { Text(label) },
        minLines = 3,
        maxLines = 12,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (field.kind.secret) KeyboardType.Password else KeyboardType.Text,
            autoCorrectEnabled = false
        ),
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        trailingIcon = if (field.kind.secret) ({
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (shown) L.privateSpace.common.hideValue else L.privateSpace.common.showValue)
            }
        }) else null,
        modifier = Modifier.fillMaxWidth().noAutofill()
    )
}

@Composable
private fun AddFieldDialog(onDismiss: () -> Unit, onAdd: (String, SpaceFieldKind) -> Unit) {
    var label by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SpaceFieldKind.TEXT) }
    SpaceAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.privateSpace.item.addField) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(L.privateSpace.item.fieldName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().noAutofill()
                )
                SpaceFieldKind.entries.forEach { k ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.RadioButton) { kind = k }.padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = kind == k, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(kindLabel(k), fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(label.trim(), kind) }, enabled = label.isNotBlank()) { Text(L.common.add) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

// ---------------- password generator ----------------

@Composable
internal fun GeneratorDialog(onDismiss: () -> Unit, onUse: ((String) -> Unit)?) {
    val context = LocalContext.current
    var options by remember { mutableStateOf(PasswordTools.GeneratorOptions()) }
    var value by remember { mutableStateOf(PasswordTools.generate(options)) }
    fun regen(o: PasswordTools.GeneratorOptions = options) {
        options = o
        value = PasswordTools.generate(o)
    }
    SpaceAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.privateSpace.gen.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(value, fontFamily = FontFamily.Monospace, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                        IconButton(onClick = { regen() }) { Icon(Icons.Outlined.Autorenew, contentDescription = L.privateSpace.gen.again) }
                    }
                }
                StrengthBar(value)
                Text(L.privateSpace.gen.length(options.length), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = options.length.toFloat(),
                    onValueChange = { regen(options.copy(length = it.toInt())) },
                    valueRange = 8f..64f
                )
                GenToggle(L.privateSpace.gen.upper, options.upper) { regen(options.copy(upper = it)) }
                GenToggle(L.privateSpace.gen.lower, options.lower) { regen(options.copy(lower = it)) }
                GenToggle(L.privateSpace.gen.digits, options.digits) { regen(options.copy(digits = it)) }
                GenToggle(L.privateSpace.gen.symbols, options.symbols) { regen(options.copy(symbols = it)) }
                GenToggle(L.privateSpace.gen.avoidSimilar, options.avoidSimilar) { regen(options.copy(avoidSimilar = it)) }
            }
        },
        confirmButton = {
            if (onUse != null) TextButton(onClick = { onUse(value) }) { Text(L.privateSpace.gen.use) }
            else TextButton(onClick = { PrivateSpace.copySecret(context, value); onDismiss() }) { Text(L.common.copy) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

@Composable
private fun GenToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 2.dp)
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.height(32.dp))
    }
}
