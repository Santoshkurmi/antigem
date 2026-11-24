package ai.antigem.ui.ide

import ai.antigem.data.daemon.FileNode
import ai.antigem.data.daemon.IdeApiClient
import ai.antigem.data.daemon.TreeLevelResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.antigem.i18n.L

/** One row of the sidebar's tree: a file or folder, a folder still loading, or a note under a folder. */
sealed class TreeRow(val key: String, val depth: Int) {
    class Item(val node: FileNode, depth: Int, val expanded: Boolean) : TreeRow(node.path, depth)
    class Loading(dir: String, depth: Int) : TreeRow("loading:$dir", depth)
    class Note(dir: String, depth: Int, val text: String, val isError: Boolean) : TreeRow("note:$dir", depth)
}

/**
 * The IDE sidebar's file tree, read one folder at a time: the project folder first, then each folder when it is opened,
 * so a project of any size costs only the folders on screen. [refreshVisible] re-checks the shown folders; the bridge
 * answers "unchanged" from a folder's change time without reading it, so checking every few seconds is cheap.
 * Used from the main thread only.
 */
@Stable
class IdeFileTreeState {
    private class Listing(val entries: List<FileNode>, val truncated: Boolean, val error: String? = null)

    var root by mutableStateOf<String?>(null)
        private set
    private var expanded by mutableStateOf<Set<String>>(emptySet())
    private val listings = mutableStateMapOf<String, Listing>()
    private val etags = HashMap<String, String?>()
    private val inFlight = HashSet<String>()

    /** Folders asked to be read again while a read was already running (a file just created in them). */
    private val rerun = HashSet<String>()

    /** Bumped when the project changes, so an answer for the old project is dropped. */
    private var generation = 0

    /** An older bridge sends whole trees: then only the project folder is checked, and its answer fills every folder. */
    private var legacy = false

    /** The rows to show, open folders unfolded in place. */
    val rows: List<TreeRow> by derivedStateOf { flatten() }

    /** The project folder's entries; null while it is first read or when that failed (see [rootError]). */
    val rootEntries: List<FileNode>? get() = root?.let { r -> listings[r]?.takeIf { it.error == null }?.entries }
    val rootError: String? get() = root?.let { listings[it]?.error }

    /** How many entries a folder holds, when it has been read. */
    fun childCount(dir: String): Int? = listings[dir]?.takeIf { it.error == null }?.entries?.size

    private fun flatten(): List<TreeRow> {
        val r = root ?: return emptyList()
        val out = ArrayList<TreeRow>()
        fun walk(dir: String, depth: Int) {
            val listing = listings[dir]
            when {
                listing == null -> out += TreeRow.Loading(dir, depth)
                listing.error != null -> out += TreeRow.Note(dir, depth, listing.error, isError = true)
                else -> {
                    for (node in listing.entries) {
                        val open = node.isDir && node.path in expanded
                        out += TreeRow.Item(node, depth, open)
                        if (open) walk(node.path, depth + 1)
                    }
                    if (listing.truncated) {
                        out += TreeRow.Note(dir, depth, L.ide.fileTreeState.onlyTheFirstEntriescountEntriesAre(listing.entries.size), isError = false)
                    } else if (listing.entries.isEmpty() && dir != r) {
                        out += TreeRow.Note(dir, depth, L.ide.fileTreeState.emptyFolder, isError = false)
                    }
                }
            }
        }
        walk(r, 0)
        return out
    }

    /** Shows [path]'s tree; a different project starts from scratch. */
    suspend fun setRoot(path: String?) {
        if (path != root) {
            generation++
            root = path
            listings.clear()
            etags.clear()
            rerun.clear()
            expanded = emptySet()
            legacy = false
        }
        if (path != null) load(path, force = false)
    }

    /** Opens or closes a folder; a folder opened for the first time (or that failed before) is read. */
    suspend fun toggle(path: String) {
        if (path in expanded) {
            expanded = expanded - path
            return
        }
        expanded = expanded + path
        val listing = listings[path]
        if (listing == null || listing.error != null) load(path, force = true)
    }

    suspend fun expand(path: String) {
        if (path != root && path !in expanded) toggle(path)
    }

    /** Re-checks the folders on screen: the project folder and the open folders inside it. [force] reads them all again. */
    suspend fun refreshVisible(force: Boolean = false) {
        val r = root ?: return
        if (legacy) {
            load(r, force)
            return
        }
        for (dir in visibleDirs()) load(dir, force)
    }

    /** After a change made from the app: re-reads these folders now. A null folder (unknown parent) re-reads all shown ones. */
    suspend fun reload(vararg dirs: String?) {
        val r = root ?: return
        if (legacy || dirs.any { it == null }) {
            refreshVisible(force = true)
            return
        }
        for (d in dirs.filterNotNull().distinct()) if (d == r || listings.containsKey(d)) load(d, force = true)
    }

    /** Forgets a deleted or renamed folder and everything under it. */
    fun forget(path: String) {
        val prefix = path.trimEnd('/') + "/"
        fun under(p: String) = p == path || p.startsWith(prefix)
        expanded = expanded.filterNot(::under).toSet()
        listings.keys.filter(::under).forEach {
            listings.remove(it)
            etags.remove(it)
        }
    }

    private fun visibleDirs(): List<String> {
        val r = root ?: return emptyList()
        val out = mutableListOf(r)
        fun walk(dir: String) {
            listings[dir]?.entries?.forEach { n ->
                if (n.isDir && n.path in expanded) {
                    out += n.path
                    walk(n.path)
                }
            }
        }
        walk(r)
        return out
    }

    private suspend fun load(dir: String, force: Boolean) {
        if (!inFlight.add(dir)) {
            if (force) rerun.add(dir)
            return
        }
        val gen = generation
        try {
            val old = listings[dir]
            val etag = if (force || old == null || old.error != null) null else etags[dir]
            val res = IdeApiClient.listTreeLevel(dir, etag)
            if (gen != generation) return
            when (res) {
                is TreeLevelResult.NotModified -> Unit
                // a folder already shown keeps its entries while the bridge is away
                is TreeLevelResult.Failed -> if (old == null || old.error != null) listings[dir] = Listing(emptyList(), false, res.message)
                is TreeLevelResult.Listing -> {
                    etags[dir] = res.etag
                    if (res.legacy) {
                        legacy = true
                        seed(res.entries)
                    }
                    // only a real change touches the state the list draws from
                    if (old == null || old.error != null || old.truncated != res.truncated || old.entries != res.entries) {
                        listings[dir] = Listing(res.entries, res.truncated)
                    }
                }
            }
        } finally {
            inFlight.remove(dir)
        }
        if (rerun.remove(dir) && gen == generation) load(dir, force = true)
    }

    /** An older bridge's whole tree: every folder it filled in is known without asking again. */
    private fun seed(nodes: List<FileNode>) {
        for (n in nodes) {
            if (n.isDir && n.children.isNotEmpty()) {
                val old = listings[n.path]
                if (old == null || old.entries != n.children) listings[n.path] = Listing(n.children, false)
                seed(n.children)
            }
        }
    }
}
