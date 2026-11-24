package ai.antigem.i18n

import org.json.JSONArray
import org.json.JSONObject

/**
 * language_mcp: lets a model list, switch, create, translate, import and export the app's languages (served by
 * AndroidLocalBridgeServer at /language). Everything goes through [LanguageManager], the same place Settings → Language uses.
 */
object LanguageMcp {

    const val INSTRUCTIONS = "Translates AntiGem's interface. Every text the app shows has a key (like settings.dialog.themes) with " +
        "English as the source; a language gives its own text per key, and English shows for any key it leaves out. " +
        "language_list shows the languages and the one on screen; language_apply switches (the app redraws at once). " +
        "To translate: language_create (code, name, native_name), or pick a custom language from language_list; then repeat " +
        "language_missing (a batch of untranslated or outdated texts with their English) → language_update {key: translation} " +
        "until nothing is missing. Apply the language first and the user watches the app change as you go. Rules: keep every " +
        "{placeholder} exactly as in English (the app fills them in); keep button and tab labels short; keep names of apps, " +
        "agents, models, commands, file paths and code as they are; plural keys take an object of the forms the language uses " +
        "(zero, one, two, few, many, other; \"other\" is required; {count} is the number). Built-in languages can't be changed: " +
        "language_duplicate one first. language_export returns the file to share. language_set_ai_reply sets the language agents reply in."

    private fun prop(type: String, description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", type); put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
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

    private val idProp = "id" to prop("string", "Language id or name (default: the language on screen)")
    private val sectionProp = "section" to prop("string", "Only keys starting with this, e.g. \"settings\" or \"settings.dialog\" (see language_sections)")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("language_list", "Every language (English, built-in and custom) with how many texts it has translated, and the one on screen."))
        put(tool("language_apply", "Show the app in a language (\"en\" for English). It redraws at once.", listOf("id"), idProp))
        put(tool("language_sections", "The parts of the app (key prefixes) with how many texts each has, and for a language how many are translated.",
            emptyList(), idProp))
        put(tool("language_missing",
            "The next texts to translate in a language: untranslated ones and outdated ones (English changed since), with their English, " +
                "placeholders and plural forms. Translate them with language_update, then call again until none are left.",
            emptyList(), idProp, sectionProp,
            "limit" to prop("integer", "How many (default 120, at most 400)"),
            "outdated" to prop("boolean", "Include outdated texts (default true)")))
        put(tool("language_get", "Texts of a language with their English and status (translated, missing, outdated).", emptyList(), idProp, sectionProp,
            "keys" to prop("array", "Only these keys"),
            "search" to prop("string", "Only texts whose key, English or translation contains this"),
            "status" to prop("string", "Only texts with this status", listOf("all", "translated", "missing", "outdated")),
            "offset" to prop("integer", "Skip this many (paging)"),
            "limit" to prop("integer", "How many (default 200, at most 500)")))
        put(tool("language_update",
            "Set texts of a custom language: strings is {key: text}; a plural key takes {\"one\": …, \"other\": …} (the forms the language uses); " +
                "null or \"\" removes a translation. Texts with an unknown {placeholder} are refused and listed. Can also change the name, code and direction.",
            listOf("id"), idProp,
            "strings" to prop("object", "key → translation, e.g. {\"common.cancel\": \"रद्द करें\"}"),
            "name" to prop("string", "Name in English, e.g. Nepali"),
            "native_name" to prop("string", "Name in the language itself, e.g. नेपाली"),
            "code" to prop("string", "Language code like ne, pt-BR (used for plural rules)"),
            "rtl" to prop("boolean", "Written right to left (Arabic, Hebrew, Urdu, Persian…)"),
            "author" to prop("string", "Who made it"),
            "about" to prop("string", "One line about it")))
        put(tool("language_create", "Create a custom language. It starts empty (English shows until texts are added) or as a copy of another one.",
            listOf("code", "name"),
            "code" to prop("string", "Language code like ne, hi, pt-BR"),
            "name" to prop("string", "Name in English, e.g. Nepali"),
            "native_name" to prop("string", "Name in the language itself, e.g. नेपाली"),
            "rtl" to prop("boolean", "Written right to left"),
            "from" to prop("string", "Copy the texts of this language"),
            "apply" to prop("boolean", "Show the app in it right away (default true)")))
        put(tool("language_duplicate", "Copy a language (built-in or custom) into a new custom one you can edit.", listOf("id"), idProp,
            "name" to prop("string", "Name of the copy")))
        put(tool("language_delete", "Delete a custom language. Ask the user first.", listOf("id"), idProp))
        put(tool("language_export", "A language as its file (JSON) to share or keep. \"en\" gives English with every key: a template.", emptyList(), idProp))
        put(tool("language_import", "Add a language from its file (as made by language_export).", listOf("json"),
            "json" to prop("string", "The language file"),
            "replace" to prop("string", "Overwrite this custom language instead of adding a new one"),
            "apply" to prop("boolean", "Show the app in it right away (default false)")))
        put(tool("language_set_ai_reply", "The language agents reply in, added to every chat's app context: off (they choose), app (the app's language) or a language name.",
            listOf("mode"),
            "mode" to prop("string", "off, app or language", listOf("off", "app", "language")),
            "language" to prop("string", "With mode=language: its name, e.g. Nepali")))
    }

    private fun text(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    private fun json(o: JSONObject) = text(o.toString(2))
    private fun error(message: String) = text(message).put("isError", true)

    private fun target(args: JSONObject): LanguageManager.Language? =
        args.optString("id").trim().ifBlank { null }?.let { LanguageManager.find(it) } ?: LanguageManager.load(LanguageManager.currentId)

    private fun infoJson(i: LanguageManager.Info) = JSONObject().apply {
        put("id", i.id); put("name", i.name); put("code", i.code)
        if (i.nativeName.isNotBlank()) put("native_name", i.nativeName)
        put("built_in", i.builtIn); if (i.rtl) put("rtl", true)
        put("translated", i.translated); put("total", i.total)
        if (i.outdated > 0) put("outdated", i.outdated)
        if (i.about.isNotBlank()) put("about", i.about)
        if (i.author.isNotBlank()) put("author", i.author)
        if (i.id == LanguageManager.currentId) put("on_screen", true)
    }

    private fun englishJson(src: LanguageManager.Source, i: Int): Any = when (val v = src.values[i]) {
        is Map<*, *> -> JSONObject(v.mapKeys { it.key.toString() })
        else -> v
    }

    private fun entryJson(lang: LanguageManager.Language, src: LanguageManager.Source, i: Int, withStatus: Boolean) = JSONObject().apply {
        val key = src.keys[i]
        put("key", key)
        put("en", englishJson(src, i))
        src.notes[key]?.let { put("note", it) }
        if (src.isPlural(i)) put("plural_forms", JSONArray(LanguageManager.pluralForms(lang.code)))
        lang.strings[key]?.let { v -> put("text", if (v is Map<*, *>) JSONObject(v.mapKeys { it.key.toString() }) else v) }
        if (withStatus) put("status", LanguageManager.status(lang, src, i).name.lowercase())
    }

    private fun stringsOf(o: JSONObject?): Map<String, Any?> {
        if (o == null) return emptyMap()
        val m = LinkedHashMap<String, Any?>()
        o.keys().forEach { k ->
            m[k] = when (val v = o.opt(k)) {
                JSONObject.NULL, null -> null
                is JSONObject -> v
                else -> v.toString()
            }
        }
        return m
    }

    fun call(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "language_list" -> {
                val list = LanguageManager.refreshList()
                json(JSONObject().apply {
                    put("on_screen", LanguageManager.currentId)
                    put("ai_reply", LanguageManager.aiReplyLanguage() ?: "not set")
                    put("languages", JSONArray().apply { list.forEach { put(infoJson(it)) } })
                })
            }
            "language_apply" -> {
                val lang = LanguageManager.find(args.optString("id")) ?: return error("No language \"${args.optString("id")}\". Use language_list.")
                if (!LanguageManager.apply(lang.id)) return error("${lang.name} could not be loaded.")
                text("The app is now in ${lang.label}.")
            }
            "language_sections" -> {
                val src = LanguageManager.source()
                val lang = args.optString("id").takeIf { it.isNotBlank() }?.let { LanguageManager.find(it) ?: return error("No language \"$it\".") }
                    ?: LanguageManager.load(LanguageManager.currentId)
                val total = LinkedHashMap<String, Int>()
                val done = HashMap<String, Int>()
                src.keys.indices.forEach { i ->
                    val s = src.section(i)
                    total[s] = (total[s] ?: 0) + 1
                    if (lang != null && LanguageManager.status(lang, src, i) == LanguageManager.Status.TRANSLATED) done[s] = (done[s] ?: 0) + 1
                }
                json(JSONObject().apply {
                    lang?.let { put("language", it.id) }
                    put("sections", JSONArray().apply {
                        total.forEach { (s, n) -> put(JSONObject().put("section", s).put("texts", n).apply { if (lang != null) put("translated", done[s] ?: 0) }) }
                    })
                })
            }
            "language_missing" -> {
                val lang = target(args) ?: return error("No language \"${args.optString("id")}\". Use language_list.")
                if (lang.id == LanguageManager.ENGLISH) return error("English is the source; pick or create another language.")
                val src = LanguageManager.source()
                val section = args.optString("section").trim()
                val limit = args.optInt("limit", 120).coerceIn(1, 400)
                val outdated = args.optBoolean("outdated", true)
                val todo = src.keys.indices.filter { i ->
                    (section.isEmpty() || src.keys[i].startsWith(section)) && when (LanguageManager.status(lang, src, i)) {
                        LanguageManager.Status.MISSING -> true
                        LanguageManager.Status.OUTDATED -> outdated
                        else -> false
                    }
                }
                json(JSONObject().apply {
                    put("language", lang.id); put("code", lang.code)
                    put("left", todo.size)
                    put("texts", JSONArray().apply { todo.take(limit).forEach { put(entryJson(lang, src, it, withStatus = true)) } })
                    if (todo.isEmpty()) put("note", "Nothing left to translate" + (if (section.isNotEmpty()) " in $section" else "") + ".")
                    else if (lang.builtIn) put("note", "${lang.name} is built in: language_duplicate it before translating.")
                })
            }
            "language_get" -> {
                val lang = target(args) ?: return error("No language \"${args.optString("id")}\". Use language_list.")
                val src = LanguageManager.source()
                val section = args.optString("section").trim()
                val keys = args.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                val search = args.optString("search").trim()
                val status = args.optString("status").lowercase().ifBlank { "all" }
                val offset = args.optInt("offset", 0).coerceAtLeast(0)
                val limit = args.optInt("limit", 200).coerceIn(1, 500)
                val hits = src.keys.indices.filter { i ->
                    val key = src.keys[i]
                    (section.isEmpty() || key.startsWith(section)) && (keys == null || key in keys) &&
                        (search.isEmpty() || key.contains(search, true) || src.text(i).contains(search, true) ||
                            lang.strings[key]?.toString()?.contains(search, true) == true) &&
                        (status == "all" || LanguageManager.status(lang, src, i).name.equals(status, true))
                }
                json(JSONObject().apply {
                    put("language", lang.id); put("total", hits.size); put("offset", offset)
                    put("texts", JSONArray().apply { hits.drop(offset).take(limit).forEach { put(entryJson(lang, src, it, withStatus = true)) } })
                })
            }
            "language_update" -> {
                val lang = target(args) ?: return error("No language \"${args.optString("id")}\". Use language_list.")
                if (lang.id == LanguageManager.ENGLISH) return error("English is the source and can't be changed; create a language.")
                if (lang.builtIn) return error("${lang.name} is built in: language_duplicate it first, then update the copy.")
                val r = LanguageManager.update(
                    lang.id, stringsOf(args.optJSONObject("strings")),
                    name = args.optString("name").takeIf { args.has("name") },
                    nativeName = args.optString("native_name").takeIf { args.has("native_name") },
                    code = args.optString("code").takeIf { args.has("code") },
                    rtl = if (args.has("rtl")) args.optBoolean("rtl") else null,
                    author = args.optString("author").takeIf { args.has("author") },
                    about = args.optString("about").takeIf { args.has("about") }
                )
                val after = LanguageManager.load(lang.id)?.let { LanguageManager.info(it) }
                json(JSONObject().apply {
                    put("saved", r.saved); if (r.removed > 0) put("removed", r.removed)
                    if (r.problems.isNotEmpty()) put("refused", JSONArray(r.problems))
                    if (r.warnings.isNotEmpty()) put("warnings", JSONArray(r.warnings))
                    if (r.unknownKeys.isNotEmpty()) put("unknown_keys", JSONArray(r.unknownKeys.take(50)))
                    after?.let { put("translated", it.translated); put("total", it.total); put("left", it.missing + it.outdated) }
                })
            }
            "language_create" -> {
                val code = args.optString("code").trim().ifBlank { return error("code is required, like ne or pt-BR") }
                val nm = args.optString("name").trim().ifBlank { return error("name is required, like Nepali") }
                val from = args.optString("from").trim().takeIf { it.isNotEmpty() }?.let { LanguageManager.find(it)?.id ?: return error("No language \"$it\" to copy.") }
                val lang = LanguageManager.create(code, nm, args.optString("native_name"), args.optBoolean("rtl", false), from)
                val applied = args.optBoolean("apply", true) && LanguageManager.apply(lang.id)
                json(JSONObject().put("id", lang.id).put("applied", applied)
                    .put("next", "language_missing with id=${lang.id}, then language_update with the translations"))
            }
            "language_duplicate" -> {
                val lang = target(args) ?: return error("No language \"${args.optString("id")}\".")
                val copy = LanguageManager.duplicate(lang.id, args.optString("name")) ?: return error("English has no texts of its own to copy; use language_create.")
                json(JSONObject().put("id", copy.id).put("name", copy.name))
            }
            "language_delete" -> {
                val lang = target(args) ?: return error("No language \"${args.optString("id")}\".")
                if (lang.builtIn) return error("${lang.name} is built in and can't be deleted.")
                LanguageManager.delete(lang.id)
                text("Deleted ${lang.name}.")
            }
            "language_export" -> {
                val lang = target(args) ?: return error("No language \"${args.optString("id")}\".")
                text(LanguageManager.export(lang.id) ?: return error("Could not export ${lang.name}."))
            }
            "language_import" -> {
                val (lang, r) = LanguageManager.import(args.optString("json"), args.optString("replace").takeIf { it.isNotBlank() })
                if (lang == null) return error(r.problems.joinToString("\n"))
                val applied = args.optBoolean("apply", false) && LanguageManager.apply(lang.id)
                json(JSONObject().apply {
                    put("id", lang.id); put("name", lang.name); put("texts", r.saved); put("applied", applied)
                    if (r.problems.isNotEmpty()) put("refused", JSONArray(r.problems.take(100)))
                    if (r.unknownKeys.isNotEmpty()) put("unknown_keys_kept", r.unknownKeys.size)
                })
            }
            "language_set_ai_reply" -> {
                when (args.optString("mode").lowercase()) {
                    "off" -> LanguageManager.chooseAiReply("")
                    "app" -> LanguageManager.chooseAiReply(LanguageManager.AI_REPLY_APP)
                    "language" -> LanguageManager.chooseAiReply(args.optString("language").trim().ifBlank { return error("Give the language name.") })
                    else -> return error("mode is off, app or language")
                }
                text(LanguageManager.aiReplyLanguage()?.let { "Agents now reply in $it (from the next message's context)." } ?: "Agents choose their reply language.")
            }
            else -> error("Unknown tool $name")
        }
    } catch (e: Exception) {
        error("${name} failed: ${e.message ?: e.javaClass.simpleName}")
    }
}
