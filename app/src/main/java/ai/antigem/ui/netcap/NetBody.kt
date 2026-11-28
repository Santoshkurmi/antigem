package ai.antigem.ui.netcap

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.netcap.NetBodyInfo
import ai.antigem.data.netcap.NetcapApi
import ai.antigem.data.netcap.objects
import ai.antigem.data.netcap.strings
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class BodyMode { PRETTY, TEXT, TREE, HEX, IMAGE }

private const val LINE_PAGE = 200
private const val HEX_PAGE = 4096

/**
 * A request or response body, read from the engine in pages: text and pretty lines, a lazy JSON tree, hex, or the
 * image. Nothing holds the whole body, so a 50 MB answer scrolls like a small one.
 */
@Composable
internal fun BodyView(flowId: Long, side: String, info: NetBodyInfo, modifier: Modifier = Modifier) {
    val host = LocalNetHost.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (!info.present) {
        Box(modifier.fillMaxSize()) { EmptyState(Icons.Outlined.Inbox, L.netcap.ui.noBody) }
        return
    }
    val modes = buildList {
        if (info.image) add(BodyMode.IMAGE)
        if (info.text && info.canPretty) add(BodyMode.PRETTY)
        if (info.text) add(BodyMode.TEXT)
        if (info.isJson && info.text) add(BodyMode.TREE)
        add(BodyMode.HEX)
    }
    // the body view chosen last on this side (request / response) stays for the next request; one this body can't show
    // (Tree for an image) falls back to its first view without forgetting the choice
    val preferred = if (side == "res") DetailUi.resMode else DetailUi.reqMode
    val mode = preferred?.takeIf { it in modes } ?: modes.first()
    fun setMode(m: BodyMode) { if (side == "res") DetailUi.resMode = m else DetailUi.reqMode = m }
    val wrap = DetailUi.wrap
    var findOpen by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Triple<Int, Int, Int>>>(emptyList()) }
    var matchIndex by remember { mutableIntStateOf(0) }
    val lineList = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        // toolbar: modes, size, find, wrap, open in the IDE
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                modes.forEach { m ->
                    ToggleChip(
                        when (m) {
                            BodyMode.PRETTY -> L.netcap.ui.bodyPretty; BodyMode.TEXT -> L.netcap.ui.bodyText; BodyMode.TREE -> L.netcap.ui.bodyTree
                            BodyMode.HEX -> L.netcap.ui.bodyHex; BodyMode.IMAGE -> L.netcap.ui.bodyImage
                        }, mode == m
                    ) { setMode(m); matches = emptyList() }
                }
            }
            if (mode == BodyMode.PRETTY || mode == BodyMode.TEXT) {
                IconButton(onClick = { findOpen = !findOpen; if (!findOpen) { find = ""; matches = emptyList() } }) {
                    Icon(Icons.Outlined.Search, L.common.search, tint = if (findOpen) AppColors.primary else AppColors.onBackground)
                }
                IconButton(onClick = { DetailUi.wrap = !DetailUi.wrap }) {
                    Icon(Icons.AutoMirrored.Outlined.WrapText, L.netcap.ui.wrap, tint = if (wrap) AppColors.primary else AppColors.onBackground)
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, L.common.more, tint = AppColors.onBackground) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(L.netcap.ui.openInIde) }, leadingIcon = { Icon(Icons.Outlined.Code, null) }, onClick = {
                        menu = false
                        scope.launch {
                            netTry {
                                val r = NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "file", "pretty" to if (mode == BodyMode.PRETTY) 1 else 0))
                                host.openInIde(r.optString("path"))
                            }
                        }
                    })
                    DropdownMenuItem(text = { Text(L.netcap.ui.copyBody) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, enabled = info.text, onClick = {
                        menu = false
                        scope.launch {
                            netTry {
                                if (info.decodedSize > ai.antigem.data.clipboard.SafeClipboard.MAX_CHARS) {
                                    AppToastHelper.showToast(L.netcap.ui.tooBigToCopy(fmtSize(info.decodedSize)))
                                } else {
                                    // the body file, not the pages (a page holds at most 5,000 lines)
                                    val r = NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "file", "pretty" to if (mode == BodyMode.PRETTY) 1 else 0))
                                    val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.io.File(r.optString("path")).readText() }
                                    copyText(context, "body", text)
                                }
                            }
                        }
                    })
                    DropdownMenuItem(text = { Text(L.netcap.ui.saveBody) }, leadingIcon = { Icon(Icons.Outlined.Download, null) }, onClick = {
                        menu = false
                        scope.launch {
                            netTry {
                                val r = NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "file", "pretty" to 0))
                                AppToastHelper.showToast(L.netcap.screen.savedTo(r.optString("path")))
                            }
                        }
                    })
                }
            }
        }
        Hint(
            buildString {
                append(info.contentType.ifEmpty { info.type })
                append(" · ").append(fmtSize(info.decodedSize))
                if (info.encoding.isNotEmpty()) append(" (").append(info.encoding).append(' ').append(fmtSize(info.size)).append(')')
                if (info.truncated) append(" · ").append(L.netcap.ui.truncated)
            },
            Modifier.padding(horizontal = 14.dp)
        )
        if (findOpen && (mode == BodyMode.PRETTY || mode == BodyMode.TEXT)) {
            val pretty = mode == BodyMode.PRETTY
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NetSearchField(find, { find = it }, L.netcap.ui.findInBody, Modifier.weight(1f), onDone = {
                    scope.launch {
                        matches = if (find.isEmpty()) emptyList() else netTry {
                            NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "search", "find" to find, "pretty" to if (pretty) 1 else 0, "max" to 2000))
                                .optJSONArray("matches").objects().map { Triple(it.optInt("line"), it.optInt("col"), it.optInt("len")) }
                        } ?: emptyList()
                        matchIndex = 0
                        matches.firstOrNull()?.let { lineList.scrollToItem(it.first) }
                    }
                })
                Text(if (matches.isEmpty()) "0" else "${matchIndex + 1}/${matches.size}", color = AppColors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
                IconButton(onClick = {
                    if (matches.isNotEmpty()) { matchIndex = (matchIndex - 1 + matches.size) % matches.size; scope.launch { lineList.scrollToItem(matches[matchIndex].first) } }
                }) { Icon(Icons.Filled.KeyboardArrowUp, null, tint = AppColors.onBackground) }
                IconButton(onClick = {
                    if (matches.isNotEmpty()) { matchIndex = (matchIndex + 1) % matches.size; scope.launch { lineList.scrollToItem(matches[matchIndex].first) } }
                }) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = AppColors.onBackground) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.codeBg)) {
            when (mode) {
                BodyMode.PRETTY, BodyMode.TEXT -> LinesView(
                    flowId, side, pretty = mode == BodyMode.PRETTY,
                    total = if (mode == BodyMode.PRETTY) info.prettyLines else info.lines,
                    wrap = wrap, json = info.isJson, matches = matches, current = matches.getOrNull(matchIndex), state = lineList
                )
                BodyMode.TREE -> TreeView(flowId, side)
                BodyMode.HEX -> HexView(flowId, side, info.decodedSize)
                BodyMode.IMAGE -> ImageView(flowId, side)
            }
        }
    }
}

/** Text lines, fetched in pages as they scroll into view. */
@Composable
private fun LinesView(
    flowId: Long, side: String, pretty: Boolean, total: Int, wrap: Boolean, json: Boolean,
    matches: List<Triple<Int, Int, Int>>, current: Triple<Int, Int, Int>?, state: androidx.compose.foundation.lazy.LazyListState
) {
    val pages = remember(flowId, side, pretty) { mutableStateMapOf<Int, List<String>>() }
    val cont = remember(flowId, side, pretty) { mutableStateListOf<Int>() }
    var count by remember(flowId, side, pretty) { mutableIntStateOf(total.coerceAtLeast(0)) }
    var error by remember(flowId, side, pretty) { mutableStateOf<String?>(null) }
    val loading = remember(flowId, side, pretty) { HashSet<Int>() }
    val lock = remember { Mutex() }
    val scope = rememberCoroutineScope()

    fun load(page: Int) {
        if (page in pages || !loading.add(page)) return
        scope.launch {
            try {
                val r = NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "lines", "from" to page * LINE_PAGE, "count" to LINE_PAGE, "pretty" to if (pretty) 1 else 0))
                lock.withLock {
                    count = r.optInt("total", count)
                    r.optJSONArray("cont")?.let { a -> for (i in 0 until a.length()) cont.add(a.optInt(i)) }
                    pages[page] = r.optJSONArray("lines").strings()
                }
            } catch (e: Throwable) {
                error = e.message
            } finally {
                loading.remove(page)
            }
        }
    }
    LaunchedEffect(flowId, side, pretty) { load(0) }
    if (error != null && pages.isEmpty()) { Text(error!!, color = AppColors.danger, modifier = Modifier.padding(12.dp)); return }
    if (count <= 0 && pages.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }; return }

    val byLine = remember(matches) { matches.groupBy { it.first } }
    val gutter = remember(count) { count.toString().length }
    val style = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 17.sp, color = AppColors.codeText)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // without wrapping, the column is as wide as the longest line loaded (lines are cut at 2,000 characters)
    val maxChars = if (wrap) 0 else pages.values.maxOfOrNull { p -> p.maxOfOrNull { it.length } ?: 0 } ?: 0
    val charW = remember(density) { with(density) { measurer.measure("0", style).size.width.toDp() } }
    val colors = jsonColors()
    val content: @Composable () -> Unit = {
        SelectionContainer {
            LazyColumn(state = state, modifier = if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxHeight().width(charW * (maxChars + gutter + 3) + 24.dp)) {
                items(count, key = { it }) { i ->
                    val page = i / LINE_PAGE
                    val line = pages[page]?.getOrNull(i % LINE_PAGE)
                    if (line == null) LaunchedEffect(page) { load(page) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                        Text(
                            if (i in cont) "↪".padStart(gutter) else (i + 1).toString().padStart(gutter),
                            style = style.copy(color = AppColors.textSecondary.copy(alpha = 0.6f)), modifier = Modifier.padding(end = 8.dp)
                        )
                        val text = remember(line, byLine[i], current) {
                            if (line == null) AnnotatedString("") else highlightLine(line, json, colors, byLine[i], current?.takeIf { it.first == i })
                        }
                        Text(text, style = style, softWrap = wrap, modifier = Modifier.weight(1f, fill = wrap))
                    }
                }
            }
        }
    }
    if (wrap) content() else Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) { content() }
}

internal data class JsonColors(val key: Color, val string: Color, val number: Color, val keyword: Color, val punct: Color, val match: Color, val current: Color)

@Composable
internal fun jsonColors() = JsonColors(
    key = AppColors.syntaxAttribute, string = AppColors.syntaxString, number = AppColors.syntaxNumber,
    keyword = AppColors.syntaxKeyword, punct = AppColors.syntaxPunctuation,
    match = AppColors.warning.copy(alpha = 0.35f), current = AppColors.warning.copy(alpha = 0.75f)
)

/** Colors one JSON line (keys, strings, numbers, true/false/null) and marks search matches. */
internal fun highlightLine(line: String, json: Boolean, c: JsonColors, marks: List<Triple<Int, Int, Int>>?, current: Triple<Int, Int, Int>?): AnnotatedString =
    buildAnnotatedString {
        if (json && line.length <= 4000) {
            var i = 0
            val n = line.length
            while (i < n) {
                val ch = line[i]
                when {
                    ch == '"' -> {
                        var j = i + 1
                        while (j < n && line[j] != '"') { if (line[j] == '\\') j++; j++ }
                        j = (j + 1).coerceAtMost(n)
                        var k = j
                        while (k < n && line[k] == ' ') k++
                        val isKey = k < n && line[k] == ':'
                        withStyle(SpanStyle(color = if (isKey) c.key else c.string)) { append(line, i, j) }
                        i = j
                    }
                    ch == '-' || ch.isDigit() -> {
                        var j = i + 1
                        while (j < n && (line[j].isDigit() || line[j] in ".eE+-")) j++
                        withStyle(SpanStyle(color = c.number)) { append(line, i, j) }
                        i = j
                    }
                    line.startsWith("true", i) || line.startsWith("null", i) -> { withStyle(SpanStyle(color = c.keyword)) { append(line, i, i + 4) }; i += 4 }
                    line.startsWith("false", i) -> { withStyle(SpanStyle(color = c.keyword)) { append(line, i, i + 5) }; i += 5 }
                    ch in "{}[],:" -> { withStyle(SpanStyle(color = c.punct)) { append(ch) }; i++ }
                    else -> { append(ch); i++ }
                }
            }
        } else append(line)
        marks?.forEach { (_, col, len) ->
            val s = col.coerceIn(0, line.length)
            val e = (col + len).coerceIn(s, line.length)
            val isCur = current != null && current.second == col
            addStyle(SpanStyle(background = if (isCur) c.current else c.match), s, e)
        }
    }

private data class TreeNode(val key: String, val path: String, val kind: String, val preview: String, val count: Int, val depth: Int)

/** A JSON tree that loads a node's children when it is opened. */
@Composable
private fun TreeView(flowId: Long, side: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val children = remember(flowId, side) { mutableStateMapOf<String, List<TreeNode>>() }
    val open = remember(flowId, side) { mutableStateListOf<String>() }
    var rootKind by remember(flowId, side) { mutableStateOf("") }
    var error by remember(flowId, side) { mutableStateOf<String?>(null) }

    suspend fun fetch(path: String, depth: Int) {
        val r = NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "tree", "path" to path, "from" to 0, "count" to 2000))
        if (path.isEmpty()) rootKind = r.optString("kind")
        children[path] = r.optJSONArray("children").objects().map {
            TreeNode(it.optString("key"), it.optString("path"), it.optString("kind"), it.optString("preview"), it.optInt("count"), depth)
        }
    }
    LaunchedEffect(flowId, side) { runCatching { fetch("", 0) }.onFailure { error = it.message } }
    if (error != null) { Text(error!!, color = AppColors.danger, modifier = Modifier.padding(12.dp)); return }
    val visible = remember(children.toMap(), open.toList()) {
        val out = ArrayList<TreeNode>()
        fun walk(path: String) {
            children[path]?.forEach { n ->
                out += n
                if (n.path in open) walk(n.path)
            }
        }
        walk("")
        out
    }
    if (children[""] == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }; return }
    val c = jsonColors()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
        items(visible, key = { it.path }) { n ->
            val container = n.kind == "object" || n.kind == "array"
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        if (container) {
                            if (n.path in open) open.remove(n.path) else {
                                open.add(n.path)
                                if (children[n.path] == null) scope.launch { netTry { fetch(n.path, n.depth + 1) } }
                            }
                        } else copyText(context, n.key, n.preview)
                    }
                    .padding(start = (8 + n.depth * 14).dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (container) Icon(if (n.path in open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, tint = AppColors.textSecondary, modifier = Modifier.size(16.dp))
                else Spacer(Modifier.width(16.dp))
                Text(n.key, color = c.key, fontFamily = Mono, fontSize = 12.5.sp)
                Text(": ", color = c.punct, fontFamily = Mono, fontSize = 12.5.sp)
                val (txt, col) = when (n.kind) {
                    "object" -> "{ ${n.count} }" to AppColors.textSecondary
                    "array" -> "[ ${n.count} ]" to AppColors.textSecondary
                    "string" -> n.preview to c.string
                    "number" -> n.preview to c.number
                    else -> n.preview to c.keyword
                }
                Text(txt, color = col, fontFamily = Mono, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A hex dump, 16 bytes a row, read 4 KB at a time. */
@Composable
private fun HexView(flowId: Long, side: String, size: Long) {
    val pages = remember(flowId, side) { mutableStateMapOf<Int, ByteArray>() }
    val loading = remember(flowId, side) { HashSet<Int>() }
    val scope = rememberCoroutineScope()
    val rows = ((size + 15) / 16).toInt().coerceAtLeast(0)
    fun load(page: Int) {
        if (page in pages || !loading.add(page)) return
        scope.launch {
            runCatching {
                val r = NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "hex", "offset" to page.toLong() * HEX_PAGE, "length" to HEX_PAGE))
                pages[page] = Base64.decode(r.optString("data_base64"), Base64.DEFAULT)
            }
            loading.remove(page)
        }
    }
    val style = TextStyle(fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 16.sp, color = AppColors.codeText)
    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.fillMaxHeight().width(560.dp), contentPadding = PaddingValues(6.dp)) {
            items(rows, key = { it }) { row ->
                val off = row.toLong() * 16
                val page = (off / HEX_PAGE).toInt()
                val data = pages[page]
                if (data == null) LaunchedEffect(page) { load(page) }
                val line = remember(data, row) {
                    if (data == null) "%08x".format(off) else {
                        val start = (off - page.toLong() * HEX_PAGE).toInt()
                        val end = (start + 16).coerceAtMost(data.size)
                        val hex = StringBuilder()
                        val ascii = StringBuilder()
                        for (i in start until start + 16) {
                            if (i < end) {
                                val b = data[i].toInt() and 0xff
                                hex.append("%02x ".format(b))
                                ascii.append(if (b in 32..126) b.toChar() else '.')
                            } else hex.append("   ")
                            if (i == start + 7) hex.append(' ')
                        }
                        "%08x  %s %s".format(off, hex, ascii)
                    }
                }
                Text(line, style = style, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ImageView(flowId: Long, side: String) {
    var path by remember(flowId, side) { mutableStateOf<String?>(null) }
    var error by remember(flowId, side) { mutableStateOf<String?>(null) }
    LaunchedEffect(flowId, side) {
        runCatching { NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "file")).optString("path") }
            .onSuccess { path = it }.onFailure { error = it.message }
    }
    when {
        error != null -> Text(error!!, color = AppColors.danger, modifier = Modifier.padding(12.dp))
        path == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        else -> ai.antigem.ui.ide.ImageAssetViewer(filePath = path!!, fileName = path!!.substringAfterLast('/'), modifier = Modifier.fillMaxSize())
    }
}

/** Headers as a table: name, value; tap copies "name: value", the action copies all. */
@Composable
internal fun HeadersView(headers: List<Pair<String, String>>, emptyText: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    val shown = remember(headers, q) { if (q.isBlank()) headers else headers.filter { (k, v) -> k.contains(q, true) || v.contains(q, true) } }
    Column(modifier.fillMaxSize()) {
        if (headers.size > 8) {
            NetSearchField(q, { q = it }, L.netcap.ui.findHeader, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (headers.isEmpty()) { EmptyState(Icons.Outlined.Notes, emptyText); return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Hint(L.netcap.ui.headerCount(headers.size), Modifier.weight(1f))
                    TextButton(onClick = { copyText(context, "headers", headers.joinToString("\n") { "${it.first}: ${it.second}" }) }) { Text(L.netcap.ui.copyAll) }
                }
            }
            items(shown.size) { i ->
                val (k, v) = shown[i]
                Column(
                    Modifier.fillMaxWidth().clickable { copyText(context, k, v) }.padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text(k, color = AppColors.syntaxAttribute, fontFamily = Mono, fontSize = 12.5.sp)
                    Text(v, color = AppColors.onBackground, fontFamily = Mono, fontSize = 12.5.sp)
                }
                if (i < shown.size - 1) HorizontalDivider(color = AppColors.border.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

/** key = value pairs (query, cookies, form). */
@Composable
internal fun PairsView(pairs: List<Pair<String, String>>, emptyText: String) {
    val context = LocalContext.current
    if (pairs.isEmpty()) { EmptyState(Icons.Outlined.Notes, emptyText); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
        items(pairs.size) { i ->
            val (k, v) = pairs[i]
            Row(Modifier.fillMaxWidth().clickable { copyText(context, k, v) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(k, color = AppColors.syntaxAttribute, fontFamily = Mono, fontSize = 12.5.sp, modifier = Modifier.weight(0.4f))
                Text(v, color = AppColors.onBackground, fontFamily = Mono, fontSize = 12.5.sp, modifier = Modifier.weight(0.6f))
            }
        }
    }
}

internal fun parseQuery(q: String): List<Pair<String, String>> = q.split('&').filter { it.isNotEmpty() }.map {
    val k = it.substringBefore('=')
    val v = if ('=' in it) it.substringAfter('=') else ""
    fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    dec(k) to dec(v)
}

internal fun parseCookies(headers: List<Pair<String, String>>, response: Boolean): List<Pair<String, String>> =
    if (response) headers.filter { it.first.equals("set-cookie", true) }.map { (_, v) ->
        val first = v.substringBefore(';')
        first.substringBefore('=').trim() to (first.substringAfter('=', "").trim() + v.substringAfter(';', "").let { if (it.isEmpty()) "" else "  ;$it" })
    } else headers.filter { it.first.equals("cookie", true) }.flatMap { (_, v) ->
        v.split(';').filter { it.isNotBlank() }.map { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
    }
