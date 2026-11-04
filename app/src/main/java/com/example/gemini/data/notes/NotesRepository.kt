package com.example.gemini.data.notes

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class NoteKind(val id: String) {
    NOTE("note"),
    TODO("todo");

    companion object {
        fun from(value: String?): NoteKind? = entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) }
    }
}

data class TodoItem(
    val id: String = newId(),
    val text: String = "",
    val done: Boolean = false
)

data class Note(
    val id: String = newId(),
    val kind: NoteKind = NoteKind.NOTE,
    val title: String = "",
    val text: String = "",
    val items: List<TodoItem> = emptyList(),
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    val doneCount: Int get() = items.count { it.done }
    val isEmpty: Boolean get() = title.isBlank() && text.isBlank() && items.all { it.text.isBlank() }
}

private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

/**
 * Notes and to-do lists, shared by the Notes screen and notes_mcp (AndroidLocalBridgeServer /notes).
 * Everything lives in memory as a StateFlow and is written to filesDir/notes/notes.json shortly after each change.
 */
object NotesRepository {
    private const val TAG = "NotesRepository"
    private const val SAVE_DELAY_MS = 400L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    @Volatile
    private var file: File? = null
    private var saveJob: Job? = null

    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.applicationContext.filesDir, "notes").apply { mkdirs() }
            val f = File(dir, "notes.json")
            _notes.value = runCatching { if (f.exists()) parse(f.readText()) else emptyList() }
                .onFailure { Log.w(TAG, "Could not read notes, starting empty", it) }
                .getOrDefault(emptyList())
            file = f
        }
    }

    // ---------------- reads ----------------

    fun get(id: String): Note? = _notes.value.firstOrNull { it.id == id }

    // ---------------- writes (each returns the stored result) ----------------

    fun create(
        kind: NoteKind,
        title: String = "",
        text: String = "",
        items: List<String> = emptyList(),
        pinned: Boolean = false
    ): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            kind = kind,
            title = title,
            text = text,
            items = items.filter { it.isNotBlank() }.map { TodoItem(text = it.trim()) },
            pinned = pinned,
            createdAt = now,
            updatedAt = now
        )
        mutate { it + note }
        return note
    }

    /** Applies [change] to the note and bumps updatedAt. Returns null when there is no such note. */
    fun update(id: String, change: (Note) -> Note): Note? {
        var result: Note? = null
        mutate { list ->
            list.map { n ->
                if (n.id == id) change(n).copy(id = n.id, createdAt = n.createdAt, updatedAt = System.currentTimeMillis()).also { result = it } else n
            }
        }
        return result
    }

    fun delete(id: String): Note? {
        var removed: Note? = null
        mutate { list ->
            removed = list.firstOrNull { it.id == id }
            list.filterNot { it.id == id }
        }
        return removed
    }

    /** Puts back a deleted note as it was (for undo). */
    fun restore(note: Note) {
        mutate { list -> if (list.any { it.id == note.id }) list else list + note }
    }

    private fun mutate(block: (List<Note>) -> List<Note>) {
        synchronized(lock) { _notes.value = block(_notes.value) }
        scheduleSave()
    }

    // ---------------- persistence ----------------

    private fun scheduleSave() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DELAY_MS)
                saveNow()
            }
        }
    }

    private fun saveNow() {
        val f = file ?: return
        val json = synchronized(lock) { serialize(_notes.value) }
        runCatching {
            val tmp = File(f.parentFile, "notes.json.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(f)) {
                f.writeText(json)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Could not save notes", it) }
    }

    fun toJson(note: Note, includeBody: Boolean = true): JSONObject = JSONObject().apply {
        put("id", note.id)
        put("kind", note.kind.id)
        put("title", note.title)
        if (includeBody) put("text", note.text)
        if (note.kind == NoteKind.TODO || note.items.isNotEmpty()) {
            if (includeBody) {
                put("items", JSONArray().apply {
                    note.items.forEachIndexed { index, item ->
                        put(JSONObject().apply {
                            put("id", item.id)
                            put("index", index)
                            put("text", item.text)
                            put("done", item.done)
                        })
                    }
                })
            }
            put("items_total", note.items.size)
            put("items_done", note.doneCount)
        }
        put("pinned", note.pinned)
        put("created_at", note.createdAt)
        put("updated_at", note.updatedAt)
    }

    private fun serialize(list: List<Note>): String = JSONObject().apply {
        put("version", 1)
        put("notes", JSONArray().apply { list.forEach { put(toJson(it)) } })
    }.toString()

    private fun parse(raw: String): List<Note> {
        val arr = JSONObject(raw).optJSONArray("notes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val items = o.optJSONArray("items")?.let { a ->
                (0 until a.length()).mapNotNull { j ->
                    a.optJSONObject(j)?.let { t ->
                        TodoItem(id = t.optString("id").ifBlank { newId() }, text = t.optString("text"), done = t.optBoolean("done"))
                    }
                }
            } ?: emptyList()
            val created = o.optLong("created_at", System.currentTimeMillis())
            Note(
                id = o.optString("id").ifBlank { newId() },
                kind = NoteKind.from(o.optString("kind")) ?: NoteKind.NOTE,
                title = o.optString("title"),
                text = o.optString("text"),
                items = items,
                pinned = o.optBoolean("pinned"),
                createdAt = created,
                updatedAt = o.optLong("updated_at", created)
            )
        }
    }
}
