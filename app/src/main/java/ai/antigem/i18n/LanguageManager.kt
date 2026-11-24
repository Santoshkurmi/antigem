package ai.antigem.i18n

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The app's languages: English (the texts in the code, keys in assets/i18n/en.json), the built-in translations
 * (assets/i18n/<id>.json) and the user's own (files/i18n/languages/<id>.json, made in Settings → Language or by a model
 * through language_mcp). All use the same file format, so a user's file can be shared and shipped as a built-in one.
 *
 * Performance: in English nothing is loaded. Another language is read once into [I18n.table] from a pre-indexed cache
 * (files/i18n/cache), rebuilt only when the language file, the app or its keys change. Switching bumps [version],
 * which GeminiTheme hands down as a static composition local, so every screen redraws once in the new language
 * without losing its state; reading a text is never observed on its own.
 *
 * A language file:
 * ```
 * { "format": "antigem-language", "format_version": 1, "code": "hi", "name": "Hindi", "native_name": "हिन्दी",
 *   "rtl": false, "author": "", "about": "", "app_version": "2.0.5",
 *   "strings": { "common.cancel": "रद्द करें", "files.list.count": { "one": "{count} फ़ाइल", "other": "{count} फ़ाइलें" } },
 *   "sources": { "common.cancel": "77f8c1d" } }
 * ```
 * "sources" remembers which English each text was translated from, so a text whose English changed later shows as
 * outdated. Keys the app doesn't know (from another app version) are kept in the file and ignored.
 */
object LanguageManager {
    const val ENGLISH = "en"
    const val FORMAT = "antigem-language"
    // "AGL2": tables cached by builds before the key index fix were empty, so they are not read again
    private const val CACHE_MAGIC = 0x41474C32

    // ------------------------------------------------------------------ data

    /** English, the source: every key in [L]'s order with its text (String, or Map of plural forms) and notes. */
    class Source(val keys: List<String>, val values: List<Any>, val notes: Map<String, String>) {
        // `also`, not `apply`: inside apply, `keys` would be the new map's own (empty) key set
        val index: Map<String, Int> = HashMap<String, Int>(keys.size * 2).also { map -> keys.forEachIndexed { i, k -> map[k] = i } }
        fun isPlural(i: Int) = values[i] is Map<*, *>
        fun text(i: Int): String = when (val v = values[i]) {
            is Map<*, *> -> v["other"]?.toString().orEmpty()
            else -> v.toString()
        }
        fun hash(i: Int): String = hashOf(values[i])
        fun placeholders(i: Int): Set<String> = placeholdersOf(values[i])
        /** "settings.dialog" of "settings.dialog.themes": the screen or part of the app a text belongs to. */
        fun section(i: Int): String = sectionOf(keys[i])
    }

    class Language(
        val id: String,
        val code: String,
        val name: String,
        val nativeName: String,
        val rtl: Boolean,
        val builtIn: Boolean,
        val author: String = "",
        val about: String = "",
        val appVersion: String = "",
        /** key → String, or Map<String, String> of plural forms. */
        val strings: Map<String, Any> = emptyMap(),
        /** key → hash of the English the text was translated from. */
        val sources: Map<String, String> = emptyMap()
    ) {
        val label: String get() = if (nativeName.isNotBlank() && nativeName != name) "$name · $nativeName" else name
    }

    /** One row of the language list. */
    data class Info(
        val id: String, val code: String, val name: String, val nativeName: String, val rtl: Boolean, val builtIn: Boolean,
        val translated: Int, val outdated: Int, val total: Int, val author: String, val about: String
    ) {
        val label: String get() = if (nativeName.isNotBlank() && nativeName != name) "$name · $nativeName" else name
        val missing: Int get() = total - translated
    }

    enum class Status { TRANSLATED, MISSING, OUTDATED }

    /** What an edit or import did. */
    class Report(
        val saved: Int = 0,
        val removed: Int = 0,
        val problems: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val unknownKeys: List<String> = emptyList()
    )

    // ------------------------------------------------------------------ state (read by the UI)

    /** The language on screen. */
    var currentId by mutableStateOf(ENGLISH)
        private set
    /** Bumped whenever the texts on screen change (switch, or edits of the current language). */
    var version by mutableIntStateOf(0)
        private set
    /** The current language is written right to left. */
    var isRtl by mutableStateOf(false)
        private set
    /** Every language with its progress; filled by [refreshList] (off the main thread). */
    var languages by mutableStateOf<List<Info>>(emptyList())
        private set
    /** Which language agents reply in: "" (not set), [AI_REPLY_APP] (the app's language) or a language name. */
    var aiReply by mutableStateOf("")
        private set
    const val AI_REPLY_APP = "app"

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private var source: Source? = null
    private val parsed = HashMap<String, Language>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val bump = Runnable { version++ }

    // ------------------------------------------------------------------ start

    /** Before the first screen draws (Application.onCreate): loads the saved language, from its cache when it can. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences("app_language", Context.MODE_PRIVATE)
        prefs = p
        aiReply = p.getString("ai_reply", "").orEmpty()
        val id = p.getString("id", ENGLISH) ?: ENGLISH
        if (id != ENGLISH && !activate(id)) {
            android.util.Log.w("LanguageManager", "language $id could not be loaded, showing English")
        }
    }

    private fun ctx(): Context = appContext ?: error("LanguageManager.init was not called")

    private fun userDir() = File(ctx().filesDir, "i18n/languages").apply { mkdirs() }
    private fun cacheDir() = File(ctx().filesDir, "i18n/cache").apply { mkdirs() }
    private fun userFile(id: String) = File(userDir(), "$id.json")

    private fun builtInIds(): List<String> = runCatching {
        ctx().assets.list("i18n").orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.filter { it != ENGLISH }
    }.getOrDefault(emptyList()).sorted()

    private fun userIds(): List<String> =
        userDir().listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().map { it.name.removeSuffix(".json") }.sorted()

    // ------------------------------------------------------------------ reading

    /** English and its keys (read once, on first use). */
    fun source(): Source = synchronized(lock) {
        source ?: run {
            val o = JSONObject(ctx().assets.open("i18n/en.json").bufferedReader().use { it.readText() })
            val strings = o.getJSONObject("strings")
            val keys = ArrayList<String>(strings.length())
            val values = ArrayList<Any>(strings.length())
            strings.keys().forEach { k ->
                keys += k
                values += when (val v = strings.get(k)) {
                    is JSONObject -> formsOf(v)
                    else -> v.toString()
                }
            }
            val notes = HashMap<String, String>()
            o.optJSONObject("notes")?.let { n -> n.keys().forEach { notes[it] = n.optString(it) } }
            Source(keys, values, notes).also { source = it }
        }
    }

    /** A language by id or name (any case); English is a language too (built in, with no texts of its own). */
    fun find(idOrName: String?): Language? {
        val q = idOrName?.trim().orEmpty()
        if (q.isEmpty()) return null
        if (q.equals(ENGLISH, true) || q.equals("english", true)) return english()
        load(q)?.let { return it }
        val all = allIds().mapNotNull { load(it) }
        return all.firstOrNull { it.name.equals(q, true) || it.nativeName.equals(q, true) }
            ?: all.firstOrNull { it.code.equals(q, true) }
            ?: all.firstOrNull { it.name.contains(q, true) }
    }

    fun english() = Language(ENGLISH, ENGLISH, "English", "English", rtl = false, builtIn = true)

    private fun allIds(): List<String> = (builtInIds() + userIds()).distinct()

    /** Parses a language file (cached until it changes). A user file wins over a built-in one with the same id. */
    fun load(id: String): Language? = synchronized(lock) {
        if (id == ENGLISH) return english()
        parsed[id]?.let { return it }
        val file = userFile(id)
        val lang = runCatching {
            if (file.exists()) parse(JSONObject(file.readText()), id, builtIn = false)
            else if (id in builtInIds()) parse(JSONObject(ctx().assets.open("i18n/$id.json").bufferedReader().use { it.readText() }), id, builtIn = true)
            else null
        }.onFailure { android.util.Log.w("LanguageManager", "can't read language $id", it) }.getOrNull()
        lang?.also { parsed[id] = it }
    }

    private fun parse(o: JSONObject, id: String, builtIn: Boolean): Language {
        val strings = LinkedHashMap<String, Any>()
        o.optJSONObject("strings")?.let { s ->
            s.keys().forEach { k ->
                when (val v = s.get(k)) {
                    is JSONObject -> formsOf(v).takeIf { it.isNotEmpty() }?.let { strings[k] = it }
                    is String -> if (v.isNotEmpty()) strings[k] = v
                }
            }
        }
        val sources = HashMap<String, String>()
        o.optJSONObject("sources")?.let { s -> s.keys().forEach { sources[it] = s.optString(it) } }
        val code = o.optString("code").ifBlank { id }
        return Language(
            id = id, code = code,
            name = o.optString("name").ifBlank { code },
            nativeName = o.optString("native_name"),
            rtl = o.optBoolean("rtl", false),
            builtIn = builtIn,
            author = o.optString("author"), about = o.optString("about"), appVersion = o.optString("app_version"),
            strings = strings, sources = sources
        )
    }

    private fun formsOf(o: JSONObject): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        I18n.PLURAL_FORMS.forEach { f -> o.optString(f).takeIf { it.isNotEmpty() }?.let { m[f] = it } }
        return m
    }

    /** Where a text of [lang] stands against today's English. */
    fun status(lang: Language, src: Source, i: Int): Status {
        if (lang.id == ENGLISH) return Status.TRANSLATED
        val key = src.keys[i]
        val v = lang.strings[key] ?: return Status.MISSING
        if ((v is Map<*, *>) != src.isPlural(i)) return Status.MISSING
        val from = lang.sources[key]
        return if (from != null && from != src.hash(i)) Status.OUTDATED else Status.TRANSLATED
    }

    fun info(lang: Language, src: Source = source()): Info {
        var translated = 0
        var outdated = 0
        if (lang.id == ENGLISH) translated = src.keys.size
        else for (i in src.keys.indices) when (status(lang, src, i)) {
            Status.TRANSLATED -> translated++
            Status.OUTDATED -> { translated++; outdated++ }
            Status.MISSING -> {}
        }
        return Info(lang.id, lang.code, lang.name, lang.nativeName, lang.rtl, lang.builtIn, translated, outdated, src.keys.size, lang.author, lang.about)
    }

    /** Reads every language and its progress into [languages]. Call off the main thread. */
    fun refreshList(): List<Info> {
        val src = source()
        val list = listOf(info(english(), src)) + allIds().mapNotNull { load(it) }.map { info(it, src) }
            .sortedWith(compareBy<Info>({ !it.builtIn }, { it.name.lowercase() }))
        languages = list
        return list
    }

    /** The plural forms [code]'s grammar uses (always ending with "other"). */
    fun pluralForms(code: String): List<String> = runCatching {
        val kw = android.icu.text.PluralRules.forLocale(android.icu.util.ULocale.forLanguageTag(code)).keywords
        I18n.PLURAL_FORMS.filter { it in kw }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf("one", "other")

    // ------------------------------------------------------------------ switching

    /** Shows the app in [id] (English: [ENGLISH]); false when it can't be loaded. */
    fun apply(id: String): Boolean {
        val ok = if (id == ENGLISH) {
            I18n.table = null
            I18n.pluralOf = { if (it == 1) I18n.ONE else I18n.OTHER }
            currentId = ENGLISH
            isRtl = false
            true
        } else activate(id)
        if (ok) {
            prefs?.edit()?.putString("id", id)?.apply()
            refreshNow()
        }
        return ok
    }

    private fun activate(id: String): Boolean {
        val t = readCache(id) ?: synchronized(lock) {
            val lang = load(id) ?: return false
            val built = buildTable(lang, source())
            writeCache(id, lang, built)
            Table(built, lang.code, lang.rtl)
        }
        I18n.table = t.table
        I18n.pluralOf = pluralRule(t.code)
        currentId = id
        isRtl = t.rtl
        return true
    }

    private class Table(val table: Array<Any?>, val code: String, val rtl: Boolean)

    private fun buildTable(lang: Language, src: Source): Array<Any?> {
        check(src.keys.size == L.COUNT) { "en.json has ${src.keys.size} texts, the app ${L.COUNT}" }
        val t = arrayOfNulls<Any?>(L.COUNT)
        lang.strings.forEach { (k, v) ->
            val i = src.index[k] ?: return@forEach
            slot(src, i, v)?.let { t[i] = it }
        }
        return t
    }

    /** What goes into a table slot for [v] at [i]: a String, an Array of plural forms, or null when it doesn't fit. */
    private fun slot(src: Source, i: Int, v: Any?): Any? = when {
        v is String && !src.isPlural(i) -> v.takeIf { it.isNotEmpty() }
        v is Map<*, *> && src.isPlural(i) -> Array(I18n.PLURAL_FORMS.size) { f -> (v[I18n.PLURAL_FORMS[f]] as? String)?.takeIf { it.isNotEmpty() } }
        else -> null
    }

    private fun pluralRule(code: String): (Int) -> Int = runCatching {
        val rules = android.icu.text.PluralRules.forLocale(android.icu.util.ULocale.forLanguageTag(code))
        val f: (Int) -> Int = { n -> I18n.PLURAL_FORMS.indexOf(rules.select(n.toDouble())).takeIf { it >= 0 } ?: I18n.OTHER }
        f
    }.getOrElse { { n: Int -> if (n == 1) I18n.ONE else I18n.OTHER } }

    @Volatile private var pendingRedraw = false

    /** Redraws the screens if edits made with redraw = false are waiting (the editor calls it when it closes). */
    fun flushRedraw() {
        if (pendingRedraw) { pendingRedraw = false; refreshNow() }
    }

    /** Redraws the screens now (and drops a pending delayed redraw). */
    private fun refreshNow() {
        pendingRedraw = false
        main.removeCallbacks(bump)
        if (Looper.myLooper() == Looper.getMainLooper()) version++ else main.post(bump)
    }

    /** Redraws soon: many edits in a row (a model translating in batches) redraw once. */
    private fun refreshSoon() {
        main.removeCallbacks(bump)
        main.postDelayed(bump, 350)
    }

    // ------------------------------------------------------------------ cache

    private fun stampOf(id: String): String {
        val f = userFile(id)
        if (f.exists()) return "u:${f.length()}:${f.lastModified()}"
        val updated = runCatching { ctx().packageManager.getPackageInfo(ctx().packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return "a:$updated"
    }

    private fun readCache(id: String): Table? = runCatching {
        val f = File(cacheDir(), "$id.bin")
        if (!f.exists()) return null
        DataInputStream(ByteArrayInputStream(f.readBytes())).use { inp ->
            if (inp.readInt() != CACHE_MAGIC) return null
            if (inp.readUTF() != L.FINGERPRINT) return null
            if (inp.readUTF() != stampOf(id)) return null
            val code = inp.readUTF()
            val rtl = inp.readBoolean()
            val n = inp.readInt()
            if (n != L.COUNT) return null
            fun str(): String? {
                val len = inp.readInt()
                if (len < 0) return null
                val b = ByteArray(len)
                inp.readFully(b)
                return String(b, Charsets.UTF_8)
            }
            val t = arrayOfNulls<Any?>(n)
            for (i in 0 until n) when (inp.readByte().toInt()) {
                1 -> t[i] = str()
                2 -> t[i] = Array(I18n.PLURAL_FORMS.size) { str() }
            }
            Table(t, code, rtl)
        }
    }.getOrNull()

    private fun writeCache(id: String, lang: Language, t: Array<Any?>) {
        runCatching {
            val bytes = ByteArrayOutputStream(64 * 1024)
            DataOutputStream(bytes).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeUTF(L.FINGERPRINT)
                out.writeUTF(stampOf(id))
                out.writeUTF(lang.code)
                out.writeBoolean(lang.rtl)
                out.writeInt(t.size)
                fun str(s: String?) {
                    if (s == null) { out.writeInt(-1); return }
                    val b = s.toByteArray(Charsets.UTF_8)
                    out.writeInt(b.size)
                    out.write(b)
                }
                t.forEach { v ->
                    when (v) {
                        is String -> { out.writeByte(1); str(v) }
                        is Array<*> -> { out.writeByte(2); v.forEach { str(it as String?) } }
                        else -> out.writeByte(0)
                    }
                }
            }
            val f = File(cacheDir(), "$id.bin")
            val tmp = File(cacheDir(), "$id.bin.tmp")
            tmp.writeBytes(bytes.toByteArray())
            tmp.renameTo(f)
        }.onFailure { android.util.Log.w("LanguageManager", "can't write the cache of $id", it) }
    }

    private fun dropCache(id: String) {
        File(cacheDir(), "$id.bin").delete()
    }

    // ------------------------------------------------------------------ editing

    /**
     * Sets texts of a user language. [changes]: key → text (String), plural forms (Map or JSONObject), or null / ""
     * to remove the translation (English shows). Each text must keep to the English text's {placeholders}; a text
     * that adds an unknown one, or is a plain text where English has plural forms (or the other way round), is
     * refused and listed in the report. The current language redraws on screen right away.
     */
    fun update(
        id: String,
        changes: Map<String, Any?>,
        name: String? = null,
        nativeName: String? = null,
        code: String? = null,
        rtl: Boolean? = null,
        author: String? = null,
        about: String? = null,
        /** false: don't redraw the screens yet (the editor, while typing); [flushRedraw] does it later. */
        redraw: Boolean = true
    ): Report = synchronized(lock) {
        val lang = load(id) ?: return Report(problems = listOf("No language \"$id\""))
        if (lang.builtIn) return Report(problems = listOf("${lang.name} is built in and can't be changed: duplicate it first"))
        val src = source()
        val strings = LinkedHashMap(lang.strings)
        val sources = HashMap(lang.sources)
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()
        val unknown = ArrayList<String>()
        var saved = 0
        var removed = 0
        changes.forEach { (key, raw) ->
            val i = src.index[key]
            if (i == null) { unknown += key; return@forEach }
            val value: Any? = when (raw) {
                null -> null
                is JSONObject -> formsOf(raw).takeIf { it.isNotEmpty() }
                is Map<*, *> -> raw.entries.mapNotNull { (f, t) -> (f?.toString() ?: return@mapNotNull null) to (t?.toString() ?: return@mapNotNull null) }
                    .filter { it.first in I18n.PLURAL_FORMS && it.second.isNotEmpty() }.toMap().takeIf { it.isNotEmpty() }
                else -> raw.toString().takeIf { it.isNotEmpty() }
            }
            if (value == null) {
                if (strings.remove(key) != null) removed++
                sources.remove(key)
                return@forEach
            }
            checkText(src, i, value)?.let { problems += "$key: $it"; return@forEach }
            missingPlaceholders(src, i, value).takeIf { it.isNotEmpty() }?.let { m ->
                warnings += "$key: leaves out ${m.joinToString(" ") { "{$it}" }} (English shows it)"
            }
            strings[key] = value
            sources[key] = src.hash(i)
            saved++
        }
        val updated = Language(
            id = lang.id,
            code = code?.trim()?.takeIf { it.isNotEmpty() } ?: lang.code,
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: lang.name,
            nativeName = nativeName?.trim() ?: lang.nativeName,
            rtl = rtl ?: lang.rtl,
            builtIn = false,
            author = author?.trim() ?: lang.author,
            about = about?.trim() ?: lang.about,
            appVersion = ai.antigem.BuildConfig.VERSION_NAME,
            strings = strings, sources = sources
        )
        save(updated)
        if (currentId == id) {
            I18n.table = buildTable(updated, src)
            I18n.pluralOf = pluralRule(updated.code)
            isRtl = updated.rtl
            writeCache(id, updated, I18n.table!!)
            if (redraw) refreshSoon() else pendingRedraw = true
        }
        Report(saved, removed, problems, warnings, unknown)
    }

    /** Why [value] can't be the text of key [i], or null when it fits. */
    fun checkText(src: Source, i: Int, value: Any): String? {
        val plural = src.isPlural(i)
        if (plural && value !is Map<*, *>) return "English has plural forms here: give an object like {\"one\": …, \"other\": …}"
        if (!plural && value is Map<*, *>) return "English is a plain text here, not plural forms"
        if (value is Map<*, *> && value["other"] == null) return "plural forms need \"other\""
        val allowed = src.placeholders(i)
        val extra = placeholdersOf(value) - allowed
        if (extra.isNotEmpty()) return "unknown placeholder ${extra.joinToString(" ") { "{$it}" }}" +
            (if (allowed.isEmpty()) " (English has none)" else " (English has ${allowed.joinToString(" ") { "{$it}" }})")
        return null
    }

    fun missingPlaceholders(src: Source, i: Int, value: Any): Set<String> =
        src.placeholders(i) - placeholdersOf(value) - (if (src.isPlural(i)) setOf("count") else emptySet())

    /** A new, empty user language (English shows until texts are added), or a copy of [from]'s texts. */
    fun create(code: String, name: String, nativeName: String = "", rtl: Boolean = false, from: String? = null, author: String = "", about: String = ""): Language =
        synchronized(lock) {
            val base = from?.let { load(it) }?.takeIf { it.id != ENGLISH }
            val id = freeId(name.ifBlank { code })
            val lang = Language(
                id = id, code = code.trim().ifBlank { "xx" }, name = name.trim().ifBlank { code.trim() }, nativeName = nativeName.trim(),
                rtl = rtl, builtIn = false, author = author, about = about, appVersion = ai.antigem.BuildConfig.VERSION_NAME,
                strings = base?.strings ?: emptyMap(), sources = base?.sources ?: emptyMap()
            )
            save(lang)
            lang
        }

    fun duplicate(id: String, name: String? = null): Language? = synchronized(lock) {
        val lang = load(id)?.takeIf { it.id != ENGLISH } ?: return null
        create(lang.code, name?.takeIf { it.isNotBlank() } ?: "${lang.name} (copy)", lang.nativeName, lang.rtl, from = lang.id, author = lang.author, about = lang.about)
    }

    /** Deletes a user language; the app goes back to English if it was on screen. */
    fun delete(id: String): Boolean = synchronized(lock) {
        val lang = load(id) ?: return false
        if (lang.builtIn) return false
        userFile(id).delete()
        parsed.remove(id)
        dropCache(id)
        if (currentId == id) apply(ENGLISH)
        true
    }

    private fun freeId(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "language" }.take(40)
        val taken = (allIds() + ENGLISH).toSet()
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    private fun save(lang: Language) {
        parsed[lang.id] = lang
        val f = userFile(lang.id)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(toJson(lang, source()))
        tmp.renameTo(f)
        dropCache(lang.id)
        // the list's progress follows on its next refresh; keep the row's name in step now
        languages = languages.map { if (it.id == lang.id) info(lang) else it }.let { list ->
            if (list.any { it.id == lang.id }) list else list + info(lang)
        }
    }

    // ------------------------------------------------------------------ files

    /** The language as a file (to share, keep, or ship as a built-in language: assets/i18n/<code>.json). */
    fun export(id: String): String? {
        val lang = load(id) ?: return null
        return toJson(if (lang.id == ENGLISH) englishAsLanguage() else lang, source())
    }

    /** English as a language file: a template with every key, for translating outside the app. */
    private fun englishAsLanguage(): Language {
        val src = source()
        return Language(ENGLISH, ENGLISH, "English", "English", false, true, appVersion = ai.antigem.BuildConfig.VERSION_NAME,
            strings = src.keys.indices.associate { src.keys[it] to src.values[it] })
    }

    /**
     * Adds a language from a file made by [export] (or by hand). It becomes a user language; [replaceId] overwrites
     * that user language instead. Texts that don't fit (see [checkText]) are left out and listed.
     */
    fun import(json: String, replaceId: String? = null): Pair<Language?, Report> = synchronized(lock) {
        val o = runCatching { JSONObject(json.trim().removePrefix("﻿")) }.getOrElse {
            return null to Report(problems = listOf("Not a language file: ${it.message}"))
        }
        if (o.optJSONObject("strings") == null) return null to Report(problems = listOf("Not a language file: it has no \"strings\""))
        val read = parse(o, "import", builtIn = false)
        val src = source()
        val strings = LinkedHashMap<String, Any>()
        val problems = ArrayList<String>()
        val unknown = ArrayList<String>()
        read.strings.forEach { (k, v) ->
            val i = src.index[k]
            if (i == null) { unknown += k; strings[k] = v; return@forEach } // kept: may be for another app version
            val why = checkText(src, i, v)
            if (why != null) problems += "$k: $why" else strings[k] = v
        }
        val target = replaceId?.let { load(it) }?.takeIf { !it.builtIn }
        val id = target?.id ?: freeId(read.name.ifBlank { read.code })
        val lang = Language(id, read.code, read.name, read.nativeName, read.rtl, false, read.author, read.about,
            read.appVersion.ifBlank { ai.antigem.BuildConfig.VERSION_NAME }, strings, read.sources.filterKeys { it in strings })
        save(lang)
        if (currentId == id) activate(id).also { refreshNow() }
        lang to Report(saved = strings.size - unknown.size, problems = problems, unknownKeys = unknown)
    }

    /** Pretty JSON, keys in the app's order (unknown ones last), not escaping what needn't be. */
    private fun toJson(lang: Language, src: Source): String {
        val sb = StringBuilder(64 * 1024)
        fun q(s: String) {
            sb.append('"')
            s.forEach { c ->
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }
        fun value(v: Any) {
            if (v is Map<*, *>) {
                sb.append("{ ")
                var first = true
                I18n.PLURAL_FORMS.forEach { f ->
                    val t = v[f] as? String ?: return@forEach
                    if (!first) sb.append(", ")
                    q(f); sb.append(": "); q(t)
                    first = false
                }
                sb.append(" }")
            } else q(v.toString())
        }
        sb.append("{\n")
        sb.append("  \"format\": \"$FORMAT\",\n  \"format_version\": 1,\n")
        sb.append("  \"code\": "); q(lang.code); sb.append(",\n")
        sb.append("  \"name\": "); q(lang.name); sb.append(",\n")
        sb.append("  \"native_name\": "); q(lang.nativeName); sb.append(",\n")
        sb.append("  \"rtl\": ${lang.rtl},\n")
        sb.append("  \"author\": "); q(lang.author); sb.append(",\n")
        sb.append("  \"about\": "); q(lang.about); sb.append(",\n")
        sb.append("  \"app_version\": "); q(lang.appVersion.ifBlank { ai.antigem.BuildConfig.VERSION_NAME }); sb.append(",\n")
        val ordered = lang.strings.keys.sortedBy { src.index[it] ?: Int.MAX_VALUE }
        sb.append("  \"strings\": {")
        ordered.forEachIndexed { n, k ->
            sb.append(if (n == 0) "\n    " else ",\n    ")
            q(k); sb.append(": "); value(lang.strings.getValue(k))
        }
        sb.append(if (ordered.isEmpty()) "},\n" else "\n  },\n")
        val withSources = ordered.filter { lang.sources[it] != null }
        sb.append("  \"sources\": {")
        withSources.forEachIndexed { n, k ->
            sb.append(if (n == 0) "\n    " else ",\n    ")
            q(k); sb.append(": "); q(lang.sources.getValue(k))
        }
        sb.append(if (withSources.isEmpty()) "}\n" else "\n  }\n")
        sb.append("}\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ the language agents reply in

    /** "" (not set), [AI_REPLY_APP] (follow the app's language) or a language name like "Nepali". */
    fun chooseAiReply(value: String) {
        aiReply = value.trim()
        prefs?.edit()?.putString("ai_reply", aiReply)?.apply()
    }

    /** The language every chat is told to reply in, or null (the agent picks, normally the user's). */
    fun aiReplyLanguage(): String? = when (val v = aiReply) {
        "" -> null
        AI_REPLY_APP -> if (currentId == ENGLISH) null else load(currentId)?.let { l ->
            if (l.nativeName.isNotBlank() && !l.nativeName.equals(l.name, true)) "${l.name} (${l.nativeName})" else l.name
        }
        else -> v
    }

    /** The part of the app context that tells agents the reply language, or null. */
    fun aiReplyContext(h: String = "##"): String? = aiReplyLanguage()?.let { lang ->
        "$h Reply language\nWrite to the user in $lang by default: replies, questions, plans and summaries. Switch only when the user " +
            "writes in another language or asks you to. Code, commands, file names, paths and technical terms stay as they are."
    }

    // ------------------------------------------------------------------ helpers

    private val placeholderRegex = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)\\}")

    fun placeholdersOf(v: Any?): Set<String> = when (v) {
        is Map<*, *> -> v.values.flatMap { t -> placeholderRegex.findAll(t.toString()).map { it.groupValues[1] } }.toSet()
        null -> emptySet()
        else -> placeholderRegex.findAll(v.toString()).map { it.groupValues[1] }.toSet()
    }

    fun hashOf(v: Any?): String = Integer.toHexString(
        when (v) {
            is Map<*, *> -> I18n.PLURAL_FORMS.joinToString("\u0000") { v[it]?.toString().orEmpty() }.hashCode()
            else -> v.toString().hashCode()
        }
    )

    fun sectionOf(key: String): String = key.substringBeforeLast('.', key)
}
