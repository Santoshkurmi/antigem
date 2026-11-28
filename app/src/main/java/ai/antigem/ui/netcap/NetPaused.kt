package ai.antigem.ui.netcap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.netcap.NetPaused
import ai.antigem.data.netcap.NetRule
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapApi
import ai.antigem.data.netcap.headersJson
import ai.antigem.data.netcap.objects
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Seconds left before a paused item continues by itself. */
@Composable
internal fun rememberSecondsLeft(p: NetPaused): Int {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.id, p.readAt) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    return (((p.readAt + p.remainingMs) - now) / 1000).toInt().coerceAtLeast(0)
}

internal suspend fun resolvePaused(id: String, action: String, replace: JSONObject? = null, respond: JSONObject? = null) {
    val body = JSONObject().put("id", id).put("action", action)
    replace?.let { body.put("replace", it) }
    respond?.let { body.put("respond", it) }
    NetcapApi.post("/v1/ui/paused/resolve", body)
    NetStore.loadPaused()
}

/** The breakpoint queue, a quick "pause the next one", and the breakpoint rules. */
@Composable
internal fun PausedTab() {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val paused by NetStore.paused.collectAsState()
    val tick by NetStore.tick.collectAsState()
    var rules by remember { mutableStateOf<List<NetRule>>(emptyList()) }
    LaunchedEffect(tick / 20) {
        rules = runCatching { NetcapApi.get("/v1/ui/rules").optJSONArray("rules").objects().map(NetRule::parse).filter { it.type == "breakpoint" } }.getOrDefault(rules)
    }
    var match by rememberSaveable { mutableStateOf(TrafficUi.filter()) }
    var phase by rememberSaveable { mutableStateOf("request") }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 6.dp, 12.dp, LIST_BOTTOM), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                NetCard(L.netcap.ui.pauseNext) {
                    NetSearchField(match, { match = it }, L.netcap.ui.pauseNextHint, mono = true)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToggleChip(L.netcap.ui.request, phase == "request") { phase = "request" }
                        ToggleChip(L.netcap.ui.response, phase == "response") { phase = "response" }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(onClick = {
                            scope.launch {
                                netTry {
                                    NetcapApi.post("/v1/ui/breaknext", JSONObject().put("match", match.trim()).put("phase", phase).put("count", 1))
                                    AppToastHelper.showToast(L.netcap.ui.pauseNextOn)
                                }
                            }
                        }) { Text(L.netcap.ui.pauseNextButton) }
                    }
                }
            }
            if (paused.isEmpty()) {
                item { EmptyState(Icons.Outlined.PauseCircle, L.netcap.ui.nothingPaused, L.netcap.ui.nothingPausedHint) }
            } else {
                item { Text(L.netcap.ui.pausedNow(paused.size), color = AppColors.onBackground, fontWeight = FontWeight.SemiBold) }
                items(paused, key = { it.id }) { p -> PausedItemCard(p) }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L.netcap.ui.breakpointRules, color = AppColors.onBackground, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        host.navigate(NetDest.RuleEdit(JSONObject().put("name", L.netcap.ui.newBreakpoint).put("match", "").put("phase", "request")
                            .put("enabled", true).put("action", JSONObject().put("type", "breakpoint").put("timeout_s", 60))))
                    }) { Text(L.netcap.ui.newBreakpoint) }
                }
            }
            if (rules.isEmpty()) item { Hint(L.netcap.ui.noBreakpointRules) }
            items(rules, key = { "r" + it.id }) { r -> RuleRow(r) { rules = rules.map { x -> if (x.id == r.id) x.copy(enabled = it) else x } } }
        }
    }
}

@Composable
private fun PausedItemCard(p: NetPaused) {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val left = rememberSecondsLeft(p)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppColors.warningContainer.copy(alpha = 0.5f))
            .clickable { host.navigate(NetDest.PausedEdit(p.id)) }.padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(if (p.isResponse) L.netcap.ui.phaseResponse else L.netcap.ui.phaseRequest, AppColors.warning, filled = true)
            Spacer(Modifier.width(6.dp))
            Tag(p.method, methodColor(p.method))
            if (p.isResponse && p.status > 0) { Spacer(Modifier.width(4.dp)); Tag("${p.status}", statusColor(p.status)) }
            Spacer(Modifier.weight(1f))
            Text(L.netcap.ui.secondsLeft(left), color = if (left < 10) AppColors.danger else AppColors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Text(p.url, color = AppColors.onBackground, fontFamily = Mono, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { scope.launch { netTry { resolvePaused(p.id, "drop") } } }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(L.netcap.ui.drop, color = AppColors.danger)
            }
            OutlinedButton(onClick = { scope.launch { netTry { NetcapApi.post("/v1/ui/paused/extend", JSONObject().put("id", p.id).put("seconds", 60)); NetStore.loadPaused() } } },
                contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+60 s") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { host.navigate(NetDest.PausedEdit(p.id)) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(L.common.edit) }
            Button(onClick = { scope.launch { netTry { resolvePaused(p.id, "continue") } } }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(L.netcap.ui.continueButton) }
        }
    }
}

private enum class PausedTabPart { HEADERS, BODY }

/** A paused request or response: change anything, then continue, answer it yourself, or drop it. */
@Composable
internal fun PausedEditScreen(id: String) {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val paused by NetStore.paused.collectAsState()
    val p = paused.firstOrNull { it.id == id }
    val d = remember(id) { EditDraft() }
    var loaded by remember(id) { mutableStateOf(false) }
    var part by remember { mutableStateOf(PausedTabPart.BODY) }
    var mock by remember { mutableStateOf(false) }
    val textType = p != null && (p.bodyType.isEmpty() || p.bodyType in listOf("json", "html", "xml", "js", "css", "text", "form"))
    LaunchedEffect(p?.id) {
        if (p == null || loaded) return@LaunchedEffect
        d.method = p.method; d.url = p.url; d.status = if (p.status > 0) "${p.status}" else ""
        d.headers.clear(); p.headers.forEach { (k, v) -> d.headers.add(HeaderRow(k, v)) }
        d.originalSize = p.bodySize; d.originalType = p.bodyType
        d.textName = "body." + extFor(p.bodyType, p.headers.firstOrNull { it.first.equals("content-type", true) }?.second.orEmpty())
        when {
            p.bodyText != null && !p.bodyCut && p.bodySize <= EDITOR_MAX -> { d.text = p.bodyText; d.source = BodySource.TEXT }
            // over 256 KB the list leaves the body out: fetch it when the editor can hold it
            p.bodyCut && textType && p.bodySize in 1..EDITOR_MAX -> {
                d.source = BodySource.ORIGINAL
                d.loading = true
                runCatching { NetcapApi.getBytes("/v1/ui/paused/body", mapOf("id" to p.id)) }.onSuccess { b ->
                    val t = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrNull()
                    if (t != null) { d.text = t; d.editor?.setFile(d.textName, t); d.source = BodySource.TEXT }
                }
                d.loading = false
            }
            // a body (or one whose size is not known yet, -1) goes on as it is unless the user changes it
            p.bodySize != 0L -> d.source = BodySource.ORIGINAL
            else -> d.source = BodySource.NONE
        }
        loaded = true
    }
    Column(Modifier.fillMaxSize()) {
        NetTopBar(
            if (p?.isResponse == true) L.netcap.ui.pausedResponse else L.netcap.ui.pausedRequest,
            subtitle = p?.let { L.netcap.ui.secondsLeft(rememberSecondsLeft(it)) }, onBack = host.back
        ) {
            if (p != null) TextButton(onClick = { scope.launch { netTry { NetcapApi.post("/v1/ui/paused/extend", JSONObject().put("id", p.id).put("seconds", 60)); NetStore.loadPaused() } } }) { Text("+60 s") }
        }
        if (p == null) {
            EmptyState(Icons.Outlined.PlayCircle, L.netcap.ui.pausedGone, L.netcap.ui.pausedGoneHint)
            return@Column
        }
        RequestLine(d, response = p.isResponse)
        if (p.isResponse) Text(p.url, color = AppColors.textSecondary, fontFamily = Mono, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip(L.netcap.ui.body, part == PausedTabPart.BODY) { part = PausedTabPart.BODY }
            ToggleChip(L.netcap.ui.headersCount(d.headers.count { it.on }), part == PausedTabPart.HEADERS) { part = PausedTabPart.HEADERS }
        }
        Box(Modifier.weight(1f)) {
            when (part) {
                PausedTabPart.HEADERS -> HeadersEditor(d.headers)
                PausedTabPart.BODY -> BodyEditor(d, null, if (p.isResponse) "res" else "req")
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { scope.launch { netTry { resolvePaused(p.id, "drop"); host.back() } } }) { Text(L.netcap.ui.drop, color = AppColors.danger) }
            if (!p.isResponse) OutlinedButton(onClick = { mock = true }) { Text(L.netcap.ui.answer) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { netTry { resolvePaused(p.id, "continue"); host.back() } } }) { Text(L.netcap.ui.asIs) }
            Button(onClick = {
                val replace = JSONObject().put("headers", headersJson(d.headerList()))
                if (p.isResponse) d.status.toIntOrNull()?.let { replace.put("status", it) } else replace.put("method", d.method.trim().uppercase()).put("url", d.url.trim())
                d.putBody(replace)
                scope.launch { netTry { resolvePaused(p.id, "continue", replace = replace); host.back() } }
            }) { Text(L.netcap.ui.applyContinue) }
        }
    }
    if (mock && p != null) MockDialog(onDismiss = { mock = false }) { respond ->
        scope.launch { netTry { resolvePaused(p.id, "respond", respond = respond); host.back() } }
    }
}

/** Answer a paused request yourself: status, content type, body. */
@Composable
private fun MockDialog(onDismiss: () -> Unit, onSend: (JSONObject) -> Unit) {
    var status by remember { mutableStateOf("200") }
    var type by remember { mutableStateOf("application/json") }
    var body by remember { mutableStateOf("{}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.netcap.ui.answer) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(status, { status = it.filter(Char::isDigit).take(3) }, label = { Text(L.netcap.ui.status) }, singleLine = true)
                OutlinedTextField(type, { type = it }, label = { Text("Content-Type") }, singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text(L.netcap.ui.body) }, minLines = 4, maxLines = 10, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSend(JSONObject().put("status", status.toIntOrNull() ?: 200).put("headers", headersJson(listOf("Content-Type" to type))).put("body_text", body))
                onDismiss()
            }) { Text(L.common.send) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}
