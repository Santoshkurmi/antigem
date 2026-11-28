package ai.antigem.ui.netcap

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.netcap.NetApps
import ai.antigem.data.netcap.NetGroup
import ai.antigem.data.netcap.NetRow
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapApi
import ai.antigem.data.netcap.objects
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal enum class GroupMode { LIST, HOST, APP }

/** The traffic list's own state, kept while the page shows a request (and while the page is hidden). */
internal object TrafficUi {
    var search by mutableStateOf("")
    val status = mutableStateListOf<String>()
    val types = mutableStateListOf<String>()
    var pinnedOnly by mutableStateOf(false)
    var modifiedOnly by mutableStateOf(false)
    var errorsOnly by mutableStateOf(false)
    var hideNoise by mutableStateOf(false)
    var group by mutableStateOf(GroupMode.LIST)
    /** The host or app the user drilled into from a group (added to the filter). */
    var scope by mutableStateOf<Pair<GroupMode, String>?>(null)
    val selected = mutableStateListOf<Long>()
    val listState = LazyListState()
    var showFilterHelp by mutableStateOf(false)
    /** The list follows new requests while it is at the top (the user scrolling down stops that). */
    var follow by mutableStateOf(true)

    val selecting: Boolean get() = selected.isNotEmpty()

    /** The filter (the engine's filter language) built from the box and the chips. */
    fun filter(): String {
        val parts = ArrayList<String>()
        if (search.isNotBlank()) parts += search.trim()
        scope?.let { (m, key) -> parts += if (m == GroupMode.APP) "app:$key" else "host:$key" }
        if (status.isNotEmpty()) parts += status.joinToString(" OR ", "(", ")") { if (it == "err") "error:any" else "status:$it" }
        if (types.isNotEmpty()) parts += types.joinToString(" OR ", "(", ")") { if (it == "ws" || it == "grpc" || it == "tcp") "kind:$it" else "type:$it" }
        if (pinnedOnly) parts += "pinned:true"
        if (modifiedOnly) parts += "(flag:modified OR flag:mocked OR flag:mapped)"
        if (errorsOnly) parts += "(status:4xx OR status:5xx OR error:any)"
        return parts.joinToString(" ")
    }

    val chipsOn: Boolean get() = status.isNotEmpty() || types.isNotEmpty() || pinnedOnly || modifiedOnly || errorsOnly
}

@Composable
internal fun TrafficTab() {
    val ui = TrafficUi
    // the filter applies after a short pause in typing (a new change cancels the wait)
    val filterFlow = snapshotFlowOf { ui.filter() }
    LaunchedEffect(Unit) {
        filterFlow.collectLatest { q ->
            if (q != NetStore.filter.value) delay(300)
            NetStore.setFilter(q)
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (ui.selecting) SelectionBar() else FilterBar()
        ChipRow()
        ui.scope?.let { (m, key) -> ScopeLine(m, key) }
        Box(Modifier.weight(1f)) {
            if (ui.group == GroupMode.LIST || ui.scope != null) FlowList() else GroupList(ui.group)
        }
    }
    if (ui.showFilterHelp) FilterHelpSheet { ui.showFilterHelp = false }
}

@Composable
private fun <T> snapshotFlowOf(block: () -> T) = remember { androidx.compose.runtime.snapshotFlow(block) }

@Composable
private fun FilterBar() {
    val ui = TrafficUi
    val frozen by NetStore.frozen.collectAsState()
    val held by NetStore.held.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        NetSearchField(
            value = ui.search,
            onValueChange = { ui.search = it },
            placeholder = L.netcap.ui.filterHint,
            mono = ui.search.contains(':'),
            leading = { Icon(Icons.Outlined.FilterList, null, tint = AppColors.textSecondary, modifier = Modifier.size(18.dp)) },
            trailing = {
                Icon(
                    Icons.Outlined.HelpOutline, L.netcap.ui.filterHelp, tint = AppColors.textSecondary,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp).clip(CircleShape).clickable { ui.showFilterHelp = true }
                )
            },
            modifier = Modifier.weight(1f)
        )
        // live / frozen
        BadgedBox(badge = { if (frozen && held > 0) Badge { Text(if (held > 999) "999+" else "$held") } }) {
            IconButton(onClick = { NetStore.setFrozen(!frozen) }) {
                Icon(
                    if (frozen) Icons.Outlined.PlayCircle else Icons.Outlined.PauseCircle,
                    if (frozen) L.netcap.ui.liveResume else L.netcap.ui.liveFreeze,
                    tint = if (frozen) AppColors.warning else AppColors.onBackground
                )
            }
        }
        GroupMenu()
    }
}

@Composable
private fun GroupMenu() {
    val ui = TrafficUi
    val sort by NetStore.sort.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                when (ui.group) { GroupMode.LIST -> Icons.Outlined.ViewList; GroupMode.HOST -> Icons.Outlined.Dns; GroupMode.APP -> Icons.Outlined.Apps },
                L.netcap.ui.viewAs, tint = AppColors.onBackground
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(L.netcap.ui.viewAs, color = AppColors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            listOf(GroupMode.LIST to L.netcap.ui.viewList, GroupMode.HOST to L.netcap.ui.viewHosts, GroupMode.APP to L.netcap.ui.viewApps).forEach { (m, label) ->
                DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (ui.group == m) Icon(Icons.Outlined.Check, null) },
                    onClick = { ui.group = m; ui.scope = null; open = false })
            }
            HorizontalDivider()
            Text(L.netcap.ui.sortBy, color = AppColors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            listOf("newest" to L.netcap.ui.sortNewest, "oldest" to L.netcap.ui.sortOldest, "slowest" to L.netcap.ui.sortSlowest, "largest" to L.netcap.ui.sortLargest).forEach { (s, label) ->
                DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (sort == s) Icon(Icons.Outlined.Check, null) },
                    onClick = { NetStore.setSort(s); open = false })
            }
        }
    }
}

@Composable
private fun ChipRow() {
    val ui = TrafficUi
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        fun toggle(list: MutableList<String>, v: String) { if (v in list) list.remove(v) else list.add(v) }
        if (ui.chipsOn) {
            Icon(
                Icons.Filled.Close, L.netcap.ui.clearFilters, tint = AppColors.textSecondary,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable {
                    ui.status.clear(); ui.types.clear(); ui.pinnedOnly = false; ui.modifiedOnly = false; ui.errorsOnly = false
                }.padding(5.dp)
            )
        }
        ToggleChip(L.netcap.ui.chipPinned, ui.pinnedOnly, Icons.Outlined.PushPin) { ui.pinnedOnly = !ui.pinnedOnly }
        ToggleChip(L.netcap.ui.chipErrors, ui.errorsOnly, color = AppColors.danger) { ui.errorsOnly = !ui.errorsOnly }
        listOf("2xx" to AppColors.success, "3xx" to AppColors.info, "4xx" to AppColors.warning, "5xx" to AppColors.danger).forEach { (s, c) ->
            ToggleChip(s, s in ui.status, color = c) { toggle(ui.status, s) }
        }
        listOf("json" to "JSON", "html" to "HTML", "js" to "JS", "css" to "CSS", "image" to L.netcap.ui.chipImages,
            "media" to L.netcap.ui.chipMedia, "font" to L.netcap.ui.chipFonts, "ws" to "WebSocket", "grpc" to "gRPC", "tcp" to "TCP").forEach { (t, label) ->
            ToggleChip(label, t in ui.types) { toggle(ui.types, t) }
        }
        ToggleChip(L.netcap.ui.chipModified, ui.modifiedOnly, Icons.Outlined.EditNote, color = AppColors.accentIndigo) { ui.modifiedOnly = !ui.modifiedOnly }
    }
}

@Composable
private fun ScopeLine(mode: GroupMode, key: String) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(AppColors.fill).clickable { TrafficUi.scope = null }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, L.common.back, tint = AppColors.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        if (mode == GroupMode.APP) { AppIcon(key, 18.dp); Spacer(Modifier.width(6.dp)) }
        Text(
            if (mode == GroupMode.APP) NetApps.label(context, key) else key,
            color = AppColors.onBackground, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FlowList() {
    val host = LocalNetHost.current
    val ui = TrafficUi
    val rows by NetStore.rows.collectAsState()
    val total by NetStore.total.collectAsState()
    val loading by NetStore.loading.collectAsState()
    val connected by NetStore.connected.collectAsState()
    val state by NetStore.state.collectAsState()
    var menuFor by remember { mutableStateOf<NetRow?>(null) }
    val listState = ui.listState
    // more rows when the end comes near
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it > rows.size - 30 } == true } }
    LaunchedEffect(nearEnd, rows.size) { if (nearEnd && NetStore.hasMore) NetStore.loadMore() }
    // follow new rows while the list is at the top: new rows keep the shown ones in place, so scroll back up to them
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, index) -> if (scrolling) ui.follow = index == 0 }
    }
    val topId = rows.firstOrNull()?.id
    LaunchedEffect(topId) { if (ui.follow && rows.isNotEmpty() && listState.firstVisibleItemIndex != 0) listState.scrollToItem(0) }
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    if (rows.isEmpty()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            TrafficUi.filter().isNotEmpty() -> EmptyState(Icons.Outlined.SearchOff, L.netcap.ui.noMatch, L.netcap.ui.noMatchHint)
            !ai.antigem.data.netcap.NetcapManager.state.collectAsState().value.capturing -> EmptyState(Icons.Outlined.Wifi, L.netcap.ui.emptyTitle, L.netcap.ui.emptyHint)
            else -> EmptyState(Icons.Outlined.HourglassEmpty, L.netcap.ui.waiting, if (connected) null else L.netcap.ui.connecting)
        }
        return
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LIST_BOTTOM)) {
            items(rows, key = { it.id }, contentType = { "row" }) { r ->
                FlowRowItem(
                    r,
                    selected = r.id in ui.selected,
                    onClick = {
                        if (ui.selecting) { if (r.id in ui.selected) ui.selected.remove(r.id) else ui.selected.add(r.id) }
                        else host.navigate(NetDest.Detail(r.id))
                    },
                    onLongClick = { if (ui.selecting) ui.selected.add(r.id) else menuFor = r }
                )
            }
            item(key = "footer", contentType = "footer") {
                Text(
                    if (NetStore.hasMore) L.netcap.ui.loadingMore else L.netcap.ui.shownOf(rows.size, total),
                    color = AppColors.textSecondary, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        // new rows above while scrolled down
        val scope = rememberCoroutineScope()
        if (!atTop) {
            SmallFloatingActionButton(
                onClick = { ui.follow = true; scope.launch { listState.animateScrollToItem(0) } },
                containerColor = AppColors.card, contentColor = AppColors.onBackground,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
            ) { Icon(Icons.Outlined.ArrowUpward, L.netcap.ui.toTop, Modifier.size(18.dp)) }
        }
    }
    menuFor?.let { r -> RowActionsSheet(r) { menuFor = null } }
}

/** One request in the list: app, method, status, host, path, type, size, time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FlowRowItem(r: NetRow, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val mark = markColor(r.color)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) AppColors.primary.copy(alpha = 0.12f) else Color.Transparent)
            // the user's color: a stripe on the left (drawn, not measured: rows scroll by the thousand)
            .drawBehind { if (mark != null) drawRect(mark, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AppIcon(r.app, 30.dp)
                if (selected) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(AppColors.primary.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = when (r.kind) {
                        "ws" -> "WS"; "grpc" -> "gRPC"; "tcp" -> "TCP"; "udp" -> "UDP"; "tls" -> "TLS"
                        else -> r.method.ifEmpty { "?" }
                    }
                    Tag(label, methodColor(r.method, r.kind))
                    Spacer(Modifier.width(5.dp))
                    StatusBadge(r)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        r.host, color = AppColors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (r.pinned) Icon(Icons.Filled.PushPin, null, tint = AppColors.primary, modifier = Modifier.padding(start = 4.dp).size(13.dp))
                    Text(fmtTime(r.start), color = AppColors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (r.isHttp) r.pathAndQuery.ifEmpty { "/" } else "${r.host}:${r.port}",
                        color = AppColors.onBackground, fontSize = 13.sp, fontFamily = Mono, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    RowFlags(r)
                    // the answer's type as an icon ({} for JSON…), then size and time
                    typeIcon(r.resType.ifEmpty { r.reqType })?.let { ic ->
                        Icon(ic, null, tint = AppColors.textSecondary, modifier = Modifier.padding(start = 6.dp).size(14.dp))
                    }
                    val meta = buildString {
                        if (r.resSize > 0 || r.done) append(fmtSize(r.resSize))
                        val ms = fmtMs(r.ms)
                        if (ms.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(ms) }
                    }
                    Text(meta, color = AppColors.textSecondary, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
                if (r.note.isNotEmpty()) {
                    Text(r.note, color = AppColors.accentIndigo, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(r: NetRow) {
    when {
        r.paused.isNotEmpty() -> Tag("⏸", AppColors.warning, filled = true)
        r.error.isNotEmpty() -> Tag(if (r.status > 0) "${r.status}" else "ERR", AppColors.danger, filled = true)
        !r.done && r.status == 0 -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AppColors.textSecondary)
        r.status > 0 -> Tag("${r.status}", statusColor(r.status))
        r.tls == "passthrough" || r.tls == "untrusted" -> Tag("🔒", AppColors.textSecondary)
        else -> Unit
    }
}

@Composable
private fun RowFlags(r: NetRow) {
    val icons = ArrayList<Pair<ImageVector, Color>>(3)
    if ("mocked" in r.flags) icons += Icons.Outlined.TheaterComedy to AppColors.accentPink
    else if ("modified" in r.flags || "mapped" in r.flags || r.flags.any { it.startsWith("script:") }) icons += Icons.Outlined.EditNote to AppColors.accentIndigo
    if (r.resentFrom > 0 || "sent" in r.flags) icons += Icons.Outlined.Replay to AppColors.info
    if (r.tls == "passthrough" || r.tls == "untrusted") icons += Icons.Outlined.LockOpen to AppColors.textSecondary
    if (r.messages > 0) icons += Icons.Outlined.Forum to AppColors.accentIndigo
    icons.forEach { (ic, c) -> Icon(ic, null, tint = c, modifier = Modifier.padding(start = 3.dp).size(13.dp)) }
}

/** Hosts or apps with their counts; tapping one shows its requests. */
@Composable
private fun GroupList(mode: GroupMode) {
    val context = LocalContext.current
    val tick by NetStore.tick.collectAsState()
    val q by NetStore.filter.collectAsState()
    var groups by remember(mode) { mutableStateOf<List<NetGroup>?>(null) }
    LaunchedEffect(mode, q, tick / 8) { // about once a second while traffic comes in
        groups = runCatching {
            NetcapApi.get("/v1/ui/groups", mapOf("by" to if (mode == GroupMode.APP) "app" else "host", "q" to q, "limit" to 500))
                .optJSONArray("groups").objects().map(NetGroup::parse)
        }.getOrElse { groups ?: emptyList() }
    }
    val g = groups
    if (g == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (g.isEmpty()) { EmptyState(Icons.Outlined.Dns, L.netcap.ui.emptyTitle); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LIST_BOTTOM)) {
        items(g, key = { it.key }) { gr ->
            Row(
                Modifier.fillMaxWidth().clickable { TrafficUi.scope = mode to gr.key }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (mode == GroupMode.APP) AppIcon(gr.key, 30.dp) else
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(AppColors.fill), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Language, null, tint = AppColors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (mode == GroupMode.APP) NetApps.label(context, gr.key).ifEmpty { L.netcap.ui.unknownApp } else gr.key,
                        color = AppColors.onBackground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        L.netcap.ui.groupLine(gr.count, fmtMs(gr.avgMs).ifEmpty { "–" }, fmtTime(gr.last)),
                        color = AppColors.textSecondary, fontSize = 12.sp
                    )
                }
                if (gr.errors > 0) Tag("${gr.errors}", AppColors.danger)
            }
        }
    }
}

@Composable
private fun SelectionBar() {
    val ui = TrafficUi
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val rows by NetStore.rows.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(AppColors.primary.copy(alpha = 0.10f)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { ui.selected.clear() }) { Icon(Icons.Filled.Close, L.common.cancel, tint = AppColors.onBackground) }
        Text(L.netcap.ui.selected(ui.selected.size), color = AppColors.onBackground, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        IconButton(onClick = { ui.selected.clear(); ui.selected.addAll(rows.map { it.id }) }) { Icon(Icons.Outlined.SelectAll, L.common.selectAll, tint = AppColors.onBackground) }
        IconButton(onClick = {
            val ids = ui.selected.toList()
            scope.launch { netTry { NetStore.mark(ids, pinned = !rows.filter { it.id in ids }.all { it.pinned }) } }
        }) { Icon(Icons.Outlined.PushPin, L.netcap.ui.pin, tint = AppColors.onBackground) }
        if (ui.selected.size == 2) {
            IconButton(onClick = { val (a, b) = ui.selected.sorted(); ui.selected.clear(); host.navigate(NetDest.Compare(a, b)) }) {
                Icon(Icons.Outlined.Compare, L.netcap.ui.compare, tint = AppColors.onBackground)
            }
        }
        IconButton(onClick = {
            val ids = ui.selected.sorted()
            scope.launch {
                netTry {
                    val text = NetcapApi.tool("net_export", JSONObject().put("format", "har").put("filter", ids.joinToString(" OR ") { "id:$it" }))
                    host.navigate(NetDest.Text(L.netcap.ui.export, text))
                }
            }
        }) { Icon(Icons.Outlined.IosShare, L.netcap.ui.exportHar, tint = AppColors.onBackground) }
        IconButton(onClick = {
            val ids = ui.selected.sorted()
            host.askAi(L.netcap.ui.askAiMany(ids.joinToString(", ") { "f$it" }))
            ui.selected.clear()
        }) { Icon(Icons.Outlined.AutoAwesome, L.netcap.ui.askAi, tint = AppColors.onBackground) }
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, L.common.delete, tint = AppColors.danger) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(L.netcap.ui.deleteSelected(ui.selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val ids = ui.selected.toList(); ui.selected.clear()
                    scope.launch { netTry { NetStore.delete(ids) } }
                }) { Text(L.common.delete, color = AppColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L.common.cancel) } }
        )
    }
}

/** Long press on a request: everything you can do with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RowActionsSheet(r: NetRow, onDismiss: () -> Unit) {
    val host = LocalNetHost.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var repeat by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(r.title(), color = AppColors.onBackground, fontFamily = Mono, fontSize = 12.5.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            // colors
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                markColors.forEach { c ->
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(markColor(c)!!)
                            .clickable { scope.launch { netTry { NetStore.mark(listOf(r.id), color = if (r.color == c) "" else c) } }; onDismiss() },
                        contentAlignment = Alignment.Center
                    ) { if (r.color == c) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
                if (r.color.isNotEmpty()) {
                    TextButton(onClick = { scope.launch { netTry { NetStore.mark(listOf(r.id), color = "") } }; onDismiss() }) { Text(L.netcap.ui.noColor) }
                }
            }
            HorizontalDivider(color = AppColors.border)
            val http = r.isHttp && r.kind != "ws"
            @Composable
            fun item(icon: ImageVector, text: String, color: Color = AppColors.onBackground, enabled: Boolean = true, close: Boolean = true, action: suspend () -> Unit) {
                if (!enabled) return
                Row(
                    Modifier.fillMaxWidth().clickable { scope.launch { netTry { action() } }; if (close) onDismiss() }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(text, color = color, fontSize = 14.5.sp)
                }
            }
            item(Icons.Outlined.OpenInNew, L.common.open) { host.navigate(NetDest.Detail(r.id)) }
            item(Icons.Outlined.Replay, L.netcap.ui.resend, enabled = http) {
                val res = NetcapApi.post("/v1/ui/send", JSONObject().put("from", "f${r.id}"))
                sentToast(res)
            }
            item(Icons.Outlined.EditNote, L.netcap.ui.editAndSend, enabled = http) { host.navigate(NetDest.Composer(r.id)) }
            item(Icons.Outlined.Repeat, L.netcap.ui.repeat, enabled = http, close = false) { repeat = true }
            item(if (r.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (r.pinned) L.netcap.ui.unpin else L.netcap.ui.pin) {
                NetStore.mark(listOf(r.id), pinned = !r.pinned)
            }
            item(Icons.Outlined.StickyNote2, L.netcap.ui.note, close = false) { note = true }
            HorizontalDivider(color = AppColors.border)
            item(Icons.Outlined.Link, L.netcap.ui.copyUrl) { copyText(context, "URL", r.url) }
            item(Icons.Outlined.Terminal, L.netcap.ui.copyCurl, enabled = http) {
                copyText(context, "cURL", NetcapApi.get("/v1/ui/curl", mapOf("id" to r.id)).optString("curl"))
            }
            item(Icons.Outlined.Description, L.netcap.ui.copyRaw, enabled = http) {
                copyText(context, "HTTP", NetcapApi.get("/v1/ui/raw", mapOf("id" to r.id, "side" to "req")).optString("text"))
            }
            HorizontalDivider(color = AppColors.border)
            item(Icons.Outlined.PauseCircle, L.netcap.ui.breakHere, enabled = http) { host.navigate(NetDest.RuleEdit(ruleFrom(r, "breakpoint"))) }
            item(Icons.Outlined.TheaterComedy, L.netcap.ui.mockThis, enabled = http) { host.navigate(NetDest.RuleEdit(ruleFrom(r, "mock"))) }
            item(Icons.Outlined.DriveFileMove, L.netcap.ui.mapLocal, enabled = http) { host.navigate(NetDest.RuleEdit(ruleFrom(r, "map_local"))) }
            item(Icons.Outlined.AltRoute, L.netcap.ui.mapRemote, enabled = http) { host.navigate(NetDest.RuleEdit(ruleFrom(r, "map_remote"))) }
            item(Icons.Outlined.AutoFixHigh, L.netcap.ui.rewriteThis, enabled = http) { host.navigate(NetDest.RuleEdit(ruleFrom(r, "rewrite"))) }
            item(Icons.Outlined.Block, L.netcap.ui.blockHost(r.host), color = AppColors.danger) {
                NetcapApi.post("/v1/ui/rules", JSONObject().put("rule", JSONObject().put("name", L.netcap.ui.blockHost(r.host))
                    .put("match", "host:${r.host}").put("action", JSONObject().put("type", "block"))))
                AppToastHelper.showToast(L.netcap.ui.ruleAdded)
            }
            item(Icons.Outlined.LockOpen, L.netcap.ui.dontDecrypt(r.host)) {
                NetcapApi.post("/v1/ui/rules", JSONObject().put("rule", JSONObject().put("name", L.netcap.ui.dontDecrypt(r.host))
                    .put("match", "host:${r.host}").put("action", JSONObject().put("type", "passthrough"))))
                AppToastHelper.showToast(L.netcap.ui.ruleAdded)
            }
            item(Icons.Outlined.VisibilityOff, L.netcap.ui.hideHost(r.host)) {
                NetcapApi.post("/v1/ui/rules", JSONObject().put("rule", JSONObject().put("name", L.netcap.ui.hideHost(r.host))
                    .put("match", "host:${r.host}").put("action", JSONObject().put("type", "ignore"))))
                AppToastHelper.showToast(L.netcap.ui.ruleAdded)
            }
            HorizontalDivider(color = AppColors.border)
            item(Icons.Outlined.Compare, L.netcap.ui.compareWith) { host.navigate(NetDest.Compare(r.id, null)) }
            item(Icons.Outlined.AutoAwesome, L.netcap.ui.askAi) { host.askAi(L.netcap.ui.askAiOne("f${r.id}", r.title())) }
            item(Icons.Outlined.Delete, L.common.delete, color = AppColors.danger) { NetStore.delete(listOf(r.id)) }
        }
    }
    if (repeat) RepeatDialog(r) { repeat = false; onDismiss() }
    if (note) NoteDialog(r) { note = false; onDismiss() }
}

internal fun sentToast(res: JSONObject) {
    val ids = res.optJSONArray("ids")
    val errors = res.optJSONArray("errors")
    val err = (0 until (errors?.length() ?: 0)).map { errors!!.optString(it) }.firstOrNull { it.isNotBlank() }
    AppToastHelper.showToast(
        when {
            err != null -> err
            (ids?.length() ?: 0) == 1 -> L.netcap.ui.sentAs("f${ids!!.optLong(0)}")
            else -> L.netcap.ui.sentMany(ids?.length() ?: 0)
        }
    )
}

/** A new rule prefilled from a request (the editor opens with it). */
internal fun ruleFrom(r: NetRow, type: String): JSONObject {
    val match = buildString {
        append("host:").append(r.host)
        if (r.path.isNotEmpty()) append(" path:").append(r.path)
        if (r.method.isNotEmpty() && type != "map_remote") append(" method:").append(r.method)
    }
    val action = JSONObject().put("type", type)
    val phase = when (type) {
        "breakpoint" -> "request"
        "mock", "map_local", "map_remote" -> "request"
        else -> "response"
    }
    when (type) {
        "mock" -> action.put("status", if (r.status > 0) r.status else 200)
        "map_remote" -> action.put("url", "${r.scheme.ifEmpty { "https" }}://${r.host}")
    }
    return JSONObject()
        .put("name", "${type.replace('_', ' ')} ${r.host}${r.path}")
        .put("match", match)
        .put("phase", phase)
        .put("enabled", true)
        .put("action", action)
        .put("_from", r.id)
}

@Composable
private fun RepeatDialog(r: NetRow, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf("10") }
    var interval by remember { mutableStateOf("0") }
    var parallel by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.netcap.ui.repeat) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(count, { count = it.filter(Char::isDigit) }, label = { Text(L.netcap.ui.repeatCount) }, singleLine = true)
                OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text(L.netcap.ui.repeatInterval) }, singleLine = true)
                OutlinedTextField(parallel, { parallel = it.filter(Char::isDigit) }, label = { Text(L.netcap.ui.repeatParallel) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = count.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                val body = JSONObject().put("from", "f${r.id}").put("repeat", JSONObject().put("count", c)
                    .put("interval_ms", interval.toIntOrNull() ?: 0).put("concurrency", (parallel.toIntOrNull() ?: 1).coerceIn(1, 16)))
                scope.launch { netTry { sentToast(NetcapApi.post("/v1/ui/send", body)) } }
                onDismiss()
            }) { Text(L.common.send) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

@Composable
internal fun NoteDialog(r: NetRow, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(r.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.netcap.ui.note) },
        text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2) },
        confirmButton = {
            TextButton(onClick = { scope.launch { netTry { NetStore.mark(listOf(r.id), note = text.trim()) } }; onDismiss() }) { Text(L.common.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/** The filter language in short, with examples the user can tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            Text(L.netcap.ui.filterHelp, color = AppColors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(L.netcap.ui.filterHelpIntro, color = AppColors.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            listOf(
                "host:api.example.com" to L.netcap.ui.fxHost,
                "host:*.example.com path:/v1/*" to L.netcap.ui.fxHostPath,
                "method:POST status:4xx" to L.netcap.ui.fxMethodStatus,
                "type:json -host:*.googleapis.com" to L.netcap.ui.fxNot,
                "res.body:\"email\"" to L.netcap.ui.fxBody,
                "req.header.authorization:exists" to L.netcap.ui.fxHeader,
                "res.json:.user.role==\"admin\"" to L.netcap.ui.fxJson,
                "time>500ms size>100k" to L.netcap.ui.fxSlow,
                "(status:5xx OR error:any)" to L.netcap.ui.fxOr,
                "app:com.example.app since:10m" to L.netcap.ui.fxApp,
                "tls:passthrough" to L.netcap.ui.fxTls,
            ).forEach { (ex, what) ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { TrafficUi.search = ex; onDismiss() }.padding(vertical = 7.dp, horizontal = 4.dp)
                ) {
                    Text(ex, color = AppColors.primary, fontFamily = Mono, fontSize = 13.sp)
                    Text(what, color = AppColors.textSecondary, fontSize = 12.5.sp)
                }
            }
        }
    }
}
