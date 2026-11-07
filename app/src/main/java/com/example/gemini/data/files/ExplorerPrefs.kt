package com.example.gemini.data.files

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

enum class SortBy(val label: String) { NAME("Name"), DATE("Date modified"), SIZE("Size"), TYPE("Type") }

enum class OpenChoice(val label: String) { IDE("Code editor"), EXTERNAL("Other app"), PREVIEW("Preview") }

data class ExplorerSettings(
    val sortBy: SortBy = SortBy.NAME,
    val descending: Boolean = false,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = false,
    val grid: Boolean = false,
    val thumbnails: Boolean = true
)

/** Files screen preferences, kept in memory and on disk. */
object ExplorerPrefs {
    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(ExplorerSettings())
    val settings: StateFlow<ExplorerSettings> = _settings.asStateFlow()

    /** Last folder per explorer instance key (e.g. "screen", "picker"), so each reopens where it was left. */
    private val lastPaths = mutableMapOf<String, String>()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("file_explorer", Context.MODE_PRIVATE)
        prefs = p
        _settings.value = ExplorerSettings(
            sortBy = runCatching { SortBy.valueOf(p.getString("sort_by", SortBy.NAME.name)!!) }.getOrDefault(SortBy.NAME),
            descending = p.getBoolean("descending", false),
            foldersFirst = p.getBoolean("folders_first", true),
            showHidden = p.getBoolean("show_hidden", false),
            grid = p.getBoolean("grid", false),
            thumbnails = p.getBoolean("thumbnails", true)
        )
        p.all.forEach { (k, v) -> if (k.startsWith("last_path_") && v is String) lastPaths[k.removePrefix("last_path_")] = v }
    }

    fun update(transform: (ExplorerSettings) -> ExplorerSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs?.edit()
            ?.putString("sort_by", s.sortBy.name)
            ?.putBoolean("descending", s.descending)
            ?.putBoolean("folders_first", s.foldersFirst)
            ?.putBoolean("show_hidden", s.showHidden)
            ?.putBoolean("grid", s.grid)
            ?.putBoolean("thumbnails", s.thumbnails)
            ?.apply()
    }

    fun lastPath(key: String): String? = lastPaths[key]

    fun setLastPath(key: String, path: String) {
        lastPaths[key] = path
        prefs?.edit()?.putString("last_path_$key", path)?.apply()
    }

    // ----- "Always open .ext with …" -----

    fun rememberedChoice(ext: String): OpenChoice? =
        prefs?.getString("open_with_${ext.lowercase()}", null)?.let { runCatching { OpenChoice.valueOf(it) }.getOrNull() }

    fun rememberChoice(ext: String, choice: OpenChoice) {
        prefs?.edit()?.putString("open_with_${ext.lowercase()}", choice.name)?.apply()
    }

    fun rememberedExtensions(): List<Pair<String, OpenChoice>> =
        prefs?.all.orEmpty().mapNotNull { (k, v) ->
            if (!k.startsWith("open_with_") || v !is String) null
            else runCatching { k.removePrefix("open_with_") to OpenChoice.valueOf(v) }.getOrNull()
        }.sortedBy { it.first }

    fun forgetChoices() {
        val p = prefs ?: return
        val e = p.edit()
        p.all.keys.filter { it.startsWith("open_with_") }.forEach { e.remove(it) }
        e.apply()
    }

    // ----- recent folders -----

    fun recentFolders(): List<String> =
        runCatching { JSONArray(prefs?.getString("recent_folders", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }
            .getOrDefault(emptyList())

    fun addRecentFolder(path: String) {
        if (path.isBlank()) return
        val list = (listOf(path) + recentFolders().filter { it != path }).take(8)
        prefs?.edit()?.putString("recent_folders", JSONArray(list).toString())?.apply()
    }
}
