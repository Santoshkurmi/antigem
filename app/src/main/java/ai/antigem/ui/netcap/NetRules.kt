package ai.antigem.ui.netcap

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.netcap.NetRule
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapApi
import ai.antigem.data.netcap.objects
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import ai.antigem.ui.components.AppToastHelper
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class RuleType(val id: String, val label: String, val hint: String, val icon: ImageVector)

internal fun ruleTypes() = listOf(
    RuleType("rewrite", L.netcap.ui.rtRewrite, L.netcap.ui.rtRewriteHint, Icons.Outlined.AutoFixHigh),
    RuleType("mock", L.netcap.ui.rtMock, L.netcap.ui.rtMockHint, Icons.Outlined.TheaterComedy),
    RuleType("map_local", L.netcap.ui.rtMapLocal, L.netcap.ui.rtMapLocalHint, Icons.Outlined.DriveFileMove),
    RuleType("map_remote", L.netcap.ui.rtMapRemote, L.netcap.ui.rtMapRemoteHint, Icons.Outlined.AltRoute),
    RuleType("block", L.netcap.ui.rtBlock, L.netcap.ui.rtBlockHint, Icons.Outlined.Block),
    RuleType("delay", L.netcap.ui.rtDelay, L.netcap.ui.rtDelayHint, Icons.Outlined.Speed),
    RuleType("breakpoint", L.netcap.ui.rtBreakpoint, L.netcap.ui.rtBreakpointHint, Icons.Outlined.PauseCircle),
    RuleType("tag", L.netcap.ui.rtTag, L.netcap.ui.rtTagHint, Icons.Outlined.Sell),
    RuleType("set_var", L.netcap.ui.rtSetVar, L.netcap.ui.rtSetVarHint, Icons.Outlined.DataObject),
    RuleType("passthrough", L.netcap.ui.rtPassthrough, L.netcap.ui.rtPassthroughHint, Icons.Outlined.LockOpen),
    RuleType("ignore", L.netcap.ui.rtIgnore, L.netcap.ui.rtIgnoreHint, Icons.Outlined.VisibilityOff),
)

/** All rules (made here, by an agent, or by a file in the rules folder) and the variables. */
@Composable
internal fun RulesTab() {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val tick by NetStore.tick.collectAsState()
    var rules by remember { mutableStateOf<List<NetRule>?>(null) }
    var vars by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var pickType by remember { mutableStateOf(false) }
    var editVar by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(tick / 15, refresh) {
        runCatching { NetcapApi.get("/v1/ui/rules") }.onSuccess { r ->
            rules = r.optJSONArray("rules").objects().map(NetRule::parse)
            val v = r.optJSONObject("vars")
            vars = v?.keys()?.asSequence()?.map { it to v.optString(it) }?.sortedBy { it.first }?.toList() ?: emptyList()
        }
    }
    Box(Modifier.fillMaxSize()) {
        val list = rules
        if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Box }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 6.dp, 12.dp, LIST_BOTTOM), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { NewRowButton(L.netcap.ui.newRule) { pickType = true } }
            if (list.isEmpty()) item { EmptyState(Icons.Outlined.Rule, L.netcap.ui.noRules, L.netcap.ui.noRulesHint) }
            items(list, key = { it.id }) { r -> RuleRow(r) { on -> rules = rules?.map { x -> if (x.id == r.id) x.copy(enabled = on) else x } } }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L.netcap.ui.variables, color = AppColors.onBackground, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editVar = "" to "" }) { Text(L.netcap.ui.addVariable) }
                }
                Hint(L.netcap.ui.variablesHint)
            }
            items(vars, key = { "v:" + it.first }) { (k, v) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.card).clickable { editVar = k to v }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("{{$k}}", color = AppColors.primary, fontFamily = Mono, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(v, color = AppColors.onBackground, fontFamily = Mono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (pickType) RuleTypePicker(onDismiss = { pickType = false }) { t ->
        pickType = false
        val action = JSONObject().put("type", t)
        when (t) {
            "mock" -> action.put("status", 200).put("content_type", "application/json").put("body", "{}")
            "delay" -> action.put("ms", 2000)
            "breakpoint" -> action.put("timeout_s", 60)
            "map_remote" -> action.put("url", "http://192.168.1.2:8080")
        }
        host.navigate(NetDest.RuleEdit(JSONObject().put("name", "").put("match", TrafficUi.filter()).put("enabled", true).put("action", action)
            .put("phase", if (t in listOf("tag", "set_var")) "response" else "request")))
    }
    editVar?.let { (k0, v0) ->
        var k by remember(k0) { mutableStateOf(k0) }
        var v by remember(k0) { mutableStateOf(v0) }
        AlertDialog(
            onDismissRequest = { editVar = null },
            title = { Text(L.netcap.ui.variable) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(k, { k = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' || c == '-' } }, label = { Text(L.common.name) }, singleLine = true, enabled = k0.isEmpty())
                    OutlinedTextField(v, { v = it }, label = { Text(L.netcap.ui.value) }, maxLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editVar = null
                    scope.launch { netTry { NetcapApi.post("/v1/ui/vars", JSONObject().put("name", k).put("value", v)); refresh++ } }
                }) { Text(L.common.save) }
            },
            dismissButton = {
                Row {
                    if (k0.isNotEmpty()) TextButton(onClick = {
                        editVar = null
                        scope.launch { netTry { NetcapApi.post("/v1/ui/vars", JSONObject().put("name", k0).put("delete", true)); refresh++ } }
                    }) { Text(L.common.delete, color = AppColors.danger) }
                    TextButton(onClick = { editVar = null }) { Text(L.common.cancel) }
                }
            }
        )
    }
}

/** One rule: type, name, what it matches, hits, on/off. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RuleRow(r: NetRule, onToggled: (Boolean) -> Unit) {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val type = ruleTypes().firstOrNull { it.id == r.type }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.card)
            .clickable { host.navigate(NetDest.RuleEdit(JSONObject(r.raw.toString()))) }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(type?.icon ?: Icons.Outlined.Rule, null, tint = if (r.enabled) AppColors.primary else AppColors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name.ifEmpty { type?.label ?: r.type }, color = AppColors.onBackground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${r.id} · ${type?.label ?: r.type} · ${r.match.ifEmpty { L.netcap.ui.everything }}",
                color = AppColors.textSecondary, fontSize = 12.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (r.hits > 0) Text(L.netcap.ui.hits(r.hits), color = AppColors.textSecondary, fontSize = 11.5.sp)
        }
        Switch(r.enabled, { on ->
            onToggled(on)
            scope.launch { netTry { NetcapApi.post("/v1/ui/rules/enable", JSONObject().put("id", r.id).put("on", on)) } }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleTypePicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Text(L.netcap.ui.newRule, color = AppColors.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            ruleTypes().forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { onPick(t.id) }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(t.icon, null, tint = AppColors.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(t.label, color = AppColors.onBackground, fontSize = 14.5.sp)
                        Text(t.hint, color = AppColors.textSecondary, fontSize = 12.5.sp)
                    }
                }
            }
        }
    }
}

// ---- the editor ----

private class KV(k: String, v: String) {
    var k by mutableStateOf(k)
    var v by mutableStateOf(v)
}

private fun kvFrom(o: JSONObject?, jsonValues: Boolean = false): List<KV> = o?.keys()?.asSequence()?.map { k ->
    val v = o.opt(k)
    // JSON fields show their value as JSON (strings in quotes), so "123" stays a string and 123 a number when saved
    KV(k, when {
        v == null || v == JSONObject.NULL -> if (jsonValues) "null" else ""
        jsonValues && v is String -> JSONObject.quote(v)
        else -> v.toString()
    })
}?.toList() ?: emptyList()

private fun kvJson(list: List<KV>, parseValues: Boolean = false): JSONObject = JSONObject().apply {
    list.filter { it.k.isNotBlank() }.forEach { kv ->
        // a JSON value when the whole text is one (true, 12, "text", {…}); anything else is kept as text
        val v: Any = if (parseValues && kv.v.isNotBlank()) {
            runCatching { JSONArray("[" + kv.v.trim() + "]").takeIf { it.length() == 1 }?.get(0) }.getOrNull() ?: kv.v
        } else kv.v
        put(kv.k.trim(), v)
    }
}

private class ReplaceRow(find: String, with: String, regex: Boolean) {
    var find by mutableStateOf(find)
    var with by mutableStateOf(with)
    var regex by mutableStateOf(regex)
}

/** Edits one rule; the action's fields depend on its type. "Test" shows which stored flows it would change. */
@Composable
internal fun RuleEditScreen(rule: JSONObject) {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val a0 = rule.optJSONObject("action") ?: JSONObject()
    val id = rule.optString("id")
    var name by remember { mutableStateOf(rule.optString("name")) }
    var match by remember { mutableStateOf(rule.optString("match")) }
    var enabled by remember { mutableStateOf(rule.optBoolean("enabled", true)) }
    var phase by remember { mutableStateOf(rule.optString("phase").ifEmpty { "request" }) }
    var priority by remember { mutableStateOf(rule.optInt("priority").takeIf { it != 0 }?.toString() ?: "") }
    var type by remember { mutableStateOf(a0.optString("type").ifEmpty { "rewrite" }) }
    // action fields (all kept, only the type's own are saved)
    var status by remember { mutableStateOf(a0.optInt("status").takeIf { it != 0 }?.toString() ?: "") }
    var setStatus by remember { mutableStateOf(a0.optInt("set_status").takeIf { it != 0 }?.toString() ?: "") }
    var body by remember { mutableStateOf(a0.optString("body").ifEmpty { a0.opt("json")?.let { if (it is JSONObject) it.toString(2) else if (it is JSONArray) it.toString(2) else it.toString() } ?: "" }) }
    var file by remember { mutableStateOf(a0.optString("file")) }
    var contentType by remember { mutableStateOf(a0.optString("content_type")) }
    var url by remember { mutableStateOf(a0.optString("url")) }
    var keepPath by remember { mutableStateOf(a0.optBoolean("keep_path", true)) }
    val headers = remember { mutableStateListOf<KV>().apply { addAll(kvFrom(a0.optJSONObject(if (a0.optString("type") == "rewrite") "set_headers" else "headers"))) } }
    var removeHeaders by remember { mutableStateOf(a0.optJSONArray("remove_headers")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: "") }
    val setQuery = remember { mutableStateListOf<KV>().apply { addAll(kvFrom(a0.optJSONObject("set_query"))) } }
    var removeQuery by remember { mutableStateOf(a0.optJSONArray("remove_query")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: "") }
    var setMethod by remember { mutableStateOf(a0.optString("set_method")) }
    val replaces = remember {
        mutableStateListOf<ReplaceRow>().apply {
            a0.optJSONArray("replace")?.objects()?.forEach { add(ReplaceRow(it.optString("find"), it.optString("with"), it.optBoolean("regex"))) }
        }
    }
    val jsonSet = remember { mutableStateListOf<KV>().apply { addAll(kvFrom(a0.optJSONObject("json_set"), jsonValues = true)) } }
    var jsonDelete by remember { mutableStateOf(a0.optJSONArray("json_delete")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } } ?: "") }
    var clearBody by remember { mutableStateOf(a0.optBoolean("clear_body")) }
    var ms by remember { mutableStateOf(a0.optInt("ms").takeIf { it != 0 }?.toString() ?: "") }
    var kbps by remember { mutableStateOf(a0.optInt("kbps").takeIf { it != 0 }?.toString() ?: "") }
    var tag by remember { mutableStateOf(a0.optString("tag")) }
    var varName by remember { mutableStateOf(a0.optString("var")) }
    var varFrom by remember { mutableStateOf(a0.optString("from")) }
    var timeoutS by remember { mutableStateOf(a0.optInt("timeout_s").takeIf { it != 0 }?.toString() ?: "") }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun csv(s: String) = JSONArray().apply { s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { put(it) } }
    fun build(): JSONObject {
        val a = JSONObject().put("type", type)
        when (type) {
            "block" -> status.toIntOrNull()?.let { a.put("status", it) }
            "mock" -> {
                a.put("status", status.toIntOrNull() ?: 200)
                if (headers.isNotEmpty()) a.put("headers", kvJson(headers))
                if (contentType.isNotBlank()) a.put("content_type", contentType.trim())
                if (file.isNotBlank()) a.put("file", file.trim()) else a.put("body", body)
            }
            "map_local" -> {
                a.put("file", file.trim())
                if (contentType.isNotBlank()) a.put("content_type", contentType.trim())
                if (headers.isNotEmpty()) a.put("headers", kvJson(headers))
            }
            "map_remote" -> a.put("url", url.trim()).put("keep_path", keepPath)
            "rewrite" -> {
                if (headers.isNotEmpty()) a.put("set_headers", kvJson(headers))
                if (removeHeaders.isNotBlank()) a.put("remove_headers", csv(removeHeaders))
                setStatus.toIntOrNull()?.let { a.put("set_status", it) }
                if (setQuery.isNotEmpty()) a.put("set_query", kvJson(setQuery))
                if (removeQuery.isNotBlank()) a.put("remove_query", csv(removeQuery))
                if (setMethod.isNotBlank()) a.put("set_method", setMethod.trim().uppercase())
                if (replaces.isNotEmpty()) a.put("replace", JSONArray().apply {
                    replaces.filter { it.find.isNotEmpty() }.forEach { put(JSONObject().put("find", it.find).put("with", it.with).put("regex", it.regex)) }
                })
                if (jsonSet.isNotEmpty()) a.put("json_set", kvJson(jsonSet, parseValues = true))
                if (jsonDelete.isNotBlank()) a.put("json_delete", csv(jsonDelete))
                if (clearBody) a.put("clear_body", true) else if (body.isNotEmpty()) a.put("body", body)
            }
            "delay" -> { ms.toIntOrNull()?.let { a.put("ms", it) }; kbps.toIntOrNull()?.let { a.put("kbps", it) } }
            "tag" -> a.put("tag", tag.trim())
            "set_var" -> a.put("var", varName.trim()).put("from", varFrom.trim())
            "breakpoint" -> timeoutS.toIntOrNull()?.let { a.put("timeout_s", it) }
        }
        val r = JSONObject().put("name", name.trim()).put("match", match.trim()).put("enabled", enabled).put("action", a)
        if (type !in listOf("passthrough", "ignore")) r.put("phase", phase)
        priority.toIntOrNull()?.let { r.put("priority", it) }
        if (id.isNotEmpty()) r.put("id", id)
        return r
    }

    Column(Modifier.fillMaxSize()) {
        NetTopBar(if (id.isEmpty()) L.netcap.ui.newRule else L.netcap.ui.editRule, subtitle = id.ifEmpty { null }, onBack = host.back) {
            if (id.isNotEmpty()) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, L.common.delete, tint = AppColors.danger) }
            Button(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    netTry {
                        NetcapApi.post("/v1/ui/rules", JSONObject().put("rule", build()))
                        AppToastHelper.showToast(L.netcap.ui.ruleSaved)
                        host.back()
                    }
                    saving = false
                }
            }, modifier = Modifier.padding(end = 6.dp)) { Text(L.common.save) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ruleTypes().forEach { t -> ToggleChip(t.label, type == t.id, t.icon) { type = t.id } }
            }
            ruleTypes().firstOrNull { it.id == type }?.let { Hint(it.hint) }
            OutlinedTextField(name, { name = it }, label = { Text(L.common.name) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                match, { match = it }, label = { Text(L.netcap.ui.matchLabel) }, placeholder = { Text("host:api.example.com path:/v1/*") },
                textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp), modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (type !in listOf("passthrough", "ignore")) {
                    ToggleChip(L.netcap.ui.request, phase == "request") { phase = "request" }
                    Spacer(Modifier.width(6.dp))
                    ToggleChip(L.netcap.ui.response, phase == "response") { phase = "response" }
                }
                Spacer(Modifier.weight(1f))
                Text(L.netcap.ui.enabled, color = AppColors.onBackground, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Switch(enabled, { enabled = it })
            }
            when (type) {
                "block" -> NumField(status, { status = it }, L.netcap.ui.blockStatus)
                "mock" -> {
                    NumField(status, { status = it }, L.netcap.ui.status)
                    OutlinedTextField(contentType, { contentType = it }, label = { Text("Content-Type") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    KVEditor(L.netcap.ui.headers, headers)
                    CodeField(body, { body = it }, L.netcap.ui.body)
                    OutlinedTextField(file, { file = it }, label = { Text(L.netcap.ui.orFile) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                "map_local" -> {
                    OutlinedTextField(file, { file = it }, label = { Text(L.netcap.ui.fileOrFolder) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Hint(L.netcap.ui.mapLocalHint)
                    OutlinedTextField(contentType, { contentType = it }, label = { Text("Content-Type") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    KVEditor(L.netcap.ui.headers, headers)
                }
                "map_remote" -> {
                    OutlinedTextField(url, { url = it }, label = { Text(L.netcap.ui.targetUrl) }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp))
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(keepPath, { keepPath = it }); Spacer(Modifier.width(8.dp)); Text(L.netcap.ui.keepPath, color = AppColors.onBackground) }
                }
                "rewrite" -> {
                    KVEditor(L.netcap.ui.setHeaders, headers)
                    OutlinedTextField(removeHeaders, { removeHeaders = it }, label = { Text(L.netcap.ui.removeHeaders) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (phase == "response") NumField(setStatus, { setStatus = it }, L.netcap.ui.setStatus)
                    if (phase == "request") {
                        OutlinedTextField(setMethod, { setMethod = it }, label = { Text(L.netcap.ui.setMethod) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        KVEditor(L.netcap.ui.setQuery, setQuery)
                        OutlinedTextField(removeQuery, { removeQuery = it }, label = { Text(L.netcap.ui.removeQuery) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    Text(L.netcap.ui.findReplace, color = AppColors.onBackground, fontWeight = FontWeight.Medium)
                    replaces.forEach { rr ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(rr.find, { rr.find = it }, label = { Text(L.netcap.ui.find) }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp))
                                OutlinedTextField(rr.with, { rr.with = it }, label = { Text(L.netcap.ui.replaceWith) }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp))
                                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rr.regex, { rr.regex = it }); Text(L.netcap.ui.regex, color = AppColors.onBackground, fontSize = 13.sp) }
                            }
                            IconButton(onClick = { replaces.remove(rr) }) { Icon(Icons.Filled.Close, L.common.remove, tint = AppColors.textSecondary) }
                        }
                    }
                    TextButton(onClick = { replaces.add(ReplaceRow("", "", false)) }) { Icon(Icons.Filled.Add, null); Text(L.netcap.ui.addReplace) }
                    KVEditor(L.netcap.ui.jsonSet, jsonSet, keyHint = ".data.user.role", valueHint = "\"admin\"")
                    OutlinedTextField(jsonDelete, { jsonDelete = it }, label = { Text(L.netcap.ui.jsonDelete) }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp))
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(clearBody, { clearBody = it }); Text(L.netcap.ui.clearBody, color = AppColors.onBackground, fontSize = 13.sp) }
                    if (!clearBody) CodeField(body, { body = it }, L.netcap.ui.replaceBody)
                }
                "delay" -> {
                    NumField(ms, { ms = it }, L.netcap.ui.delayMs)
                    NumField(kbps, { kbps = it }, L.netcap.ui.kbps)
                }
                "tag" -> OutlinedTextField(tag, { tag = it }, label = { Text(L.netcap.ui.tag) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                "set_var" -> {
                    OutlinedTextField(varName, { varName = it }, label = { Text(L.netcap.ui.variable) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(varFrom, { varFrom = it }, label = { Text(L.netcap.ui.varFrom) }, placeholder = { Text("res.json:.token") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp))
                }
                "breakpoint" -> {
                    NumField(timeoutS, { timeoutS = it }, L.netcap.ui.autoContinue)
                    Hint(L.netcap.ui.autoContinueHint)
                }
            }
            NumField(priority, { priority = it }, L.netcap.ui.priority)
            OutlinedButton(onClick = {
                scope.launch {
                    netTry {
                        val r = NetcapApi.post("/v1/ui/rules/test", JSONObject().put("rule", build()))
                        host.navigate(NetDest.Text(L.netcap.ui.testRule, r.optString("text")))
                    }
                }
            }) { Icon(Icons.Outlined.Science, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(L.netcap.ui.testRule) }
            Spacer(Modifier.height(30.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(L.netcap.ui.deleteRule) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { netTry { NetcapApi.post("/v1/ui/rules/remove", JSONObject().put("id", id)); host.back() } }
                }) { Text(L.common.delete, color = AppColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun NumField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit)) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, minLines = 4, maxLines = 14,
        textStyle = TextStyle(fontFamily = Mono, fontSize = 12.5.sp), modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun KVEditor(title: String, list: MutableList<KV>, keyHint: String = "X-Header", valueHint: String = "value") {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = AppColors.onBackground, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
        list.forEach { kv ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(kv.k, { kv.k = it }, placeholder = { Text(keyHint) }, singleLine = true, modifier = Modifier.weight(0.45f), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.5.sp))
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(kv.v, { kv.v = it }, placeholder = { Text(valueHint) }, singleLine = true, modifier = Modifier.weight(0.55f), textStyle = TextStyle(fontFamily = Mono, fontSize = 12.5.sp))
                IconButton(onClick = { list.remove(kv) }) { Icon(Icons.Filled.Close, L.common.remove, tint = AppColors.textSecondary) }
            }
        }
        TextButton(onClick = { list.add(KV("", "")) }) { Icon(Icons.Filled.Add, null); Text(L.common.add) }
    }
}
