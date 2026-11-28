package ai.antigem.data.netcap

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the Network page and its floating bubble show: the engine state, the traffic rows of the current filter (kept
 * live through [NetcapApi.live] while someone watches: [watch]), and the paused breakpoint items. One instance for the
 * app, so the list and its filter survive leaving the page.
 */
object NetStore {
    private const val TAG = "NetStore"
    /** Rows kept in memory; older ones come back with [loadMore]. */
    private const val WINDOW = 6000
    private const val PAGE = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(NetState())
    val state: StateFlow<NetState> = _state.asStateFlow()

    private val _rows = MutableStateFlow<List<NetRow>>(emptyList())
    /** Rows of [filter], newest first (or in [sort]'s order; live changes only reorder the newest-first list). */
    val rows: StateFlow<List<NetRow>> = _rows.asStateFlow()

    private val _total = MutableStateFlow(0)
    /** How many flows match [filter] in the session (the list may hold fewer). */
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _paused = MutableStateFlow<List<NetPaused>>(emptyList())
    val paused: StateFlow<List<NetPaused>> = _paused.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Bumped on every batch: the group view refreshes on it (throttled). */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    private val _sort = MutableStateFlow("newest")
    val sort: StateFlow<String> = _sort.asStateFlow()

    /** Frozen: live rows are kept aside (counted in [held]) until unfrozen, so the list holds still while reading. */
    private val _frozen = MutableStateFlow(false)
    val frozen: StateFlow<Boolean> = _frozen.asStateFlow()
    private val _held = MutableStateFlow(0)
    val held: StateFlow<Int> = _held.asStateFlow()

    private val list = ArrayList<NetRow>() // newest first (by id) when sort == newest
    private var next: String = ""
    private var heldRows = LinkedHashMap<Long, NetRow>()
    private var heldRemoved = HashSet<Long>()

    private val watchers = AtomicInteger(0)
    private var liveJob: Job? = null

    /** Someone shows the data (the page, the bubble): keeps the live stream open until [unwatch]. */
    fun watch() {
        if (watchers.incrementAndGet() == 1) restart()
    }

    fun unwatch() {
        if (watchers.decrementAndGet() <= 0) {
            watchers.set(0)
            liveJob?.cancel()
            liveJob = null
            _connected.value = false
        }
    }

    fun setFilter(q: String) {
        val t = q.trim()
        if (t == _filter.value) return
        _filter.value = t
        if (watchers.get() > 0) restart()
    }

    fun setSort(s: String) {
        if (s == _sort.value) return
        _sort.value = s
        if (watchers.get() > 0) restart()
    }

    fun setFrozen(on: Boolean) {
        _frozen.value = on
        if (!on) scope.launch {
            mutex.withLock {
                val rows = heldRows.values.toList()
                val removed = heldRemoved.toList()
                heldRows = LinkedHashMap(); heldRemoved = HashSet(); _held.value = 0
                applyLocked(rows, removed)
            }
        }
    }

    /** Reloads from the engine (after a session switch, a clear, or "reload"). */
    fun reload() {
        if (watchers.get() > 0) restart() else scope.launch { runCatching { loadFirst() } }
    }

    private fun restart() {
        liveJob?.cancel()
        liveJob = scope.launch { liveLoop() }
    }

    private suspend fun liveLoop() {
        var backoff = 500L
        while (scope.isActive && watchers.get() > 0) {
            val st = NetcapManager.state.value
            if (st.engine != NetcapManager.Engine.RUNNING) {
                _connected.value = false
                delay(700)
                continue
            }
            try {
                var first = true
                NetcapApi.live("", _filter.value).buffer(Channel.UNLIMITED).collect { ev ->
                    when (ev) {
                        is LiveEvent.State -> {
                            val prev = _state.value
                            _state.value = ev.state
                            if (first || prev.sessionId != ev.state.sessionId) {
                                first = false
                                loadFirst()
                                loadPaused()
                            }
                            _connected.value = true
                            _error.value = null
                            backoff = 500L
                        }
                        is LiveEvent.Batch -> {
                            ev.state?.let { s ->
                                val prev = _state.value
                                _state.value = s
                                if (prev.sessionId.isNotEmpty() && prev.sessionId != s.sessionId) loadFirst()
                            }
                            ev.paused?.let { _paused.value = it }
                            if (ev.gap) loadFirst() else if (ev.rows.isNotEmpty() || ev.removed.isNotEmpty()) apply(ev.rows, ev.removed)
                            _tick.value = _tick.value + 1
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.d(TAG, "live: ${e.message}")
                _error.value = e.message
            }
            _connected.value = false
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5_000)
        }
    }

    private suspend fun loadFirst() {
        _loading.value = true
        try {
            val r = NetcapApi.get("/v1/ui/rows", mapOf("q" to _filter.value, "limit" to PAGE, "sort" to _sort.value))
            val rows = r.optJSONArray("rows").objects().map(NetRow::parse)
            mutex.withLock {
                list.clear(); list.addAll(rows)
                next = r.optString("next")
                heldRows = LinkedHashMap(); heldRemoved = HashSet(); _held.value = 0
                _total.value = r.optInt("total", rows.size)
                _rows.value = ArrayList(list)
            }
        } finally {
            _loading.value = false
        }
    }

    /** The next page of older rows (when the list is scrolled to its end). */
    fun loadMore() {
        if (next.isEmpty() || _loading.value) return
        scope.launch {
            _loading.value = true
            try {
                val r = NetcapApi.get("/v1/ui/rows", mapOf("q" to _filter.value, "limit" to PAGE, "sort" to _sort.value, "cursor" to next))
                val rows = r.optJSONArray("rows").objects().map(NetRow::parse)
                mutex.withLock {
                    val have = list.mapTo(HashSet()) { it.id }
                    rows.forEach { if (it.id !in have) list.add(it) }
                    next = r.optString("next")
                    _rows.value = ArrayList(list)
                }
            } catch (e: Throwable) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    val hasMore: Boolean get() = next.isNotEmpty()

    private suspend fun apply(rows: List<NetRow>, removed: List<Long>) = mutex.withLock {
        if (_frozen.value) {
            rows.forEach { r ->
                // rows already shown still update in place; new ones wait
                val i = indexOf(r.id)
                if (i >= 0) list[i] = r else heldRows[r.id] = r
            }
            heldRemoved.addAll(removed)
            _held.value = heldRows.size
            _rows.value = ArrayList(list)
        } else applyLocked(rows, removed)
    }

    private fun applyLocked(rows: List<NetRow>, removed: List<Long>) {
        var added = 0
        if (removed.isNotEmpty()) {
            val gone = removed.toHashSet()
            val before = list.size
            list.removeAll { it.id in gone }
            _total.value = (_total.value - (before - list.size)).coerceAtLeast(0)
        }
        for (r in rows.sortedBy { it.id }) {
            val i = indexOf(r.id)
            if (i >= 0) {
                list[i] = r
            } else if (_sort.value == "newest") {
                // newest first: a new flow has the biggest id, so it goes on top (or in its place)
                var at = 0
                while (at < list.size && list[at].id > r.id) at++
                list.add(at, r)
                added++
            } else {
                added++ // other orders: counted, the user reloads to place them
            }
        }
        if (added > 0) _total.value = _total.value + added
        if (list.size > WINDOW) {
            while (list.size > WINDOW) list.removeAt(list.size - 1)
            next = "f" + list.last().id
        }
        _rows.value = ArrayList(list)
    }

    private fun indexOf(id: Long): Int {
        if (_sort.value != "newest") return list.indexOfFirst { it.id == id }
        // ids descending: binary search
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = list[mid].id
            when {
                v == id -> return mid
                v > id -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    suspend fun loadPaused() {
        runCatching {
            val r = NetcapApi.get("/v1/ui/paused")
            _paused.value = r.optJSONArray("paused").objects().map(NetPaused::parse)
        }
    }

    // ---- actions (the live stream brings their results back) ----

    /** Marks rows locally at once (pin, color), then on the engine. */
    suspend fun mark(ids: Collection<Long>, pinned: Boolean? = null, color: String? = null, note: String? = null) {
        mutex.withLock {
            ids.forEach { id ->
                val i = indexOf(id)
                if (i >= 0) list[i] = list[i].copy(
                    pinned = pinned ?: list[i].pinned, color = color ?: list[i].color, note = note ?: list[i].note
                )
            }
            _rows.value = ArrayList(list)
        }
        val body = JSONObject().put("ids", JSONArray(ids.toList()))
        pinned?.let { body.put("pinned", it) }
        color?.let { body.put("color", it) }
        note?.let { body.put("note", it) }
        NetcapApi.post("/v1/ui/mark", body)
    }

    suspend fun delete(ids: Collection<Long>) {
        mutex.withLock { applyLocked(emptyList(), ids.toList()) }
        NetcapApi.post("/v1/ui/delete", JSONObject().put("ids", JSONArray(ids.toList())))
    }

    suspend fun clearAll() {
        mutex.withLock {
            list.clear(); next = ""; _total.value = 0; _rows.value = emptyList()
        }
        NetcapApi.post("/v1/ui/delete", JSONObject().put("all", true))
    }

    suspend fun pauseCapture(on: Boolean) {
        NetcapApi.post("/v1/ui/capture/pause", JSONObject().put("on", on))
        _state.value = _state.value.copy(capture = _state.value.capture.copy(paused = on))
    }

    /** A row of the list by id (for the detail screen's next/previous). */
    fun neighbours(id: Long): Pair<Long?, Long?> {
        val l = _rows.value
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return null to null
        return l.getOrNull(i - 1)?.id to l.getOrNull(i + 1)?.id
    }

    fun rowById(id: Long): NetRow? = _rows.value.firstOrNull { it.id == id }
}
