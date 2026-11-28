package ai.antigem.ui.netcap

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.netcap.NetSession
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapApi
import ai.antigem.data.netcap.objects
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Decoder, compare, sessions, findings, DNS, export, the endpoint map, certificate and settings. */
@Composable
internal fun ToolsTab() {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val st by NetStore.state.collectAsState()
    @Composable
    fun tool(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.card).clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = AppColors.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = AppColors.onBackground, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Text(hint, color = AppColors.textSecondary, fontSize = 12.5.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = AppColors.textSecondary)
        }
    }
    fun runTool(title: String, name: String, args: JSONObject) {
        scope.launch { netTry { host.navigate(NetDest.Text(title, NetcapApi.tool(name, args))) } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tool(Icons.Outlined.Map, L.netcap.ui.endpointMap, L.netcap.ui.endpointMapHint) {
            runTool(L.netcap.ui.endpointMap, "net_summarize", JSONObject().put("group_by", "endpoint").put("filter", TrafficUi.filter()).put("limit", 200))
        }
        tool(Icons.Outlined.Transform, L.netcap.ui.decoder, L.netcap.ui.decoderHint) { host.navigate(NetDest.Decoder) }
        tool(Icons.Outlined.Compare, L.netcap.ui.compare, L.netcap.ui.compareHint) { host.navigate(NetDest.Compare(null, null)) }
        tool(Icons.Outlined.FolderOpen, L.netcap.ui.sessions, L.netcap.ui.sessionsHint(st.sessionName.ifEmpty { st.sessionId })) { host.navigate(NetDest.Sessions) }
        tool(Icons.Outlined.Flag, L.netcap.ui.findings, L.netcap.ui.findingsHint(st.findings)) { host.navigate(NetDest.Findings) }
        tool(Icons.Outlined.Dns, L.netcap.ui.dnsLog, L.netcap.ui.dnsLogHint) { host.navigate(NetDest.Dns) }
        tool(Icons.Outlined.IosShare, L.netcap.ui.exportHar, L.netcap.ui.exportHarHint) {
            runTool(L.netcap.ui.export, "net_export", JSONObject().put("format", "har").put("filter", TrafficUi.filter()))
        }
        tool(Icons.Outlined.FolderZip, L.netcap.ui.exportFolder, L.netcap.ui.exportFolderHint) {
            runTool(L.netcap.ui.export, "net_export", JSONObject().put("format", "folder").put("filter", TrafficUi.filter()))
        }
        tool(Icons.Outlined.Security, L.netcap.ui.certAndSettings, L.netcap.ui.certAndSettingsHint) { host.openSettings() }
        NetCard(L.netcap.ui.agentsTitle) { Hint(L.netcap.ui.agentsHint) }
        Spacer(Modifier.height(LIST_BOTTOM))
    }
}

private val decodeKinds = listOf("auto", "base64", "base64url", "url", "hex", "jwt", "gzip", "protobuf", "timestamp", "html", "sha256")

@Composable
internal fun DecoderScreen() {
    val host = LocalNetHost.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("auto") }
    var encode by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        NetTopBar(L.netcap.ui.decoder, onBack = host.back)
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, label = { Text(L.netcap.ui.input) }, minLines = 3, maxLines = 8,
                textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                decodeKinds.forEach { k -> ToggleChip(k, kind == k) { kind = k } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(encode, { encode = it }); Text(L.netcap.ui.encode, color = AppColors.onBackground, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (output.isNotEmpty()) TextButton(onClick = { copyText(context, "decoded", output) }) { Text(L.common.copy) }
                Button(enabled = input.isNotEmpty(), onClick = {
                    scope.launch {
                        output = netTry { NetcapApi.tool("net_decode", JSONObject().put("input", input).put("as", kind).put("encode", encode)) } ?: output
                    }
                }) { Text(if (encode) L.netcap.ui.encode else L.netcap.ui.decode) }
            }
        }
        if (output.isNotEmpty()) MonoTextBlock(output, Modifier.weight(1f).padding(12.dp))
    }
}

/** Two requests side by side as a diff (headers, JSON by path). */
@Composable
internal fun CompareScreen(a0: Long?, b0: Long?) {
    val host = LocalNetHost.current
    var a by remember { mutableStateOf(a0) }
    var b by remember { mutableStateOf(b0) }
    var parts by remember { mutableStateOf("both") }
    var text by remember { mutableStateOf<String?>(null) }
    val rows by NetStore.rows.collectAsState()
    LaunchedEffect(a, b, parts) {
        val x = a; val y = b
        text = if (x != null && y != null) netTry { NetcapApi.tool("net_diff", JSONObject().put("a", "f$x").put("b", "f$y").put("parts", parts)) } else null
    }
    Column(Modifier.fillMaxSize()) {
        NetTopBar(L.netcap.ui.compare, subtitle = listOfNotNull(a?.let { "f$it" }, b?.let { "f$it" }).joinToString(" ↔ "), onBack = host.back)
        if (a == null || b == null) {
            Hint(if (a == null) L.netcap.ui.pickFirst else L.netcap.ui.pickSecond, Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(rows.filter { it.id != a }.take(500), key = { it.id }) { r ->
                    FlowRowItem(r, selected = false, onClick = { if (a == null) a = r.id else b = r.id }, onLongClick = {})
                }
            }
            return@Column
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip(L.netcap.ui.both, parts == "both") { parts = "both" }
            ToggleChip(L.netcap.ui.request, parts == "req") { parts = "req" }
            ToggleChip(L.netcap.ui.response, parts == "res") { parts = "res" }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { b = null }) { Text(L.netcap.ui.changeSecond) }
        }
        val t = text
        if (t == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else DiffText(t, Modifier.weight(1f).padding(12.dp))
    }
}

@Composable
private fun DiffText(text: String, modifier: Modifier) {
    val added = AppColors.editorAddedText
    val removed = AppColors.editorRemovedText
    val addedBg = AppColors.editorAddedBg
    val removedBg = AppColors.editorRemovedBg
    val changed = AppColors.warning
    val annotated = remember(text) {
        buildAnnotatedString {
            text.lines().forEachIndexed { i, l ->
                if (i > 0) append('\n')
                val t = l.trimStart()
                when {
                    t.startsWith("+") -> withStyle(androidx.compose.ui.text.SpanStyle(color = added, background = addedBg)) { append(l) }
                    t.startsWith("-") -> withStyle(androidx.compose.ui.text.SpanStyle(color = removed, background = removedBg)) { append(l) }
                    t.startsWith("~") -> withStyle(androidx.compose.ui.text.SpanStyle(color = changed)) { append(l) }
                    else -> append(l)
                }
            }
        }
    }
    SelectionContainer(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.codeBg)) {
        Text(annotated, color = AppColors.codeText, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 17.sp,
            modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp))
    }
}

@Composable
internal fun SessionsScreen() {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<NetSession>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var rename by remember { mutableStateOf<NetSession?>(null) }
    var delete by remember { mutableStateOf<NetSession?>(null) }
    LaunchedEffect(refresh) {
        sessions = runCatching {
            val r = NetcapApi.get("/v1/ui/sessions")
            (r.optJSONArray("sessions") ?: r.optJSONArray("items")).objects().map(NetSession::parse)
        }.getOrDefault(sessions ?: emptyList())
    }
    suspend fun act(action: String, id: String, name: String = "") {
        NetcapApi.post("/v1/ui/sessions", JSONObject().put("action", action).put("id", id).put("name", name))
        refresh++
        NetStore.reload()
    }
    Column(Modifier.fillMaxSize()) {
        NetTopBar(L.netcap.ui.sessions, onBack = host.back) {
            TextButton(onClick = { scope.launch { netTry { act("new", "") } } }) { Text(L.netcap.ui.newSession) }
        }
        val list = sessions
        if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list.sortedByDescending { it.id.removePrefix("s").toIntOrNull() ?: 0 }, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (s.active) AppColors.primary.copy(alpha = 0.12f) else AppColors.card)
                        .clickable { if (!s.active) scope.launch { netTry { act("switch", s.id) } } }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name.ifEmpty { s.id }, color = AppColors.onBackground, fontSize = 14.5.sp, fontWeight = if (s.active) FontWeight.SemiBold else FontWeight.Normal)
                        Text(L.netcap.ui.sessionInfo(s.id, s.flows, fmtSize(s.disk)), color = AppColors.textSecondary, fontSize = 12.sp)
                    }
                    if (s.active) Tag(L.netcap.ui.active, AppColors.primary)
                    IconButton(onClick = { rename = s }) { Icon(Icons.Outlined.Edit, L.netcap.ui.rename, tint = AppColors.textSecondary) }
                    if (!s.active) IconButton(onClick = { delete = s }) { Icon(Icons.Outlined.Delete, L.common.delete, tint = AppColors.danger) }
                }
            }
        }
    }
    rename?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.name) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text(L.netcap.ui.rename) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { rename = null; scope.launch { netTry { act("rename", s.id, name.trim()) } } }) { Text(L.common.save) } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text(L.common.cancel) } }
        )
    }
    delete?.let { s ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text(L.netcap.ui.deleteSession(s.name.ifEmpty { s.id })) },
            text = { Text(L.netcap.ui.deleteSessionHint) },
            confirmButton = { TextButton(onClick = { delete = null; scope.launch { netTry { act("delete", s.id) } } }) { Text(L.common.delete, color = AppColors.danger) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
internal fun FindingsScreen() = ToolTextScreen(L.netcap.ui.findings, "net_findings", JSONObject().put("action", "list").put("limit", 200))

@Composable
internal fun DnsScreen() = ToolTextScreen(L.netcap.ui.dnsLog, "net_dns", JSONObject().put("limit", 500))

@Composable
private fun ToolTextScreen(title: String, toolName: String, args: JSONObject) {
    var text by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { text = netTry { NetcapApi.tool(toolName, args) } ?: text ?: "" }
    val host = LocalNetHost.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        NetTopBar(title, onBack = host.back) {
            IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, L.common.refresh, tint = AppColors.onBackground) }
            IconButton(onClick = { copyText(context, title, text.orEmpty()) }) { Icon(Icons.Outlined.ContentCopy, L.common.copy, tint = AppColors.onBackground) }
        }
        val t = text
        if (t == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else MonoTextBlock(t, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
