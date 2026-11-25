package ai.antigem.i18n

import org.json.JSONArray
import org.json.JSONObject

/**
 * language_mcp: lets a model list, switch, download, create, translate, import and export the app's languages (served by
 * AndroidLocalBridgeServer at /language). Everything goes through [LanguageManager] and [LanguageRepo], the same places
 * Settings → Language uses.
 */
object LanguageMcp {

    const val INSTRUCTIONS = "Translates AntiGem's interface. Every text the app shows has a key (like settings.dialog.themes) with " +
        "English as the source; a language gives its own text per key, and English shows for any key it leaves out. " +
        "The app ships only English: other languages are downloaded from the community repository (language_catalog lists " +
        "them, language_download installs or updates one) or made by the user. " +
        "language_list shows the installed languages and the one on screen; language_apply switches (the app redraws at once). " +
        "To translate: language_create (code, name, native_name), or pick a custom language from language_list; then repeat " +
        "language_missing (a batch of untranslated or outdated texts with their English) → language_update {key: translation} " +
        "until nothing is missing. Apply the language first and the user watches the app change as you go. Rules: keep every " +
        "{placeholder} exactly as in English (the app fills them in); keep button and tab labels short; keep names of apps, " +
        "agents, models, commands, file paths and code as they are; plural keys take an object of the forms the language uses " +
        "(zero, one, two, few, many, other; \"other\" is required; {count} is the number). Only custom languages can be changed: " +
        "downloaded (community) and built-in ones are read-only, so language_duplicate one first and edit the copy. Credit the " +
        "people who made a language in its authors. language_export returns the file to share (it can be sent to the community " +
        "repository). language_set_ai_reply sets the language agents reply in."

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
    private val authorsProp = "authors" to JSONObject().apply {
        put("type", "array")
        put("description", "Everyone who made it (replaces the list): [{\"name\": \"Sita Sharma\", \"github\": \"sita-sharma\", \"role\": \"Translation\"}]. " +
            "github is a username, role is short (Translation, Review, AI). Keep the people already listed.")
        put("items", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("name", prop("string", "Name"))
                put("github", prop("string", "GitHub username"))
                put("role", prop("string", "What they did"))
                put("url", prop("string", "https link"))
            })
            put("required", JSONArray(listOf("name")))
        })
    }
    private val variantProp = "variant" to prop("string", "Short label that tells packs of one language apart: Formal, Latin letters (empty for the main one)")
    private val descriptionProp = "description" to prop("string", "One or two lines about the translation (style, how it was made)")
    private val sectionProp = "section" to prop("string", "Only keys starting with this, e.g. \"settings\" or \"settings.dialog\" (see language_sections)")

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("language_list", "The installed languages (English, downloaded community ones and custom ones) with how many texts each has " +
            "translated, who made it, whether it can be edited, and the one on screen."))
        put(tool("language_catalog",
            "The community languages in the repository that can be downloaded, with who made them, how complete they are and whether " +
                "each is installed or has an update. Fetches the list from GitHub.", emptyList(),
            "search" to prop("string", "Only languages whose name, native name, code, variant or authors contain this"),
            "refresh" to prop("boolean", "Fetch the list again even if it was fetched a minute ago (default false)")))
        put(tool("language_download",
            "Download a community language from the repository, or update an installed one. It becomes read-only (duplicate it to edit).",
            listOf("id"),
            "id" to prop("string", "Pack id from language_catalog (like ne), or its name"),
            "apply" to prop("boolean", "Show the app in it right away (default false)")))
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
                "null or \"\" removes a translation. Texts with an unknown {placeholder} are refused and listed. Can also change the name, code, " +
                "direction, variant, description and authors. Only custom languages can be changed.",
            listOf("id"), idProp,
            "strings" to prop("object", "key → translation, e.g. {\"common.cancel\": \"रद्द करें\"}"),
            "name" to prop("string", "Name in English, e.g. Nepali"),
            "native_name" to prop("string", "Name in the language itself, e.g. नेपाली"),
            "code" to prop("string", "Language code like ne, pt-BR (used for plural rules)"),
            "rtl" to prop("boolean", "Written right to left (Arabic, Hebrew, Urdu, Persian…)"),
            variantProp, descriptionProp, authorsProp))
        put(tool("language_create", "Create a custom language. It starts empty (English shows until texts are added) or as a copy of another one.",
            listOf("code", "name"),
            "code" to prop("string", "Language code like ne, hi, pt-BR"),
            "name" to prop("string", "Name in English, e.g. Nepali"),
            "native_name" to prop("string", "Name in the language itself, e.g. नेपाली"),
            "rtl" to prop("boolean", "Written right to left"),
            "from" to prop("string", "Copy the texts of this language (its authors are kept)"),
            variantProp, descriptionProp, authorsProp,
            "apply" to prop("boolean", "Show the app in it right away (default true)")))
        put(tool("language_duplicate", "Copy a language (downloaded, built-in or custom) into a new custom one you can edit. The copy keeps its authors.", listOf("id"), idProp,
            "name" to prop("string", "Name of the copy")))
        put(tool("language_delete", "Delete a custom language, or remove a downloaded one (it can be downloaded again). Ask the user first.", listOf("id"), idProp))
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

    private fun originName(o: LanguageManager.Origin) = when (o) {
        LanguageManager.Origin.ENGLISH -> "english"
        LanguageManager.Origin.APP -> "built_in"
        LanguageManager.Origin.REPO -> "community"
        LanguageManager.Origin.USER -> "custom"
    }

    private fun authorsJson(list: List<LanguageManager.Author>) = JSONArray().apply {
        list.forEach { a ->
            put(JSONObject().apply {
                put("name", a.name)
                if (a.github.isNotBlank()) put("github", a.github)
                if (a.role.isNotBlank()) put("role", a.role)
                if (a.url.isNotBlank()) put("url", a.url)
            })
        }
    }

    private fun authorsOf(args: JSONObject): List<LanguageManager.Author>? {
        val a = args.optJSONArray("authors") ?: return null
        return (0 until a.length()).mapNotNull { n ->
            when (val v = a.opt(n)) {
                is JSONObject -> LanguageManager.Author(v.optString("name"), v.optString("github"), v.optString("url"), v.optString("role"))
                is String -> LanguageManager.Author(v)
                else -> null
            }
        }
    }

    private fun infoJson(i: LanguageManager.Info) = JSONObject().apply {
        put("id", i.id); put("name", i.name); put("code", i.code)
        if (i.nativeName.isNotBlank()) put("native_name", i.nativeName)
        if (i.variant.isNotBlank()) put("variant", i.variant)
        put("origin", originName(i.origin)); put("read_only", i.readOnly); if (i.rtl) put("rtl", true)
        put("translated", i.translated); put("total", i.total)
        if (i.outdated > 0) put("outdated", i.outdated)
        if (i.description.isNotBlank()) put("description", i.description)
        if (i.authors.isNotEmpty()) put("authors", authorsJson(i.authors))
        if (i.packId.isNotBlank()) put("pack_id", i.packId)
        if (i.basedOn.isNotBlank()) put("based_on", i.basedOn)
        if (i.origin == LanguageManager.Origin.REPO && LanguageRepo.stateOf(i.packId) == LanguageRepo.State.UPDATE) put("update_available", true)
        if (i.id == LanguageManager.currentId) put("on_screen", true)
    }

    private fun packJson(p: LanguageRepo.Pack) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("code", p.code)
        if (p.nativeName.isNotBlank()) put("native_name", p.nativeName)
        if (p.variant.isNotBlank()) put("variant", p.variant)
        if (p.rtl) put("rtl", true)
        if (p.description.isNotBlank()) put("description", p.description)
        put("authors", authorsJson(p.authors))
        put("translated", p.translated); put("total", p.total); put("percent", p.percent)
        if (p.outdated > 0) put("outdated", p.outdated)
        if (p.updated.isNotBlank()) put("updated", p.updated)
        put("size_kb", (p.size + 1023) / 1024)
        put("state", when (LanguageRepo.stateOf(p.id)) {
            LanguageRepo.State.AVAILABLE -> "not_installed"
            LanguageRepo.State.INSTALLED -> "installed"
            LanguageRepo.State.UPDATE -> "update_available"
        })
        if (LanguageRepo.stateOf(p.id) != LanguageRepo.State.AVAILABLE) put("app_id", p.localId)
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
                LanguageRepo.loadCached() // the saved community list: tells which downloaded languages have an update
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
                    else if (lang.readOnly) put("note", LanguageManager.readOnlyReason(lang) + ".")
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
                if (lang.readOnly) return error(LanguageManager.readOnlyReason(lang) + ".")
                val r = LanguageManager.update(
                    lang.id, stringsOf(args.optJSONObject("strings")),
                    name = args.optString("name").takeIf { args.has("name") },
                    nativeName = args.optString("native_name").takeIf { args.has("native_name") },
                    code = args.optString("code").takeIf { args.has("code") },
                    rtl = if (args.has("rtl")) args.optBoolean("rtl") else null,
                    variant = args.optString("variant").takeIf { args.has("variant") },
                    description = args.optString("description").takeIf { args.has("description") },
                    authors = authorsOf(args)
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
                val lang = LanguageManager.create(code, nm, args.optString("native_name"), args.optBoolean("rtl", false), from,
                    variant = args.optString("variant"), description = args.optString("description"), authors = authorsOf(args).orEmpty())
                val applied = args.optBoolean("apply", true) && LanguageManager.apply(lang.id)
                json(JSONObject().put("id", lang.id).put("applied", applied)
                    .put("next", "language_missing with id=${lang.id}, then language_update with the translations"))
            }
            "language_duplicate" -> {
                val lang = target(args) ?: return error("No language \"${args.optString("id")}\".")
                val copy = LanguageManager.duplicate(lang.id, args.optString("name")) ?: return error("English has no texts of its own to copy; use language_create.")
                json(JSONObject().put("id", copy.id).put("name", copy.name).put("next", "language_update with id=${copy.id}; add the user to authors if they change texts"))
            }
            "language_delete" -> {
                val lang = target(args) ?: return error("No language \"${args.optString("id")}\".")
                if (lang.origin == LanguageManager.Origin.ENGLISH || lang.origin == LanguageManager.Origin.APP) return error("${lang.label} is built in and can't be deleted.")
                LanguageManager.delete(lang.id)
                if (lang.origin == LanguageManager.Origin.REPO) {
                    LanguageRepo.removed(lang.packId)
                    text("Removed ${lang.label}. language_download can get it again.")
                } else text("Deleted ${lang.label}.")
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
            "language_catalog" -> {
                val r = kotlinx.coroutines.runBlocking { LanguageRepo.refresh(maxAgeMs = if (args.optBoolean("refresh")) 0 else 60_000) }
                val q = args.optString("search").trim()
                val list = LanguageRepo.packs.filter { p ->
                    q.isEmpty() || listOf(p.name, p.nativeName, p.code, p.variant, p.id).any { it.contains(q, true) } ||
                        p.authors.any { it.name.contains(q, true) || it.github.contains(q, true) }
                }
                json(JSONObject().apply {
                    put("repository", LanguageRepo.PAGE)
                    r.exceptionOrNull()?.let { put("error", it.message); if (LanguageRepo.packs.isNotEmpty()) put("note", "Showing the list saved on the phone.") }
                    put("languages", JSONArray().apply { list.forEach { put(packJson(it)) } })
                    if (list.isEmpty() && r.isSuccess) put("note", if (q.isEmpty()) "The repository has no languages yet. A custom language made with language_create can be shared there." else "No language matches \"$q\".")
                    val updates = LanguageRepo.updates()
                    if (updates.isNotEmpty()) put("updates", JSONArray(updates.map { it.id }))
                })
            }
            "language_download" -> {
                val q = args.optString("id").trim().ifBlank { return error("id is required (a pack id from language_catalog)") }
                if (LanguageRepo.find(q) == null) kotlinx.coroutines.runBlocking { LanguageRepo.refresh(maxAgeMs = 60_000) }
                val pack = LanguageRepo.find(q) ?: return error("No community language \"$q\". Use language_catalog.")
                val before = LanguageRepo.stateOf(pack.id)
                val r = kotlinx.coroutines.runBlocking { LanguageRepo.install(pack) }
                val lang = r.getOrElse { return error(it.message ?: "Download failed") }
                val applied = args.optBoolean("apply", false) && LanguageManager.apply(lang.id)
                val info = LanguageManager.info(lang)
                json(JSONObject().apply {
                    put("id", lang.id); put("name", lang.label)
                    put("result", if (before == LanguageRepo.State.AVAILABLE) "downloaded" else "updated")
                    put("translated", info.translated); put("total", info.total)
                    put("applied", applied || LanguageManager.currentId == lang.id)
                    put("authors", authorsJson(lang.authors))
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
