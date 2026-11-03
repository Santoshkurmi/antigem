package com.example.gemini.data.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * notes_mcp: lets the model read and edit the user's notes and to-do lists (the Notes screen).
 * Served by AndroidLocalBridgeServer at /notes. Every change shows up on the Notes screen at once.
 */
object NotesMcp {

    const val INSTRUCTIONS = "Reads and edits the user's Notes screen: plain notes (title + text) and to-do lists (title + checkable items). " +
        "Use notes_list to find things (filter by kind, search, sort by created/updated, page with limit/offset), notes_get to read one in full " +
        "(or latest=true for the most recently created), then notes_create / notes_update / notes_delete. For to-do items use " +
        "notes_todo_add_items, notes_todo_set_item (check, uncheck or rename by item_id or index) and notes_todo_remove_item. " +
        "These are the user's own notes: ask before deleting anything you did not create."

    private const val DEFAULT_LIMIT = 20
    private const val MAX_LIMIT = 100
    private const val PREVIEW_CHARS = 160

    // ---------------- tool list ----------------

    private fun prop(type: String, description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", type); put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
    }

    private fun stringArray(description: String) = JSONObject().apply {
        put("type", "array"); put("description", description)
        put("items", JSONObject().put("type", "string"))
    }

    private fun tool(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    private val noteId = "id" to prop("string", "Note id from notes_list or notes_create")
    private val itemId = "item_id" to prop("string", "To-do item id (from notes_get)")
    private val itemIndex = "index" to prop("integer", "0-based position of the to-do item, when no item_id")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("notes_list",
            "List notes and to-do lists, newest change first by default. Returns total count, paging info (has_more, next_offset) and for each " +
                "entry its id, kind, title, a short text preview, to-do progress, pinned flag and created/updated times. Pinned ones are not moved " +
                "to the top here; filter with pinned_only. For the most recently created use sort=created_desc with limit=1.",
            emptyList(),
            "kind" to prop("string", "Only this kind (default all)", listOf("all", "note", "todo")),
            "query" to prop("string", "Case-insensitive search in titles, text and to-do items"),
            "pinned_only" to prop("boolean", "Only pinned entries"),
            "status" to prop("string", "For to-do lists: open = has unchecked items, completed = every item checked (default any)", listOf("any", "open", "completed")),
            "sort" to prop("string", "Order (default updated_desc)", listOf("updated_desc", "updated_asc", "created_desc", "created_asc", "title_asc", "title_desc")),
            "limit" to prop("integer", "Entries per page, 1-$MAX_LIMIT (default $DEFAULT_LIMIT)"),
            "offset" to prop("integer", "Entries to skip (default 0)"),
            "page" to prop("integer", "1-based page number, instead of offset"),
            "include_body" to prop("boolean", "Include full text and all to-do items instead of a preview (default false)")
        ))
        put(tool("notes_get",
            "Read one note or to-do list in full: title, text, every to-do item with its id, index and done state, and times.",
            emptyList(),
            noteId,
            "latest" to prop("boolean", "Instead of id: return the most recently created entry"),
            "kind" to prop("string", "With latest: only consider this kind", listOf("note", "todo"))
        ))
        put(tool("notes_create",
            "Create a note (title + text) or a to-do list (title + items, text optional). Returns the created entry with its id.",
            emptyList(),
            "kind" to prop("string", "note or todo (default note; todo when items are given)", listOf("note", "todo")),
            "title" to prop("string", "Title"),
            "text" to prop("string", "Body text"),
            "items" to stringArray("To-do items, unchecked"),
            "pinned" to prop("boolean", "Pin it to the top of the Notes screen")
        ))
        put(tool("notes_update",
            "Change a note or to-do list. Only the fields you pass change. Passing items replaces the whole to-do list (to edit single items use the notes_todo_ tools).",
            listOf("id"),
            noteId,
            "title" to prop("string", "New title"),
            "text" to prop("string", "New body text (replaces it, unless append_text is true)"),
            "append_text" to prop("boolean", "Add text to the end of the existing body (on a new line) instead of replacing it"),
            "pinned" to prop("boolean", "Pin or unpin"),
            "kind" to prop("string", "Turn a note into a to-do list or back. Items are kept.", listOf("note", "todo")),
            "items" to stringArray("Replace all to-do items with these (unchecked)")
        ))
        put(tool("notes_delete", "Delete a note or to-do list for good.", listOf("id"), noteId))
        put(tool("notes_todo_add_items",
            "Add one or more unchecked items to a to-do list.",
            listOf("id", "items"),
            noteId,
            "items" to stringArray("Item texts to add"),
            "position" to prop("integer", "0-based position to insert at (default: the end)")
        ))
        put(tool("notes_todo_set_item",
            "Check (done=true), uncheck (done=false) or rename a to-do item, picked by item_id or index.",
            listOf("id"),
            noteId, itemId, itemIndex,
            "done" to prop("boolean", "true = checked / done, false = unchecked / open"),
            "text" to prop("string", "New item text")
        ))
        put(tool("notes_todo_remove_item", "Remove one to-do item, picked by item_id or index.", listOf("id"), noteId, itemId, itemIndex))
        put(tool("notes_todo_bulk",
            "Act on every item of a to-do list at once.",
            listOf("id", "action"),
            noteId,
            "action" to prop("string", "check_all, uncheck_all, or clear_completed (removes checked items)", listOf("check_all", "uncheck_all", "clear_completed"))
        ))
    }

    // ---------------- calls ----------------

    fun call(context: Context, name: String, args: JSONObject): JSONObject {
        NotesRepository.init(context)
        return runCatching {
            when (name) {
                "notes_list" -> list(args)
                "notes_get" -> get(args)
                "notes_create" -> create(args)
                "notes_update" -> update(args)
                "notes_delete" -> {
                    val id = args.optString("id")
                    val removed = NotesRepository.delete(id) ?: return notFound(id)
                    text("Deleted ${removed.kind.id} $id (\"${removed.title.ifBlank { "Untitled" }}\").")
                }
                "notes_todo_add_items" -> addItems(args)
                "notes_todo_set_item" -> setItem(args)
                "notes_todo_remove_item" -> removeItem(args)
                "notes_todo_bulk" -> bulk(args)
                else -> error("Unknown tool $name")
            }
        }.getOrElse { error("$name failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun list(args: JSONObject): JSONObject {
        val kind = NoteKind.from(args.optString("kind"))
        val query = args.optString("query").trim().lowercase()
        val pinnedOnly = args.optBoolean("pinned_only")
        val status = args.optString("status", "any").lowercase()
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val offset = if (args.has("page")) (args.optInt("page", 1).coerceAtLeast(1) - 1) * limit else args.optInt("offset", 0).coerceAtLeast(0)
        val includeBody = args.optBoolean("include_body")

        val filtered = NotesRepository.notes.value.asSequence()
            .filter { kind == null || it.kind == kind }
            .filter { !pinnedOnly || it.pinned }
            .filter {
                when (status) {
                    "open" -> it.kind == NoteKind.TODO && it.items.any { i -> !i.done }
                    "completed" -> it.kind == NoteKind.TODO && it.items.isNotEmpty() && it.items.all { i -> i.done }
                    else -> true
                }
            }
            .filter { query.isEmpty() || matches(it, query) }
            .toList()
        val sorted = when (args.optString("sort", "updated_desc").lowercase()) {
            "updated_asc" -> filtered.sortedBy { it.updatedAt }
            "created_desc" -> filtered.sortedByDescending { it.createdAt }
            "created_asc" -> filtered.sortedBy { it.createdAt }
            "title_asc" -> filtered.sortedBy { it.title.lowercase() }
            "title_desc" -> filtered.sortedByDescending { it.title.lowercase() }
            else -> filtered.sortedByDescending { it.updatedAt }
        }
        val page = sorted.drop(offset).take(limit)
        val hasMore = offset + page.size < sorted.size

        return json(JSONObject().apply {
            put("total", sorted.size)
            put("offset", offset)
            put("limit", limit)
            put("page", offset / limit + 1)
            put("pages", if (sorted.isEmpty()) 0 else (sorted.size + limit - 1) / limit)
            put("has_more", hasMore)
            if (hasMore) put("next_offset", offset + page.size)
            put("notes", JSONArray().apply {
                page.forEach { n ->
                    put(withTimes(NotesRepository.toJson(n, includeBody), n).apply {
                        if (!includeBody) {
                            put("preview", preview(n))
                        }
                    })
                }
            })
        })
    }

    private fun get(args: JSONObject): JSONObject {
        val note = if (args.optBoolean("latest") && args.optString("id").isBlank()) {
            val kind = NoteKind.from(args.optString("kind"))
            NotesRepository.notes.value.filter { kind == null || it.kind == kind }.maxByOrNull { it.createdAt }
                ?: return text("There are no ${kind?.let { if (it == NoteKind.TODO) "to-do lists" else "notes" } ?: "notes or to-do lists"} yet.")
        } else {
            val id = args.optString("id")
            if (id.isBlank()) return error("Pass id, or latest=true.")
            NotesRepository.get(id) ?: return notFound(id)
        }
        return entry(note)
    }

    private fun create(args: JSONObject): JSONObject {
        val items = stringList(args, "items")
        val kind = NoteKind.from(args.optString("kind")) ?: if (items.isNotEmpty()) NoteKind.TODO else NoteKind.NOTE
        val title = args.optString("title").trim()
        val body = args.optString("text")
        if (title.isBlank() && body.isBlank() && items.isEmpty()) return error("Give at least a title, text or items.")
        val note = NotesRepository.create(kind, title = title, text = body, items = items, pinned = args.optBoolean("pinned"))
        return entry(note, "Created ${note.kind.id} ${note.id}.")
    }

    private fun update(args: JSONObject): JSONObject {
        val id = args.optString("id")
        NotesRepository.get(id) ?: return notFound(id)
        var newKind: NoteKind? = null
        if (args.has("kind")) {
            newKind = NoteKind.from(args.optString("kind")) ?: return error("kind must be note or todo")
        }
        val newItems = if (args.has("items")) stringList(args, "items").map { TodoItem(text = it) } else null
        val updated = NotesRepository.update(id) { n ->
            n.copy(
                title = if (args.has("title")) args.optString("title").trim() else n.title,
                text = when {
                    !args.has("text") -> n.text
                    args.optBoolean("append_text") && n.text.isNotBlank() -> n.text.trimEnd() + "\n" + args.optString("text")
                    else -> args.optString("text")
                },
                pinned = if (args.has("pinned")) args.optBoolean("pinned") else n.pinned,
                kind = newKind ?: n.kind,
                items = newItems ?: n.items
            )
        } ?: return notFound(id)
        return entry(updated, "Updated ${updated.kind.id} $id.")
    }

    private fun addItems(args: JSONObject): JSONObject {
        val id = args.optString("id")
        val note = NotesRepository.get(id) ?: return notFound(id)
        if (note.kind != NoteKind.TODO) return error("$id is a note, not a to-do list. Turn it into one with notes_update kind=todo first.")
        val texts = stringList(args, "items")
        if (texts.isEmpty()) return error("items is empty.")
        val updated = NotesRepository.update(id) { n ->
            val pos = if (args.has("position")) args.optInt("position").coerceIn(0, n.items.size) else n.items.size
            n.copy(items = n.items.toMutableList().apply { addAll(pos, texts.map { TodoItem(text = it) }) })
        } ?: return notFound(id)
        return entry(updated, "Added ${texts.size} item(s).")
    }

    private fun setItem(args: JSONObject): JSONObject {
        val id = args.optString("id")
        val note = NotesRepository.get(id) ?: return notFound(id)
        val index = itemIndexOf(note, args) ?: return itemNotFound(note, args)
        if (!args.has("done") && !args.has("text")) return error("Pass done and/or text.")
        val updated = NotesRepository.update(id) { n ->
            n.copy(items = n.items.mapIndexed { i, item ->
                if (i != index) item else item.copy(
                    done = if (args.has("done")) args.optBoolean("done") else item.done,
                    text = if (args.has("text")) args.optString("text").trim() else item.text
                )
            })
        } ?: return notFound(id)
        val item = updated.items[index]
        return entry(updated, "Item $index (${item.id}) is now ${if (item.done) "checked" else "unchecked"}: \"${item.text}\".")
    }

    private fun removeItem(args: JSONObject): JSONObject {
        val id = args.optString("id")
        val note = NotesRepository.get(id) ?: return notFound(id)
        val index = itemIndexOf(note, args) ?: return itemNotFound(note, args)
        val removed = note.items[index]
        val updated = NotesRepository.update(id) { n -> n.copy(items = n.items.filterNot { it.id == removed.id }) } ?: return notFound(id)
        return entry(updated, "Removed item \"${removed.text}\".")
    }

    private fun bulk(args: JSONObject): JSONObject {
        val id = args.optString("id")
        NotesRepository.get(id) ?: return notFound(id)
        val action = args.optString("action")
        if (action !in listOf("check_all", "uncheck_all", "clear_completed")) return error("action must be check_all, uncheck_all or clear_completed")
        val updated = NotesRepository.update(id) { n ->
            when (action) {
                "check_all" -> n.copy(items = n.items.map { it.copy(done = true) })
                "uncheck_all" -> n.copy(items = n.items.map { it.copy(done = false) })
                "clear_completed" -> n.copy(items = n.items.filterNot { it.done })
                else -> n
            }
        } ?: return notFound(id)
        return entry(updated, "Done: $action.")
    }

    // ---------------- helpers ----------------

    private fun matches(n: Note, q: String): Boolean =
        n.title.lowercase().contains(q) || n.text.lowercase().contains(q) || n.items.any { it.text.lowercase().contains(q) }

    private fun preview(n: Note): String {
        val raw = if (n.kind == NoteKind.TODO && n.items.isNotEmpty()) {
            n.items.take(5).joinToString("; ") { (if (it.done) "[x] " else "[ ] ") + it.text }
        } else n.text
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > PREVIEW_CHARS) flat.take(PREVIEW_CHARS) + "…" else flat
    }

    private fun itemIndexOf(note: Note, args: JSONObject): Int? {
        val itemId = args.optString("item_id")
        if (itemId.isNotBlank()) return note.items.indexOfFirst { it.id == itemId }.takeIf { it >= 0 }
        if (args.has("index")) return args.optInt("index", -1).takeIf { it in note.items.indices }
        return null
    }

    private fun itemNotFound(note: Note, args: JSONObject): JSONObject = when {
        args.optString("item_id").isBlank() && !args.has("index") -> error("Pass item_id or index.")
        else -> error("No such item in ${note.id} (it has ${note.items.size} item(s), indexes 0-${(note.items.size - 1).coerceAtLeast(0)}). Read it with notes_get.")
    }

    private fun stringList(args: JSONObject, key: String): List<String> {
        val arr = args.optJSONArray(key)
        val raw = if (arr != null) {
            (0 until arr.length()).map { i ->
                val v = arr.opt(i)
                if (v is JSONObject) v.optString("text") else v?.toString().orEmpty()
            }
        } else {
            args.optString(key).split('\n')
        }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    private fun iso(millis: Long): String = synchronized(isoFormat) { isoFormat.format(Date(millis)) }

    private fun withTimes(o: JSONObject, n: Note): JSONObject = o.apply {
        put("created", iso(n.createdAt))
        put("updated", iso(n.updatedAt))
    }

    private fun entry(n: Note, headline: String? = null): JSONObject {
        val body = withTimes(NotesRepository.toJson(n), n).toString(2)
        return text(if (headline != null) "$headline\n$body" else body)
    }

    private fun notFound(id: String) = error(if (id.isBlank()) "Pass id." else "No note or to-do list with id $id. Find ids with notes_list.")
    private fun json(o: JSONObject) = text(o.toString(2))
    private fun text(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    private fun error(message: String) = text(message).put("isError", true)
}
