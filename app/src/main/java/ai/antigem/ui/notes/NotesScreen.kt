package ai.antigem.ui.notes

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import ai.antigem.ui.components.bouncyVerticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.notes.Note
import ai.antigem.data.notes.NoteKind
import ai.antigem.data.notes.NotesRepository
import ai.antigem.data.notes.TodoItem
import ai.antigem.theme.ClaudeTerracotta
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

private enum class NotesFilter(private val labelText: () -> String) { ALL({ L.notes.screen.all }), NOTES({ L.common.notes }), TODOS({ L.notes.screen.toDos }) ;
    val label: String get() = labelText()
}

/**
 * Notes and to-do lists. Edits go straight to NotesRepository, so changes made by agents through notes_mcp show up here at once
 * (and the other way round). The open note stays open while the user is on another screen.
 */
@Composable
fun NotesScreen(
    isVisible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { NotesRepository.init(context) }
    val notes by NotesRepository.notes.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(isVisible) { if (!isVisible) focusManager.clearFocus() }

    fun deleteWithUndo(note: Note) {
        NotesRepository.delete(note.id) ?: return
        if (note.isEmpty) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val label = if (note.kind == NoteKind.TODO) L.notes.screen.toDoListDeleted else L.notes.screen.noteDeleted
            if (snackbar.showSnackbar(label, actionLabel = L.common.undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                NotesRepository.restore(note)
            }
        }
    }

    // A new note left untouched is dropped, and blank to-do rows are tidied away
    fun closeEditor() {
        focusManager.clearFocus()
        openId?.let { id ->
            val n = NotesRepository.get(id)
            when {
                n == null -> Unit
                n.isEmpty -> NotesRepository.delete(id)
                n.items.any { it.text.isBlank() } -> NotesRepository.update(id) { it.copy(items = it.items.filter { i -> i.text.isNotBlank() }) }
            }
        }
        openId = null
    }

    fun openNew(kind: NoteKind) {
        val note = NotesRepository.create(kind)
        if (kind == NoteKind.TODO) NotesRepository.update(note.id) { it.copy(items = listOf(TodoItem())) }
        openId = note.id
    }

    // The collected list can be a frame behind a note that was just created
    val openNote = openId?.let { id -> notes.firstOrNull { it.id == id } ?: NotesRepository.get(id) }
    LaunchedEffect(openId, openNote == null) {
        if (openId != null && openNote == null) openId = null // deleted elsewhere, e.g. notes_delete
    }

    BackHandler(enabled = isVisible && openId != null) { closeEditor() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding().imePadding()) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (openNote != null) {
                NoteEditor(
                    note = openNote,
                    onBack = { closeEditor() },
                    onDelete = { openId = null; deleteWithUndo(openNote) }
                )
            } else {
                NotesList(
                    notes = notes,
                    onClose = onClose,
                    onOpen = { openId = it.id },
                    onNew = { openNew(it) }
                )
            }
        }
    }
}

// ---------------- list ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesList(
    notes: List<Note>,
    onClose: () -> Unit,
    onOpen: (Note) -> Unit,
    onNew: (NoteKind) -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(NotesFilter.ALL) }
    val chipsBounce = ai.antigem.ui.components.rememberBouncyOverscrollEffect()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showNewMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }

    val q = query.trim().lowercase()
    val shown = notes.asSequence()
        .filter {
            when (filter) {
                NotesFilter.ALL -> true
                NotesFilter.NOTES -> it.kind == NoteKind.NOTE
                NotesFilter.TODOS -> it.kind == NoteKind.TODO
            }
        }
        .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.text.lowercase().contains(q) || it.items.any { i -> i.text.lowercase().contains(q) } }
        .sortedByDescending { it.updatedAt }
        .toList()
    val pinned = shown.filter { it.pinned }
    val others = shown.filterNot { it.pinned }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (searching) { searching = false; query = "" } else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L.common.back)
                    }
                    if (searching) {
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                        PlainField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = L.notes.screen.searchNotes,
                            textStyle = TextStyle(fontSize = 16.sp),
                            singleLine = true,
                            modifier = Modifier.weight(1f).focusRequester(searchFocus)
                        )
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = L.notes.screen.clearSearch) }
                    } else {
                        Text(L.common.notes, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() })
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, contentDescription = L.common.search) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState(), overscrollEffect = chipsBounce)
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NotesFilter.entries.forEach { f ->
                        val count = when (f) {
                            NotesFilter.ALL -> notes.size
                            NotesFilter.NOTES -> notes.count { it.kind == NoteKind.NOTE }
                            NotesFilter.TODOS -> notes.count { it.kind == NoteKind.TODO }
                        }
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(if (count > 0) "${f.label} $count" else f.label) }
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            if (shown.isEmpty()) {
                EmptyState(
                    if (q.isNotEmpty()) L.notes.screen.nothingMatchesQuery(query)
                    else when (filter) {
                        NotesFilter.TODOS -> L.notes.screen.noToDoListsYet
                        NotesFilter.NOTES -> L.notes.screen.noNotesYet
                        NotesFilter.ALL -> L.notes.screen.noNotesOrToDoLists
                    }
                )
            } else {
                val listBounce = ai.antigem.ui.components.rememberBouncyLazyScroll(listState)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().then(listBounce.modifier),
                    userScrollEnabled = listBounce.userScrollEnabled,
                    overscrollEffect = listBounce.overscrollEffect,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pinned.isNotEmpty()) {
                        item(key = "h_pinned") { SectionHeader(L.notes.screen.pinned) }
                        items(pinned, key = { it.id }) { NoteCard(it, onClick = { onOpen(it) }) }
                        if (others.isNotEmpty()) item(key = "h_others") { SectionHeader(L.notes.screen.others) }
                    }
                    items(others, key = { it.id }) { NoteCard(it, onClick = { onOpen(it) }) }
                }
            }

            Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
                FloatingActionButton(onClick = { showNewMenu = true }, containerColor = ClaudeTerracotta, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Icon(Icons.Outlined.Add, contentDescription = L.notes.screen.new)
                }
                DropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(L.notes.screen.note) },
                        leadingIcon = { Icon(Icons.Outlined.EditNote, null) },
                        onClick = { showNewMenu = false; onNew(NoteKind.NOTE) }
                    )
                    DropdownMenuItem(
                        text = { Text(L.notes.screen.toDoList) },
                        leadingIcon = { Icon(Icons.Outlined.Checklist, null) },
                        onClick = { showNewMenu = false; onNew(NoteKind.TODO) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
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
private fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.EditNote, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(4.dp))
        Text(
            L.notes.screen.tapToAddOneOrAsk,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.kind == NoteKind.TODO) {
                    Icon(Icons.Outlined.Checklist, null, tint = muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    note.title.ifBlank { L.common.untitled },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (note.title.isBlank()) muted else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (note.pinned) Icon(Icons.Outlined.PushPin, L.notes.screen.pinned, tint = ClaudeTerracotta, modifier = Modifier.size(16.dp))
            }
            if (note.kind == NoteKind.TODO) {
                note.items.filter { it.text.isNotBlank() }.take(4).forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (item.done) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            if (item.done) L.common.done else null,
                            tint = if (item.done) ClaudeTerracotta else muted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (item.done) muted else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            textDecoration = if (item.done) TextDecoration.LineThrough else null
                        )
                    }
                }
                val hidden = note.items.count { it.text.isNotBlank() } - 4
                if (hidden > 0) Text("+$hidden more", fontSize = 12.sp, color = muted)
            } else if (note.text.isNotBlank()) {
                Text(note.text.trim(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(relativeTime(note.updatedAt), fontSize = 11.sp, color = muted.copy(alpha = 0.5f), modifier = Modifier.weight(1f))
                if (note.kind == NoteKind.TODO && note.items.isNotEmpty()) {
                    Text(
                        "${note.doneCount}/${note.items.size} done",
                        fontSize = 11.sp,
                        color = if (note.doneCount == note.items.size) ClaudeTerracotta else muted.copy(alpha = 0.5f),
                        modifier = Modifier.semantics { contentDescription = L.notes.screen.donecountOfItemscountDone(note.doneCount, note.items.size) }
                    )
                }
            }
        }
    }
}

// ---------------- editor ----------------

@Composable
private fun NoteEditor(note: Note, onBack: () -> Unit, onDelete: () -> Unit) {
    val id = note.id
    var showMenu by remember { mutableStateOf(false) }
    var pendingFocusItemId by remember(id) { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    fun insertItem(at: Int) {
        val item = TodoItem()
        NotesRepository.update(id) { n -> n.copy(items = n.items.toMutableList().apply { add(at.coerceIn(0, size), item) }) }
        pendingFocusItemId = item.id
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L.common.back) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { NotesRepository.update(id) { it.copy(pinned = !it.pinned) } }) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = if (note.pinned) L.notes.screen.unpin else L.notes.screen.pin,
                        tint = if (note.pinned) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = L.common.more) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (note.kind == NoteKind.TODO) {
                            DropdownMenuItem(
                                text = { Text(L.notes.screen.checkAll) },
                                leadingIcon = { Icon(Icons.Outlined.DoneAll, null) },
                                onClick = { showMenu = false; NotesRepository.update(id) { n -> n.copy(items = n.items.map { it.copy(done = true) }) } }
                            )
                            DropdownMenuItem(
                                text = { Text(L.notes.screen.uncheckAll) },
                                leadingIcon = { Icon(Icons.Outlined.CheckBoxOutlineBlank, null) },
                                onClick = { showMenu = false; NotesRepository.update(id) { n -> n.copy(items = n.items.map { it.copy(done = false) }) } }
                            )
                            DropdownMenuItem(
                                text = { Text(L.notes.screen.clearCompleted) },
                                leadingIcon = { Icon(Icons.Outlined.ClearAll, null) },
                                enabled = note.doneCount > 0,
                                onClick = { showMenu = false; NotesRepository.update(id) { n -> n.copy(items = n.items.filterNot { it.done }) } }
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text(if (note.kind == NoteKind.TODO) L.notes.screen.turnIntoNote else L.notes.screen.turnIntoToDoList) },
                            leadingIcon = { Icon(if (note.kind == NoteKind.TODO) Icons.Outlined.EditNote else Icons.Outlined.Checklist, null) },
                            onClick = {
                                showMenu = false
                                NotesRepository.update(id) { n ->
                                    if (n.kind == NoteKind.TODO) {
                                        // Items become lines of text so nothing is lost
                                        val lines = n.items.filter { it.text.isNotBlank() }.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text }
                                        n.copy(kind = NoteKind.NOTE, items = emptyList(), text = listOf(n.text.trimEnd(), lines).filter { it.isNotBlank() }.joinToString("\n"))
                                    } else {
                                        val lines = n.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                        n.copy(kind = NoteKind.TODO, text = "", items = n.items + lines.map { l ->
                                            val done = l.startsWith("[x]", ignoreCase = true)
                                            TodoItem(text = l.removePrefix("[x]").removePrefix("[X]").removePrefix("[ ]").removePrefix("- ").trim(), done = done)
                                        }.ifEmpty { listOf(TodoItem()) })
                                    }
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(L.common.delete, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                // outside the scroll: the scrolled area ends at the top of the keyboard, so text scrolled into view isn't behind it
                .imePadding()
                .bouncyVerticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            SyncedField(
                key = id,
                remoteValue = note.title,
                currentRemote = { NotesRepository.get(id)?.title },
                onValueChange = { v -> NotesRepository.update(id) { it.copy(title = v) } },
                placeholder = L.notes.screen.title,
                textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                singleLine = true,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )

            if (note.kind == NoteKind.NOTE || note.text.isNotEmpty()) {
                SyncedField(
                    key = id,
                    remoteValue = note.text,
                    currentRemote = { NotesRepository.get(id)?.text },
                    onValueChange = { v -> NotesRepository.update(id) { it.copy(text = v) } },
                    placeholder = if (note.kind == NoteKind.TODO) L.common.details else L.notes.screen.note,
                    textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }

            if (note.kind == NoteKind.TODO) {
                Spacer(Modifier.height(4.dp))
                note.items.forEachIndexed { index, item ->
                    key(item.id) {
                        TodoRow(
                            noteId = id,
                            item = item,
                            requestFocus = pendingFocusItemId == item.id,
                            onFocused = { pendingFocusItemId = null },
                            onNext = { insertItem(index + 1) },
                            onRemove = { NotesRepository.update(id) { n -> n.copy(items = n.items.filterNot { it.id == item.id }) } }
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { insertItem(note.items.size) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Add, null, tint = muted, modifier = Modifier.padding(horizontal = 12.dp).size(20.dp))
                    Text(L.notes.screen.addItem, fontSize = 15.sp, color = muted)
                }
                if (note.items.isNotEmpty()) {
                    Text(L.notes.screen.donecountOfItemscountDone(note.doneCount, note.items.size), fontSize = 12.sp, color = muted.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(L.notes.screen.editedRelativetime(relativeTime(note.updatedAt)), fontSize = 11.sp, color = muted.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun TodoRow(
    noteId: String,
    item: TodoItem,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    onNext: () -> Unit,
    onRemove: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focusRequester.requestFocus() }
            onFocused()
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = item.done,
            onCheckedChange = { checked ->
                NotesRepository.update(noteId) { n -> n.copy(items = n.items.map { if (it.id == item.id) it.copy(done = checked) else it }) }
            },
            colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta),
            // TalkBack: which item this box checks off
            modifier = Modifier.semantics { contentDescription = item.text.ifBlank { L.notes.screen.listItem } }
        )
        SyncedField(
            key = item.id,
            remoteValue = item.text,
            currentRemote = { NotesRepository.get(noteId)?.items?.firstOrNull { it.id == item.id }?.text },
            onValueChange = { v ->
                NotesRepository.update(noteId) { n -> n.copy(items = n.items.map { if (it.id == item.id) it.copy(text = v) else it }) }
            },
            placeholder = L.notes.screen.listItem,
            textStyle = TextStyle(
                fontSize = 16.sp,
                textDecoration = if (item.done) TextDecoration.LineThrough else null
            ),
            textAlpha = if (item.done) 0.5f else 1f,
            singleLine = true,
            imeAction = ImeAction.Next,
            onImeAction = onNext,
            modifier = Modifier.weight(1f).focusRequester(focusRequester)
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = L.notes.screen.removeItem, tint = muted, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Text field that writes every change to the repository but keeps its own text, so typing is never reset by the collected
 * StateFlow lagging a frame behind. It only takes [remoteValue] when the repository really holds something else (an agent edit).
 */
@Composable
private fun SyncedField(
    key: String,
    remoteValue: String,
    currentRemote: () -> String?,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    textAlpha: Float = 1f,
    singleLine: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null
) {
    // A TextFieldValue, not a String, so PlainField knows where the caret is
    var value by remember(key) { mutableStateOf(TextFieldValue(remoteValue)) }
    LaunchedEffect(key, remoteValue) {
        val latest = currentRemote()
        if (latest != null && latest != value.text) value = TextFieldValue(latest, value.selection) // caret kept, clamped to the new text
    }
    PlainField(
        value = value,
        onValueChange = { v ->
            val textChanged = v.text != value.text
            value = v
            // only real edits reach the repository: a caret move alone would bump the note's "Edited" time
            if (textChanged) onValueChange(v.text)
        },
        placeholder = placeholder,
        textStyle = textStyle,
        textAlpha = textAlpha,
        singleLine = singleLine,
        imeAction = imeAction,
        onImeAction = onImeAction,
        modifier = modifier
    )
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    textAlpha: Float = 1f,
    singleLine: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle.copy(color = onSurface.copy(alpha = textAlpha)),
        cursorBrush = SolidColor(ClaudeTerracotta),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onImeAction?.invoke() ?: defaultKeyboardAction(ImeAction.Next) }),
        modifier = modifier,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = onSurface.copy(alpha = 0.35f), textDecoration = null))
                inner()
            }
        }
    )
}

/**
 * [PlainField] for a [TextFieldValue]. A multi-line one also scrolls the page to the caret when only the caret moves: the text
 * field does that by itself only when the text changes, so the keyboard's arrow keys (Gboard, Ridmik) left the caret off screen.
 */
@Composable
private fun PlainField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    textAlpha: Float = 1f,
    singleLine: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val caretInView = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    if (!singleLine) {
        LaunchedEffect(value.selection, focused) {
            val l = layout
            // after typing, this layout is still the old text: the field scrolls to the caret itself once the new one is laid out
            if (!focused || l == null || l.layoutInput.text.text != value.text) return@LaunchedEffect
            val caret = l.getCursorRect(value.selection.end)
            // a line above and below the caret as well, so it isn't left at the very edge of the screen
            caretInView.bringIntoView(Rect(caret.left, caret.top - caret.height, caret.right, caret.bottom + caret.height))
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle.copy(color = onSurface.copy(alpha = textAlpha)),
        cursorBrush = SolidColor(ClaudeTerracotta),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onImeAction?.invoke() ?: defaultKeyboardAction(ImeAction.Next) }),
        onTextLayout = { layout = it },
        modifier = modifier.bringIntoViewRequester(caretInView).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) Text(placeholder, style = textStyle.copy(color = onSurface.copy(alpha = 0.35f), textDecoration = null))
                inner()
            }
        }
    )
}

private fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    if (now - millis < DateUtils.MINUTE_IN_MILLIS) return L.common.justNow
    return DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
