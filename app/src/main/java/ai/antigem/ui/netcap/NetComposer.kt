package ai.antigem.ui.netcap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ai.antigem.data.netcap.NetFlow
import ai.antigem.data.netcap.NetRow
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapApi
import ai.antigem.data.netcap.headersJson
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import ai.antigem.ui.ide.CodeEditorView
import ai.antigem.ui.ide.EditorThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Bodies up to this size load into the editor; bigger ones are edited as a file in the IDE. */
internal const val EDITOR_MAX = 8L * 1024 * 1024

internal class HeaderRow(key: String, value: String, on: Boolean = true) {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
    var on by mutableStateOf(on)
}

internal enum class BodySource { TEXT, ORIGINAL, FILE, NONE }

/** What the request/response editor holds (the composer and the breakpoint editor share it). */
internal class EditDraft {
    var method by mutableStateOf("GET")
    var url by mutableStateOf("")
    var status by mutableStateOf("")
    val headers = mutableStateListOf<HeaderRow>()
    var source by mutableStateOf(BodySource.NONE)
    var filePath by mutableStateOf("")
    var originalSize by mutableLongStateOf(0L)
    var originalType by mutableStateOf("")
    /** The body text: in the editor while it shows, kept here when it is not composed. */
    var text by mutableStateOf("")
    var textName by mutableStateOf("body.txt")
    var editor: CodeEditorView? = null
    var loading by mutableStateOf(false)

    fun bodyText(): String = editor?.getText() ?: text

    fun headerList(): List<Pair<String, String>> = headers.filter { it.on && it.key.isNotBlank() }.map { it.key.trim() to it.value }

    /** body_text / body_file (or nothing: the original body) into [o]. */
    fun putBody(o: JSONObject) {
        when (source) {
            BodySource.TEXT -> o.put("body_text", bodyText())
            BodySource.FILE -> o.put("body_file", filePath)
            BodySource.NONE -> o.put("body_text", "")
            BodySource.ORIGINAL -> Unit
        }
    }
}

internal fun extFor(type: String, contentType: String): String = when {
    type == "json" || contentType.contains("json") -> "json"
    type == "html" || contentType.contains("html") -> "html"
    type == "xml" || contentType.contains("xml") -> "xml"
    type == "js" || contentType.contains("javascript") -> "js"
    type == "css" -> "css"
    type == "form" -> "txt"
    else -> "txt"
}

/**
 * Loads a stored body into [d]: text into the editor when it is text and small enough, else a copy of the body file
 * (edited in the IDE) or the original bytes as they are.
 */
internal suspend fun loadBody(d: EditDraft, flowId: Long, side: String, info: ai.antigem.data.netcap.NetBodyInfo) {
    if (!info.present) { d.source = BodySource.NONE; return }
    d.originalSize = info.decodedSize
    d.originalType = info.contentType.ifEmpty { info.type }
    d.textName = "body." + extFor(info.type, info.contentType)
    if (!info.text || info.truncated) { d.source = BodySource.ORIGINAL; return }
    if (info.decodedSize > EDITOR_MAX) { d.source = BodySource.ORIGINAL; return }
    d.loading = true
    try {
        val r = NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "file", "pretty" to 0))
        val text = withContext(Dispatchers.IO) { File(r.optString("path")).readText() }
        d.text = text
        d.editor?.setFile(d.textName, text)
        d.source = BodySource.TEXT
    } catch (e: Throwable) {
        d.source = BodySource.ORIGINAL
    } finally {
        d.loading = false
    }
}

/** A copy of the body to edit in the IDE (the body file the viewers use stays as it was). */
internal suspend fun bodyCopyForIde(flowId: Long, side: String, pretty: Boolean): String {
    val r = NetcapApi.get("/v1/ui/body", mapOf("id" to flowId, "side" to side, "view" to "file", "pretty" to if (pretty) 1 else 0))
    val src = File(r.optString("path"))
    return withContext(Dispatchers.IO) {
        val dir = File(src.parentFile?.parentFile, "edit").apply { mkdirs() }
        val dst = File(dir, "f$flowId-${System.currentTimeMillis() / 1000}.$side.${src.extension.ifEmpty { "txt" }}")
        src.copyTo(dst, overwrite = true)
        dst.absolutePath
    }
}

private enum class ComposerTab { HEADERS, QUERY, BODY, OPTIONS }

/** Edit and send: a stored request changed and sent again, or a new one. The answers appear in the list. */
@Composable
internal fun ComposerScreen(fromId: Long?) {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val d = remember { EditDraft() }
    var tab by remember { mutableStateOf(ComposerTab.HEADERS) }
    var follow by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf("30") }
    var count by remember { mutableStateOf("1") }
    var interval by remember { mutableStateOf("0") }
    var parallel by remember { mutableStateOf("1") }
    var sending by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Long>>(emptyList()) }
    var resultError by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableStateOf<NetFlow?>(null) }

    LaunchedEffect(fromId) {
        if (fromId == null) {
            d.method = "GET"; d.url = "https://"; d.source = BodySource.NONE
            tab = ComposerTab.HEADERS
            return@LaunchedEffect
        }
        netTry {
            val f = NetFlow.parse(NetcapApi.get("/v1/ui/flow", mapOf("id" to fromId)))
            origin = f
            d.method = f.row.method
            d.url = f.row.url
            d.headers.clear()
            f.reqHeaders.filterNot { (k, _) -> k.equals("content-length", true) || k.startsWith(":") }.forEach { (k, v) -> d.headers.add(HeaderRow(k, v)) }
            loadBody(d, fromId, "req", f.req)
            if (f.req.present) tab = ComposerTab.BODY
        }
    }

    Column(Modifier.fillMaxSize()) {
        NetTopBar(L.netcap.ui.editAndSend, subtitle = fromId?.let { "f$it" }, onBack = host.back) {
            Button(
                enabled = !sending && d.url.length > 8,
                onClick = {
                    sending = true
                    resultError = null
                    val body = JSONObject().put("method", d.method.trim().uppercase()).put("url", d.url.trim())
                        .put("headers", headersJson(d.headerList()))
                        .put("follow_redirects", follow)
                        .put("timeout_ms", (timeout.toIntOrNull() ?: 30) * 1000)
                    if (fromId != null) body.put("from", "f$fromId")
                    d.putBody(body)
                    val c = count.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                    if (c > 1) body.put("repeat", JSONObject().put("count", c).put("interval_ms", interval.toIntOrNull() ?: 0)
                        .put("concurrency", (parallel.toIntOrNull() ?: 1).coerceIn(1, 16)))
                    scope.launch {
                        try {
                            val r = NetcapApi.post("/v1/ui/send", body)
                            val ids = r.optJSONArray("ids")
                            results = (0 until (ids?.length() ?: 0)).map { ids!!.optLong(it) }
                            val errs = r.optJSONArray("errors")
                            resultError = (0 until (errs?.length() ?: 0)).map { errs!!.optString(it) }.firstOrNull { it.isNotBlank() }
                        } catch (e: Throwable) {
                            resultError = e.message
                        } finally {
                            sending = false
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.padding(end = 6.dp)
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.onBackground)
                else Icon(Icons.Outlined.Send, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(L.common.send)
            }
        }
        RequestLine(d)
        if (results.isNotEmpty() || resultError != null) ResultCard(results, resultError, origin?.row?.id)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ComposerTab.entries.forEach { t ->
                ToggleChip(
                    when (t) {
                        ComposerTab.HEADERS -> L.netcap.ui.headersCount(d.headers.count { it.on })
                        ComposerTab.QUERY -> L.netcap.ui.queryCount(parseQuery(d.url.substringAfter('?', "")).size)
                        ComposerTab.BODY -> L.netcap.ui.body
                        ComposerTab.OPTIONS -> L.netcap.ui.options
                    }, tab == t
                ) { tab = t }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                ComposerTab.HEADERS -> HeadersEditor(d.headers)
                ComposerTab.QUERY -> QueryEditor(d)
                ComposerTab.BODY -> BodyEditor(d, fromId, "req")
                ComposerTab.OPTIONS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(follow, { follow = it })
                        Spacer(Modifier.width(10.dp))
                        Text(L.netcap.ui.followRedirects, color = AppColors.onBackground)
                    }
                    OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) }, label = { Text(L.netcap.ui.timeoutSeconds) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(L.netcap.ui.repeat, color = AppColors.onBackground, fontWeight = FontWeight.Medium)
                    OutlinedTextField(count, { count = it.filter(Char::isDigit) }, label = { Text(L.netcap.ui.repeatCount) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text(L.netcap.ui.repeatInterval) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(parallel, { parallel = it.filter(Char::isDigit) }, label = { Text(L.netcap.ui.repeatParallel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Hint(L.netcap.ui.variablesHint)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(ids: List<Long>, error: String?, originId: Long?) {
    val host = LocalNetHost.current
    val rows by NetStore.rows.collectAsState()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(AppColors.card).padding(10.dp)
    ) {
        if (error != null) Text(error, color = AppColors.danger, fontSize = 13.sp)
        ids.takeLast(3).forEach { id ->
            val r = rows.firstOrNull { it.id == id }
            Row(Modifier.fillMaxWidth().clickable { host.navigate(NetDest.Detail(id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r != null) {
                    Tag(if (r.status > 0) "${r.status}" else "…", statusColor(r.status, r.error))
                    Spacer(Modifier.width(8.dp))
                    Text("f$id · ${fmtSize(r.resSize)} · ${fmtMs(r.ms)}", color = AppColors.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
                } else Text("f$id", color = AppColors.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (originId != null) TextButton(onClick = { host.navigate(NetDest.Compare(originId, id)) }) { Text(L.netcap.ui.compare) }
                Icon(Icons.Outlined.ChevronRight, null, tint = AppColors.textSecondary)
            }
        }
        if (ids.size > 3) Hint(L.netcap.ui.sentMany(ids.size))
    }
}

/** Method and URL (or the status, for a response). */
@Composable
internal fun RequestLine(d: EditDraft, response: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
        if (response) {
            OutlinedTextField(d.status, { d.status = it.filter(Char::isDigit).take(3) }, label = { Text(L.netcap.ui.status) }, singleLine = true, modifier = Modifier.width(120.dp))
            return@Row
        }
        var open by remember { mutableStateOf(false) }
        Box {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(methodColor(d.method).copy(alpha = 0.15f)).clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) { Text(d.method.ifEmpty { "GET" }, color = methodColor(d.method), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").forEach { m ->
                    DropdownMenuItem(text = { Text(m, fontFamily = Mono, color = methodColor(m)) }, onClick = { d.method = m; open = false })
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AppColors.fill).padding(horizontal = 10.dp, vertical = 10.dp)) {
            BasicTextField(
                d.url, { d.url = it.replace("\n", "") },
                textStyle = TextStyle(color = AppColors.onBackground, fontFamily = Mono, fontSize = 13.sp),
                cursorBrush = SolidColor(AppColors.primary), maxLines = 4, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Headers: on/off, name, value; add and remove. */
@Composable
internal fun HeadersEditor(headers: MutableList<HeaderRow>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 6.dp, end = 6.dp, bottom = 80.dp)) {
        itemsIndexed(headers, key = { i, h -> System.identityHashCode(h) }) { _, h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(h.on, { h.on = it })
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(AppColors.fill).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    BasicTextField(h.key, { h.key = it.replace("\n", "") }, singleLine = true,
                        textStyle = TextStyle(color = AppColors.syntaxAttribute, fontFamily = Mono, fontSize = 12.5.sp),
                        cursorBrush = SolidColor(AppColors.primary), modifier = Modifier.fillMaxWidth())
                    BasicTextField(h.value, { h.value = it.replace("\n", "") },
                        textStyle = TextStyle(color = AppColors.onBackground, fontFamily = Mono, fontSize = 12.5.sp),
                        cursorBrush = SolidColor(AppColors.primary), modifier = Modifier.fillMaxWidth())
                }
                IconButton(onClick = { headers.remove(h) }) { Icon(Icons.Filled.Close, L.common.remove, tint = AppColors.textSecondary) }
            }
        }
        item {
            TextButton(onClick = { headers.add(HeaderRow("", "")) }) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text(L.netcap.ui.addHeader)
            }
        }
    }
}

/** The URL's query as rows; changes are written back into the URL. */
@Composable
private fun QueryEditor(d: EditDraft) {
    val rows = remember { mutableStateListOf<HeaderRow>() }
    LaunchedEffect(Unit) {
        rows.clear()
        parseQuery(d.url.substringAfter('?', "")).forEach { (k, v) -> rows.add(HeaderRow(k, v)) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { rows.map { Triple(it.key, it.value, it.on) } }.drop(1).collect { list ->
            val base = d.url.substringBefore('?')
            fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            val q = list.filter { it.third && it.first.isNotBlank() }.joinToString("&") { (k, v, _) -> enc(k) + if (v.isEmpty()) "" else "=" + enc(v) }
            val next = if (q.isEmpty()) base else "$base?$q"
            if (rows.isNotEmpty() || d.url.contains('?')) d.url = next
        }
    }
    HeadersEditor(rows)
}

/** The body: text in the code editor, the original bytes, a file, or none. */
@Composable
internal fun BodyEditor(d: EditDraft, flowId: Long?, side: String) {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var wrap by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleChip(L.netcap.ui.bodyText, d.source == BodySource.TEXT) { d.source = BodySource.TEXT }
            if (d.originalSize != 0L) ToggleChip(L.netcap.ui.bodyOriginal(if (d.originalSize < 0) "?" else fmtSize(d.originalSize)), d.source == BodySource.ORIGINAL) { d.source = BodySource.ORIGINAL }
            ToggleChip(L.netcap.ui.bodyFile, d.source == BodySource.FILE) { d.source = BodySource.FILE }
            ToggleChip(L.netcap.ui.bodyNone, d.source == BodySource.NONE) { d.source = BodySource.NONE }
        }
        when (d.source) {
            BodySource.TEXT -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val t = d.bodyText()
                        val p = prettyMaybe(t)
                        if (p != t) { d.text = p; d.editor?.setFile(d.textName, p) }
                    }) { Icon(Icons.Outlined.DataObject, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(L.netcap.ui.formatJson) }
                    TextButton(onClick = {
                        val t = d.bodyText()
                        val c = runCatching { val s = t.trim(); if (s.startsWith("{")) JSONObject(s).toString() else if (s.startsWith("[")) org.json.JSONArray(s).toString() else t }.getOrDefault(t)
                        if (c != t) { d.text = c; d.editor?.setFile(d.textName, c) }
                    }) { Text(L.netcap.ui.minify) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { wrap = !wrap; d.editor?.editor?.isWordwrap = wrap }) {
                        Icon(Icons.AutoMirrored.Outlined.WrapText, L.netcap.ui.wrap, tint = if (wrap) AppColors.primary else AppColors.onBackground)
                    }
                }
                if (d.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val theme = EditorThemeColors.fromAppTheme()
                AndroidView(
                    factory = { ctx ->
                        CodeEditorView(ctx).also { v ->
                            v.applyThemeColors(theme)
                            v.setFile(d.textName, d.text)
                            d.editor = v
                        }
                    },
                    update = { it.applyThemeColors(theme) },
                    onRelease = { v -> d.text = v.getText(); if (d.editor === v) d.editor = null },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                )
            }
            BodySource.ORIGINAL -> Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L.netcap.ui.originalBodyHint(fmtSize(d.originalSize), d.originalType), color = AppColors.onBackground, fontSize = 13.5.sp)
                if (flowId != null) OutlinedButton(onClick = {
                    scope.launch {
                        netTry {
                            val path = bodyCopyForIde(flowId, side, pretty = false)
                            d.filePath = path; d.source = BodySource.FILE
                            host.openInIde(path)
                        }
                    }
                }) { Icon(Icons.Outlined.Code, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(L.netcap.ui.editInIde) }
            }
            BodySource.FILE -> Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(d.filePath, { d.filePath = it.trim() }, label = { Text(L.netcap.ui.filePath) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Hint(L.netcap.ui.fileBodyHint)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (flowId != null) OutlinedButton(onClick = {
                        scope.launch {
                            netTry {
                                val path = bodyCopyForIde(flowId, side, pretty = false)
                                d.filePath = path
                                host.openInIde(path)
                            }
                        }
                    }) { Text(L.netcap.ui.copyBodyToFile) }
                    if (d.filePath.isNotEmpty()) OutlinedButton(onClick = { host.openInIde(d.filePath) }) { Text(L.netcap.ui.openInIde) }
                }
                val f = remember(d.filePath) { File(d.filePath) }
                if (d.filePath.isNotEmpty()) Hint(if (f.isFile) fmtSize(f.length()) else L.netcap.ui.fileMissing, color = if (f.isFile) AppColors.textSecondary else AppColors.danger)
            }
            BodySource.NONE -> Hint(L.netcap.ui.noBodySent, Modifier.padding(14.dp))
        }
    }
}
