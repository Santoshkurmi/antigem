package ai.antigem.ui.netcap

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.netcap.NetApps
import ai.antigem.data.netcap.NetFlow
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapApi
import ai.antigem.data.netcap.objects
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import kotlinx.coroutines.launch

internal enum class DetailTab { OVERVIEW, REQUEST, RESPONSE, MESSAGES }
internal enum class PartTab { HEADERS, BODY, QUERY, COOKIES, TRAILERS }

/**
 * What the detail screen shows, kept for every request opened after: the tab, and in Request and Response the chosen
 * part (Body, Headers…) and body view (Pretty, Text…). A choice a request can't show (Tree for an image) is skipped for
 * it, and stays for the next one.
 */
internal object DetailUi {
    var tab by mutableStateOf(DetailTab.RESPONSE)
    var reqPart by mutableStateOf(PartTab.BODY)
    var resPart by mutableStateOf(PartTab.BODY)
    var reqMode by mutableStateOf<BodyMode?>(null)
    var resMode by mutableStateOf<BodyMode?>(null)
    var wrap by mutableStateOf(true)
}

/** One request: overview, request, response, messages, as swipeable tabs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FlowDetailScreen(id: Long) {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    var flow by remember { mutableStateOf<NetFlow?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    // the row changes while the flow runs: reload when it finishes or gets its answer
    val rows by NetStore.rows.collectAsState()
    val live = rows.firstOrNull { it.id == id }
    val liveKey = live?.let { "${it.done}/${it.status}/${it.messages}/${it.pinned}/${it.color}/${it.note}/${it.paused}" }
    LaunchedEffect(id, liveKey) {
        runCatching { NetFlow.parse(NetcapApi.get("/v1/ui/flow", mapOf("id" to id))) }
            .onSuccess { flow = it; error = null }
            .onFailure { error = it.message }
    }
    val f = flow
    Column(Modifier.fillMaxSize()) {
        NetTopBar(
            title = f?.row?.let { r -> if (r.isHttp) "${r.method} ${if (r.status > 0) r.status else ""}".trim() else r.kind.uppercase() } ?: "f$id",
            subtitle = f?.row?.host,
            onBack = host.back
        ) {
            if (f != null && f.row.isHttp && f.row.kind != "ws") {
                TextButton(onClick = { host.navigate(NetDest.Composer(id)) }) {
                    Icon(Icons.Outlined.EditNote, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(L.netcap.ui.editAndSendShort)
                }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, L.common.more, tint = AppColors.onBackground) }
        }
        if (f != null && menu) RowActionsSheet(f.row) { menu = false }
        if (f == null) {
            if (error != null) Text(error!!, color = AppColors.danger, modifier = Modifier.padding(16.dp))
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        // the URL, copyable
        SelectionContainer {
            Text(
                f.row.url, color = AppColors.onBackground, fontFamily = Mono, fontSize = 12.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)
            )
        }
        val tabs = remember(f.row.kind, f.row.messages > 0) {
            buildList {
                add(DetailTab.OVERVIEW)
                if (f.row.isHttp) { add(DetailTab.REQUEST); add(DetailTab.RESPONSE) }
                if (f.row.messages > 0 || f.row.kind == "ws" || f.row.kind == "tcp" || f.row.kind == "udp") add(DetailTab.MESSAGES)
            }
        }
        // the remembered tab, or the nearest this request has
        val start = tabs.indexOf(DetailUi.tab).takeIf { it >= 0 } ?: tabs.indexOf(DetailTab.MESSAGES).takeIf { it >= 0 } ?: 0
        val pager = rememberPagerState(initialPage = start) { tabs.size }
        // a swipe or a tab tap is the new remembered tab
        LaunchedEffect(pager, tabs) {
            snapshotFlow { pager.settledPage }.collect { p -> tabs.getOrNull(p)?.let { DetailUi.tab = it } }
        }
        TabRow(
            selectedTabIndex = pager.currentPage, containerColor = Color.Transparent, contentColor = AppColors.primary,
            divider = { HorizontalDivider(color = AppColors.border) }
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = {
                    Text(
                        when (t) {
                            DetailTab.OVERVIEW -> L.netcap.ui.overview
                            DetailTab.REQUEST -> L.netcap.ui.request
                            DetailTab.RESPONSE -> L.netcap.ui.response
                            DetailTab.MESSAGES -> L.netcap.ui.messagesCount(f.row.messages)
                        }, fontSize = 13.sp, maxLines = 1
                    )
                })
            }
        }
        // while the screen slides in only its frame shows; the tab's content comes right after, and the tabs next to
        // it a moment later (so a swipe finds them ready)
        val settled = LocalNetSettled.current
        if (!settled) {
            Box(Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        var neighbours by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); neighbours = 1 }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = neighbours, key = { tabs[it] }) { page ->
            when (tabs[page]) {
                DetailTab.OVERVIEW -> Overview(f)
                DetailTab.REQUEST -> Part(f, response = false)
                DetailTab.RESPONSE -> Part(f, response = true)
                DetailTab.MESSAGES -> MessagesView(id)
            }
        }
    }
}

@Composable
private fun Part(f: NetFlow, response: Boolean) {
    val headers = if (response) f.resHeaders else f.reqHeaders
    val trailers = if (response) f.resTrailers else f.reqTrailers
    val info = if (response) f.res else f.req
    val cookies = remember(headers) { parseCookies(headers, response) }
    val query = remember(f.row.query) { if (response) emptyList() else parseQuery(f.row.query) }
    val parts = buildList {
        add(PartTab.BODY); add(PartTab.HEADERS)
        if (query.isNotEmpty()) add(PartTab.QUERY)
        if (cookies.isNotEmpty()) add(PartTab.COOKIES)
        if (trailers.isNotEmpty()) add(PartTab.TRAILERS)
    }
    // the remembered part for this side; one this request lacks falls back to Body (or Headers when there's no body)
    val wanted = if (response) DetailUi.resPart else DetailUi.reqPart
    val part = when {
        wanted in parts && !(wanted == PartTab.BODY && !info.present && headers.isNotEmpty()) -> wanted
        info.present -> PartTab.BODY
        else -> PartTab.HEADERS
    }
    fun choose(p: PartTab) { if (response) DetailUi.resPart = p else DetailUi.reqPart = p }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (response && f.row.status > 0) Tag("${f.row.status}", statusColor(f.row.status, f.row.error), modifier = Modifier.align(Alignment.CenterVertically))
            parts.forEach { p ->
                ToggleChip(
                    when (p) {
                        PartTab.HEADERS -> L.netcap.ui.headersCount(headers.size)
                        PartTab.BODY -> if (info.present) L.netcap.ui.bodySize(fmtSize(info.decodedSize)) else L.netcap.ui.body
                        PartTab.QUERY -> L.netcap.ui.queryCount(query.size)
                        PartTab.COOKIES -> L.netcap.ui.cookiesCount(cookies.size)
                        PartTab.TRAILERS -> L.netcap.ui.trailers
                    }, part == p
                ) { choose(p) }
            }
        }
        when (part) {
            PartTab.HEADERS -> HeadersView(headers, L.netcap.ui.noHeaders)
            PartTab.BODY -> BodyView(f.row.id, if (response) "res" else "req", info)
            PartTab.QUERY -> PairsView(query, L.netcap.ui.noQuery)
            PartTab.COOKIES -> PairsView(cookies, L.netcap.ui.noCookies)
            PartTab.TRAILERS -> HeadersView(trailers, L.netcap.ui.noHeaders)
        }
    }
}

@Composable
private fun Overview(f: NetFlow) {
    val host = LocalNetHost.current
    val context = LocalContext.current
    val r = f.row
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.error.isNotEmpty()) {
            NetCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = AppColors.danger)
                    Spacer(Modifier.width(8.dp))
                    Text(r.error, color = AppColors.danger, fontSize = 13.5.sp)
                }
            }
        }
        if (r.tls == "passthrough" || r.tls == "untrusted") {
            NetCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LockOpen, null, tint = AppColors.warning)
                    Spacer(Modifier.width(8.dp))
                    Text(if (r.tls == "untrusted") L.netcap.ui.tlsUntrusted else L.netcap.ui.tlsPassthrough, color = AppColors.onBackground, fontSize = 13.sp)
                }
            }
        }
        NetCard(L.netcap.ui.general) {
            if (r.app.isNotEmpty()) {
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L.netcap.ui.app, color = AppColors.textSecondary, fontSize = 13.sp, modifier = Modifier.width(112.dp))
                    AppIcon(r.app, 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(NetApps.label(context, r.app), color = AppColors.onBackground, fontSize = 13.sp)
                }
            }
            InfoLine(L.netcap.ui.status, if (r.status > 0) "${r.status}" else if (r.done) "–" else L.netcap.ui.pending, valueColor = statusColor(r.status, r.error))
            InfoLine(L.netcap.ui.method, r.method)
            InfoLine(L.netcap.ui.protocol, listOf(r.kind.uppercase(), if (r.http.isNotEmpty()) "HTTP/${r.http}" else "", r.scheme).filter { it.isNotEmpty() }.joinToString(" · "))
            InfoLine(L.netcap.ui.started, fmtDateTime(r.start))
            InfoLine(L.netcap.ui.duration, fmtMs(r.ms))
            InfoLine(L.netcap.ui.endpoint, f.endpoint, mono = true)
            InfoLine(L.netcap.ui.clientAddr, f.client, mono = true)
            InfoLine(L.netcap.ui.serverAddr, f.server, mono = true)
            InfoLine("ID", "f${r.id}", mono = true)
            if (r.resentFrom > 0) {
                Row(Modifier.fillMaxWidth().clickable { host.navigate(NetDest.Detail(r.resentFrom)) }.padding(vertical = 3.dp)) {
                    Text(L.netcap.ui.resentFrom, color = AppColors.textSecondary, fontSize = 13.sp, modifier = Modifier.width(112.dp))
                    Text("f${r.resentFrom}", color = AppColors.primary, fontSize = 13.sp, fontFamily = Mono)
                }
            }
            if (r.note.isNotEmpty()) InfoLine(L.netcap.ui.note, r.note)
        }
        if (f.timings.isNotEmpty()) NetCard(L.netcap.ui.timing) { Timings(f.timings) }
        NetCard(L.netcap.ui.sizes) {
            InfoLine(L.netcap.ui.request, sizeLine(f.req.size, f.req.decodedSize, f.req.encoding))
            InfoLine(L.netcap.ui.response, sizeLine(f.res.size, f.res.decodedSize, f.res.encoding))
            if (f.bytesOut > 0 || f.bytesIn > 0) InfoLine(L.netcap.ui.onWire, "↑ ${fmtSize(f.bytesOut)}  ↓ ${fmtSize(f.bytesIn)}")
        }
        if (r.tls.isNotEmpty()) NetCard("TLS") {
            InfoLine(L.netcap.ui.tlsState, r.tls)
            f.tlsInfo.forEach { (k, v) -> InfoLine(k.replace('_', ' '), v, mono = true) }
        }
        if (r.flags.isNotEmpty() || r.tags.isNotEmpty()) NetCard(L.netcap.ui.changes) {
            if (r.flags.isNotEmpty()) InfoLine(L.netcap.ui.flags, r.flags.joinToString(", "))
            if (r.tags.isNotEmpty()) InfoLine(L.netcap.ui.tags, r.tags.joinToString(", "))
        }
    }
}

private fun sizeLine(wire: Long, decoded: Long, enc: String): String =
    if (enc.isNotEmpty() && decoded != wire) "${fmtSize(wire)} $enc → ${fmtSize(decoded)}" else fmtSize(maxOf(wire, decoded))

/** Connect, TLS, waiting for the answer, the download: bars on one time line; every time in one right-hand column. */
@Composable
private fun Timings(t: Map<String, Double>) {
    // the engine measures connect and TLS before the request (once per connection, so 0 on a reused one), and
    // total from the request's start: waiting (ttfb) + download; the whole time is all of them
    val connect = t["connect"] ?: 0.0
    val tls = t["tls"] ?: 0.0
    val request = t["total"] ?: 0.0
    val wait = (t["ttfb"] ?: 0.0).coerceAtMost(request)
    val all = listOf(
        Triple(L.netcap.ui.tConnect, connect, AppColors.accentTeal),
        Triple(L.netcap.ui.tTls, tls, AppColors.accentIndigo),
        Triple(L.netcap.ui.tWait, wait, AppColors.warning),
        Triple(L.netcap.ui.tDownload, (request - wait).coerceAtLeast(0.0), AppColors.success),
    )
    val total = connect + tls + request
    val scale = maxOf(total, 1.0)
    @Composable
    fun line(label: String, bold: Boolean, value: Double, bar: @Composable BoxWithConstraintsScope.() -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = if (bold) AppColors.onBackground else AppColors.textSecondary, fontSize = 12.5.sp,
                fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.width(88.dp))
            BoxWithConstraints(Modifier.weight(1f).height(10.dp), content = bar)
            Text(fmtMs(value).ifEmpty { "–" }, color = AppColors.onBackground, fontSize = 12.5.sp,
                fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(72.dp))
        }
    }
    var offset = 0.0
    all.forEach { (label, ms, color) ->
        if (ms <= 0) return@forEach
        val from = offset
        line(label, false, ms) {
            Box(
                Modifier.padding(start = maxWidth * (from / scale).toFloat()).width((maxWidth * (ms / scale).toFloat()).coerceAtLeast(2.dp))
                    .fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color)
            )
        }
        offset += ms
    }
    line(L.netcap.ui.tTotal, true, total) {
        Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(AppColors.border))
    }
}

private data class Msg(val index: Int, val dir: String, val time: Long, val type: String, val size: Long, val text: String, val binary: Boolean)

/** WebSocket messages (and raw TCP/UDP chunks) as a conversation: out on the right, in on the left. */
@Composable
private fun MessagesView(flowId: Long) {
    val context = LocalContext.current
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    var msgs by remember(flowId) { mutableStateOf<List<Msg>?>(null) }
    var dirFilter by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val rows by NetStore.rows.collectAsState()
    val count = rows.firstOrNull { it.id == flowId }?.messages
    LaunchedEffect(flowId, count) {
        runCatching {
            NetcapApi.get("/v1/ui/messages", mapOf("id" to flowId, "from" to 0, "limit" to 5000)).optJSONArray("messages").objects().map {
                Msg(it.optInt("index"), it.optString("dir"), it.optLong("time"), it.optString("type"), it.optLong("size"), it.optString("text"), it.optBoolean("binary"))
            }
        }.onSuccess { msgs = it }
    }
    val list = msgs
    if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val shown = remember(list, dirFilter, q) { list.filter { (dirFilter.isEmpty() || it.dir == dirFilter) && (q.isBlank() || it.text.contains(q, true)) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NetSearchField(q, { q = it }, L.common.search, Modifier.weight(1f))
            ToggleChip("↑", dirFilter == "out") { dirFilter = if (dirFilter == "out") "" else "out" }
            ToggleChip("↓", dirFilter == "in") { dirFilter = if (dirFilter == "in") "" else "in" }
        }
        if (shown.isEmpty()) { EmptyState(Icons.Outlined.Forum, L.netcap.ui.noMessages); return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.index }) { m ->
                val out = m.dir == "out"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.fillMaxWidth(0.86f).clip(RoundedCornerShape(12.dp))
                            .background(if (out) AppColors.primary.copy(alpha = 0.12f) else AppColors.card)
                            .clickable {
                                scope.launch {
                                    netTry {
                                        val r = NetcapApi.get("/v1/ui/message", mapOf("id" to flowId, "index" to m.index))
                                        val text = r.optString("text").ifEmpty {
                                            val b = android.util.Base64.decode(r.optString("data_base64"), android.util.Base64.DEFAULT)
                                            b.joinToString(" ") { "%02x".format(it) }
                                        }
                                        host.navigate(NetDest.Text(L.netcap.ui.messageTitle(m.index, if (out) "↑" else "↓"), prettyMaybe(text)))
                                    }
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (out) "↑" else "↓", color = if (out) AppColors.primary else AppColors.success, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Text("${m.type} · ${fmtSize(m.size)} · ${fmtTimeMs(m.time)}", color = AppColors.textSecondary, fontSize = 11.sp)
                        }
                        Text(
                            if (m.binary) L.netcap.ui.binaryMessage(fmtSize(m.size)) else m.text,
                            color = AppColors.onBackground, fontFamily = Mono, fontSize = 12.5.sp, maxLines = 6, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Pretty JSON when the text is JSON, else the text. */
internal fun prettyMaybe(text: String): String {
    val t = text.trim()
    if (t.length > 2_000_000) return text
    return runCatching {
        when {
            t.startsWith("{") -> org.json.JSONObject(t).toString(2)
            t.startsWith("[") -> org.json.JSONArray(t).toString(2)
            else -> text
        }
    }.getOrDefault(text)
}
