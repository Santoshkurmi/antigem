package ai.antigem.ui.netcap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.netcap.NetScript
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapApi
import ai.antigem.data.netcap.objects
import ai.antigem.data.netcap.strings
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Starting points for a new script (the engine's JS API: docs/network_interceptor/05-rules-and-scripts.md). */
private val scriptTemplates = listOf(
    "add-header" to """
        // @name     NAME
        // @match    host:api.example.com
        // Adds a header to every matching request.

        function onRequest(f, ctx) {
          f.req.headers.set("X-Debug", "1")
        }
    """.trimIndent(),
    "change-json" to """
        // @name     NAME
        // @match    host:api.example.com path:/v1/config
        // Changes a field of a JSON answer before the app sees it.

        function onResponse(f, ctx) {
          var j = f.res.json()
          if (!j) return
          j.features = j.features || {}
          j.features.new_ui = true
          f.res.setJson(j)
        }
    """.trimIndent(),
    "mock" to """
        // @name     NAME
        // @match    host:api.example.com path:/v1/status
        // Answers without asking the server.

        function onRequest(f, ctx) {
          f.respond({ status: 200, json: { ok: true, from: "AntiGem" } })
        }
    """.trimIndent(),
    "token-keeper" to """
        // @name     NAME
        // @match    host:api.example.com
        // Keeps the token from the login answer and adds it to later requests.

        function onResponse(f, ctx) {
          if (f.path === "/v1/login" && f.res.status === 200) {
            var j = f.res.json()
            if (j && j.token) { ctx.vars.token = j.token; f.tag("login-ok") }
          }
        }

        function onRequest(f, ctx) {
          if (ctx.vars.token && !f.req.headers.has("Authorization")) f.req.headers.set("Authorization", "Bearer " + ctx.vars.token)
        }
    """.trimIndent(),
    "errors-to-findings" to """
        // @name     NAME
        // @match    status:5xx
        // Records every server error as a finding (off the traffic path).

        function onComplete(f, ctx) {
          ctx.finding({ severity: "warn", title: f.res.status + " on " + f.path, flow: f.id })
        }
    """.trimIndent(),
    "websocket" to """
        // @name     NAME
        // @match    kind:ws
        // Logs WebSocket messages; change m.text or call m.drop() to change them.

        function onMessage(m, ctx) {
          console.log(m.dir + " " + (m.text || "").slice(0, 200))
        }
    """.trimIndent(),
)

/** The engine's scripts: on/off, errors, counts, commands, logs; editing opens the file in the IDE. */
@Composable
internal fun ScriptsTab() {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val tick by NetStore.tick.collectAsState()
    var scripts by remember { mutableStateOf<List<NetScript>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(tick / 15, refresh) {
        runCatching { NetcapApi.get("/v1/ui/scripts").optJSONArray("scripts").objects().map(NetScript::parse) }.onSuccess { scripts = it }
    }
    Box(Modifier.fillMaxSize()) {
        val list = scripts
        if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Box }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 6.dp, 12.dp, LIST_BOTTOM), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { NewRowButton(L.netcap.ui.newScript) { creating = true } }
            if (list.isEmpty()) item { EmptyState(Icons.Outlined.Code, L.netcap.ui.noScripts, L.netcap.ui.noScriptsHint) }
            items(list, key = { it.name }) { s ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.card).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Code, null, tint = if (s.enabled) AppColors.primary else AppColors.textSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name + if (s.session) " · " + L.netcap.ui.thisSession else "", color = AppColors.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                listOfNotNull(s.match.ifEmpty { null }, s.hooks.joinToString(", ").ifEmpty { null }).joinToString(" · "),
                                color = AppColors.textSecondary, fontSize = 12.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(s.enabled, { on ->
                            scripts = scripts?.map { if (it.name == s.name) it.copy(enabled = on) else it }
                            scope.launch { netTry { NetcapApi.post("/v1/ui/scripts/enable", JSONObject().put("name", s.name).put("on", on)) } }
                        })
                    }
                    if (s.error.isNotEmpty()) Text(s.error, color = AppColors.danger, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Hint(L.netcap.ui.scriptCounts(s.calls, s.fails, s.skipped), Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { host.openInIde(s.file) }, label = { Text(L.common.edit) }, leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)) })
                        AssistChip(onClick = { host.navigate(NetDest.ScriptLogs(s.name)) }, label = { Text(L.netcap.ui.logs) }, leadingIcon = { Icon(Icons.Outlined.Subject, null, Modifier.size(16.dp)) })
                        AssistChip(onClick = {
                            scope.launch { netTry { host.navigate(NetDest.Text(L.netcap.ui.testScript(s.name), NetcapApi.tool("net_script", JSONObject().put("action", "test").put("name", s.name).put("limit", 10)))) } }
                        }, label = { Text(L.netcap.ui.test) }, leadingIcon = { Icon(Icons.Outlined.Science, null, Modifier.size(16.dp)) })
                        s.commands.forEach { c ->
                            AssistChip(onClick = {
                                scope.launch { netTry { host.navigate(NetDest.Text("${s.name}.$c", NetcapApi.tool("net_script", JSONObject().put("action", "call").put("name", "${s.name}.$c")))) } }
                            }, label = { Text(c) }, leadingIcon = { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp)) })
                        }
                        AssistChip(onClick = {
                            scope.launch { netTry { NetcapApi.post("/v1/ui/scripts/remove", JSONObject().put("name", s.name)); refresh++ } }
                        }, label = { Text(L.common.remove, color = AppColors.danger) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp), tint = AppColors.danger) })
                    }
                }
            }
        }
    }
    if (creating) NewScriptDialog(onDismiss = { creating = false }) { name, template, session ->
        creating = false
        scope.launch {
            netTry {
                val code = scriptTemplates.first { it.first == template }.second.replace("NAME", name)
                val r = NetcapApi.post("/v1/ui/scripts/new", JSONObject().put("name", name).put("code", code).put("session", session))
                refresh++
                host.openInIde(r.optString("file"))
            }
        }
    }
}

@Composable
private fun NewScriptDialog(onDismiss: () -> Unit, onCreate: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(scriptTemplates.first().first) }
    var session by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.netcap.ui.newScript) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }, label = { Text(L.common.name) }, singleLine = true)
                Text(L.netcap.ui.startFrom, color = AppColors.textSecondary, fontSize = 13.sp)
                scriptTemplates.forEach { (t, _) ->
                    Row(Modifier.fillMaxWidth().clickable { template = t }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(template == t, { template = t })
                        Text(t, color = AppColors.onBackground, fontFamily = Mono, fontSize = 13.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(session, { session = it }); Text(L.netcap.ui.onlyThisSession, color = AppColors.onBackground, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, template, session) }) { Text(L.common.add) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/** A script's log, refreshed every two seconds. */
@Composable
internal fun ScriptLogsScreen(name: String) {
    val host = LocalNetHost.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val state = rememberLazyListState()
    LaunchedEffect(name) {
        while (true) {
            runCatching { NetcapApi.get("/v1/ui/scripts/logs", mapOf("name" to name, "lines" to 500)).optJSONArray("lines").strings() }.onSuccess {
                val grew = it.size != lines.size
                lines = it
                if (grew && it.isNotEmpty()) state.scrollToItem(it.size - 1)
            }
            delay(2000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        NetTopBar(L.netcap.ui.logsOf(name), onBack = host.back) {
            IconButton(onClick = { copyText(context, "log", lines.joinToString("\n")) }) { Icon(Icons.Outlined.ContentCopy, L.common.copy, tint = AppColors.onBackground) }
        }
        if (lines.isEmpty()) { EmptyState(Icons.Outlined.Subject, L.netcap.ui.noLogs); return@Column }
        SelectionContainer(Modifier.weight(1f).padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(AppColors.codeBg)) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
                items(lines.size) { i ->
                    Text(lines[i], color = AppColors.codeText, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}
