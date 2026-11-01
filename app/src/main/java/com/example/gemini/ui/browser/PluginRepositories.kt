package com.example.gemini.ui.browser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** A public GitHub repository holding plugin folders; [ref] is a branch or tag, blank for the default branch. */
data class PluginRepo(val owner: String, val name: String, val ref: String = "") {
    val slug get() = "$owner/$name"
    val label get() = if (ref.isBlank()) slug else "$slug@$ref"
}

/** One plugin a repository offers; [version] comes from the index, or from its manifest when the index has none. */
data class RepoPlugin(val path: String, val id: String, val name: String, val version: String, val description: String, val iconUrl: String)

data class RepoListing(val title: String, val plugins: List<RepoPlugin>, val fromIndex: Boolean)

/** Dotted numeric versions, as shown and compared in the plugin screens. */
object PluginVersions {
    fun label(version: String) = if (version.isBlank()) "no version" else "v" + version.removePrefix("v")

    /** Part-by-part comparison; null when either version is missing or not numeric. */
    fun compare(a: String, b: String): Int? {
        if (a.isBlank() || b.isBlank()) return null
        val x = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: return null }
        val y = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: return null }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 }); if (d != 0) return d }
        return 0
    }
}

/**
 * The user's plugin repositories and what each offers. Listing (from a repo's `antigem-plugins.json`, or its
 * top-level folders with a manifest.json) and installing a single folder are done by the IDE bridge.
 */
object PluginRepositories {
    const val INDEX = "antigem-plugins.json"
    private const val PREFS = "plugin_repositories"
    private val NAME = "[A-Za-z0-9_.-]+"

    val repos = mutableStateListOf<PluginRepo>()
    /** Latest listing and error per [PluginRepo.label]; [loading] holds labels being refreshed. */
    val listings = mutableStateMapOf<String, RepoListing>()
    val errors = mutableStateMapOf<String, String>()
    val loading = mutableStateListOf<String>()

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("repos", null) ?: return
        runCatching { val array = JSONArray(stored); repos.addAll((0 until array.length()).map { parse(array.getString(it)) }) }
    }

    /** Accepts `owner/repo`, `owner/repo@branch`, `https://github.com/owner/repo` and `…/tree/branch` URLs. */
    fun parse(input: String): PluginRepo {
        val text = input.trim().removeSuffix("/").removeSuffix(".git").replace(Regex("^(https?://)?(www\\.)?github\\.com/"), "")
        val tree = Regex("^($NAME)/($NAME)/tree/(.+)$").find(text)
        val plain = Regex("^($NAME)/($NAME)(?:@(.+))?$").find(text)
        val match = tree ?: plain ?: throw IllegalArgumentException("Use owner/repo, owner/repo@branch or a github.com repository URL")
        val ref = match.groupValues[3]
        require(ref.none { it.isWhitespace() } && ref.split('/').none { it.isEmpty() || it == ".." } || ref.isEmpty()) { "Invalid branch or tag: $ref" }
        return PluginRepo(match.groupValues[1], match.groupValues[2], ref)
    }

    fun add(repo: PluginRepo) {
        require(repos.none { it.label.equals(repo.label, ignoreCase = true) }) { "${repo.label} is already added" }
        repos.add(repo); persist()
    }

    fun remove(repo: PluginRepo) {
        repos.remove(repo); listings.remove(repo.label); errors.remove(repo.label); persist()
    }

    private fun persist() {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("repos", JSONArray(repos.map { it.label }).toString()).apply()
    }

    suspend fun refreshAll() = coroutineScope { repos.toList().map { async { refresh(it) } }.awaitAll() }

    suspend fun refresh(repo: PluginRepo) {
        if (repo.label in loading) return
        withContext(Dispatchers.Main) { loading.add(repo.label) }
        val result = runCatching { PluginBridge.repoList(repo.label) }
        withContext(Dispatchers.Main) {
            result.onSuccess { listings[repo.label] = it; errors.remove(repo.label) }
                .onFailure { errors[repo.label] = it.message ?: "Could not read ${repo.label}" }
            loading.remove(repo.label)
        }
    }

    /** The installed plugin a repository entry corresponds to: same repo and folder, else the same plugin ID. */
    fun installedFor(repo: PluginRepo, plugin: RepoPlugin): BrowserExtension? {
        val installed = BrowserExtensionManager.instance.extensions
        return installed.firstOrNull { it.source?.repo.equals(repo.slug, ignoreCase = true) && it.source?.path == plugin.path }
            ?: plugin.id.takeIf { it.isNotBlank() }?.let { id -> installed.firstOrNull { it.id == id } }
    }

    /** A newer version of [extension] in the repository it was installed from, if one has been seen. */
    fun updateFor(extension: BrowserExtension): RepoPlugin? {
        val source = extension.source ?: return null
        val repo = repos.firstOrNull { it.slug.equals(source.repo, ignoreCase = true) && it.ref == source.ref } ?: return null
        val offered = listings[repo.label]?.plugins?.firstOrNull { it.path == source.path } ?: return null
        return offered.takeIf { (PluginVersions.compare(it.version, extension.version) ?: 0) > 0 }
    }

    suspend fun install(repo: PluginRepo, plugin: RepoPlugin, folder: String, updateId: String?, asNew: Boolean = false): BrowserExtension =
        BrowserExtensionManager.instance.installFromRepo(repo.label, plugin.path, folder, updateId, asNew)
}
