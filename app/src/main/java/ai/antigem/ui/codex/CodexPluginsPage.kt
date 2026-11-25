package ai.antigem.ui.codex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.codex.CodexChatBackend
import ai.antigem.data.agent.opencode.a
import ai.antigem.data.agent.opencode.bool
import ai.antigem.data.agent.opencode.o
import ai.antigem.data.agent.opencode.str
import ai.antigem.theme.QuotaGreen
import ai.antigem.theme.QuotaRed
import ai.antigem.ui.claude.ClaudeCard
import ai.antigem.ui.components.AppToastHelper
import ai.antigem.ui.components.ChatToastType
import ai.antigem.ui.components.CodexAccent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ai.antigem.i18n.L

private fun toast(msg: String, ok: Boolean = true) =
    AppToastHelper.showToast(msg, if (ok) ChatToastType.SUCCESS else ChatToastType.ERROR)

/** One plugin of a marketplace, from Codex' `plugin/list`. */
private data class CxPlugin(
    val id: String,
    val name: String,
    val title: String,
    val description: String,
    val category: String?,
    val version: String?,
    val installed: Boolean,
    val enabled: Boolean,
    val marketplace: String,
    val marketplacePath: String?
)

private data class CxMarketplace(val name: String, val title: String, val path: String?, val plugins: List<CxPlugin>)

private data class CxPluginData(val marketplaces: List<CxMarketplace>, val loadErrors: List<String>) {
    val all: List<CxPlugin> = marketplaces.flatMap { it.plugins }
}

private fun parsePluginList(result: JsonElement): CxPluginData {
    val o = result as? JsonObject ?: return CxPluginData(emptyList(), emptyList())
    val markets = o.a("marketplaces").orEmpty().mapNotNull { m ->
        val mo = m as? JsonObject ?: return@mapNotNull null
        val name = mo.str("name") ?: return@mapNotNull null
        val path = mo.str("path")
        val plugins = mo.a("plugins").orEmpty().mapNotNull { p ->
            val po = p as? JsonObject ?: return@mapNotNull null
            val ui = po.o("interface")
            val pName = po.str("name") ?: return@mapNotNull null
            CxPlugin(
                id = po.str("id") ?: "$pName@$name",
                name = pName,
                title = ui.str("displayName") ?: pName,
                description = ui.str("shortDescription") ?: ui.str("longDescription") ?: "",
                category = ui.str("category"),
                version = po.str("localVersion") ?: po.str("version"),
                installed = po.bool("installed") == true,
                enabled = po.bool("enabled") != false,
                marketplace = name,
                marketplacePath = path
            )
        }
        CxMarketplace(name, mo.o("interface").str("displayName") ?: name, path, plugins)
    }
    val errors = o.a("marketplaceLoadErrors").orEmpty().mapNotNull { (it as? JsonObject)?.str("message") }
    return CxPluginData(markets, errors)
}

/** "method not found" from an older Codex: the plugin commands came with newer versions. */
private fun friendly(e: Throwable): String {
    val msg = e.message ?: L.codex.plugins.codexDidNotAnswer
    return if (msg.contains("method not found", ignoreCase = true) || msg.contains("unknown variant", ignoreCase = true))
        L.codex.plugins.thisCodexIsTooOldTo else msg
}

/**
 * Settings → Codex → Plugins: Codex' own plugin commands over its app-server (`plugin/list`, `plugin/install`,
 * `plugin/uninstall`, `marketplace/add|upgrade|remove`), the same as `codex plugin …`. A marketplace is a git
 * repository with a plugin catalog: Codex reads its own format and Claude Code's.
 */
@Composable
fun CodexPluginsPage(backend: CodexChatBackend) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<CxPluginData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var marketSource by remember { mutableStateOf("") }
    var addingMarket by remember { mutableStateOf(false) }
    val ops = remember { mutableStateMapOf<String, String>() } // plugin id / marketplace name → running action
    // Marketplaces tab: the marketplace whose plugins are shown under it, and the filter of that list
    var expandedMarket by remember { mutableStateOf<String?>(null) }
    var marketQuery by remember { mutableStateOf("") }
    var scrollToMarket by remember { mutableStateOf<String?>(null) }
    // a repository Codex couldn't add as a marketplace: (source, why) for the "Set up with AI" dialog
    var aiSetup by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var lastFailed by remember { mutableStateOf<Pair<String, String?>?>(null) }

    suspend fun reload() {
        loading = true
        backend.client.rpc("plugin/list", buildJsonObject { }, timeoutMs = 90_000)
            .onSuccess { data = parsePluginList(it); error = null }
            .onFailure { error = friendly(it) }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    fun run(key: String, action: String, method: String, params: JsonObject, done: (JsonElement) -> String) {
        ops[key] = action
        scope.launch {
            backend.client.rpc(method, params, timeoutMs = 300_000)
                .onSuccess {
                    toast(done(it))
                    reload()
                    // a plugin brings skills: the "/" menu reads them again
                    backend.refreshSkills()
                }
                .onFailure { toast(friendly(it), ok = false) }
            ops.remove(key)
        }
    }

    fun install(p: CxPlugin) = run(p.id, "install", "plugin/install", buildJsonObject {
        put("pluginName", p.name)
        if (p.marketplacePath != null) put("marketplacePath", p.marketplacePath) else put("remoteMarketplaceName", p.marketplace)
    }) { res ->
        val apps = (res as? JsonObject)?.a("appsNeedingAuth").orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }
        if (apps.isNotEmpty()) L.codex.plugins.titleInstalledStartANewChat2(p.title, apps.joinToString()) else L.codex.plugins.titleInstalledStartANewChat(p.title)
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            listOf(L.common.installed, L.codex.plugins.discover, L.codex.plugins.marketplaces).forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
            }
        }
        val d = data
        if (d == null) {
            Column(Modifier.padding(16.dp)) {
                if (error != null) {
                    Text(error!!, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { scope.launch { reload() } }, enabled = !loading) { Text(L.common.tryAgain) }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CodexAccent)
                    Spacer(Modifier.width(8.dp))
                    Text(L.codex.plugins.loadingPlugins, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            return@Column
        }
        val installed = d.all.filter { it.installed }
        val available = d.all.filter { !it.installed }
        val q = query.trim().lowercase()
        val results = if (q.isEmpty()) available else available.filter { "${it.title}\n${it.name}\n${it.description}\n${it.category.orEmpty()}\n${it.marketplace}".lowercase().contains(q) }
        val listState = rememberLazyListState()
        LaunchedEffect(scrollToMarket, d) {
            val name = scrollToMarket ?: return@LaunchedEffect
            val i = d.marketplaces.indexOfFirst { it.name == name }
            // before the marketplaces: the error lines, "about" and "add"; only the opened one has rows under it
            if (tab == 2 && i >= 0) listState.animateScrollToItem((if (error != null) 1 else 0) + d.loadErrors.size + 2 + i)
            scrollToMarket = null
        }
        LazyColumn(
            Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (error != null) item(key = "error") { Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            d.loadErrors.forEachIndexed { i, e -> item(key = "le$i") { Text(e, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error) } }
            when (tab) {
                0 -> {
                    if (installed.isEmpty()) item(key = "none") { Hint(L.codex.plugins.noPluginsInstalledFindSomeIn) }
                    items(installed.size, key = { "i_${installed[it].id}_$it" }) { i ->
                        val p = installed[i]
                        PluginCard(p, ops[p.id], onToggle = { on ->
                            // off: Codex keeps it installed but loads it in no chat (`[plugins."<id>"] enabled = false`)
                            ops[p.id] = "toggle"
                            scope.launch {
                                backend.setPluginEnabled(p.id, on)
                                    .onSuccess {
                                        toast(if (on) L.codex.plugins.titleTurnedOnStartANew(p.title) else L.codex.plugins.titleTurnedOffNewChatsWont(p.title))
                                        reload()
                                        backend.refreshSkills()
                                    }
                                    .onFailure { toast(friendly(it), ok = false) }
                                ops.remove(p.id)
                            }
                        }) {
                            OutlinedButton(onClick = {
                                run(p.id, "uninstall", "plugin/uninstall", buildJsonObject { put("pluginId", p.id) }) { "${p.title} uninstalled" }
                            }, enabled = ops[p.id] == null) { Text(L.common.uninstall, fontSize = 12.sp, color = QuotaRed) }
                        }
                    }
                }
                1 -> {
                    item(key = "search") {
                        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(L.codex.plugins.searchAvailablecountPlugins(available.size)) }, modifier = Modifier.fillMaxWidth())
                    }
                    item(key = "count") { Hint("${results.size} plugin(s)") }
                    items(results.size, key = { "a_" + results[it].id }) { i ->
                        val p = results[i]
                        PluginCard(p, ops[p.id]) {
                            Button(onClick = { install(p) }, enabled = ops[p.id] == null, colors = ButtonDefaults.buttonColors(containerColor = CodexAccent)) {
                                Text(L.common.install, fontSize = 12.sp)
                            }
                        }
                    }
                }
                else -> {
                    item(key = "about") {
                        Hint(L.codex.plugins.aMarketplaceIsAGitRepository)
                    }
                    item(key = "add") {
                        ClaudeCard {
                            Text(L.codex.plugins.addAMarketplace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Hint(L.codex.plugins.githubOwnerRepoOwnerRepoBranch)
                            lastFailed?.let { failed ->
                                ai.antigem.ui.settings.SetupWithAiLink(CodexAccent) { aiSetup = failed }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(marketSource, { marketSource = it.trim() }, singleLine = true, placeholder = { Text("owner/repo") }, modifier = Modifier.weight(1f), enabled = !addingMarket)
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    addingMarket = true
                                    scope.launch {
                                        backend.client.rpc("marketplace/add", buildJsonObject { put("source", marketSource) }, timeoutMs = 300_000)
                                            .onSuccess { res ->
                                                val o = res as? JsonObject
                                                val name = o.str("marketplaceName") ?: marketSource
                                                toast(if (o.bool("alreadyAdded") == true) L.codex.plugins.nameWasAlreadyAdded(name) else L.codex.plugins.addedNamePickItsPluginsBelow(name))
                                                marketSource = ""
                                                reload()
                                                // open it with its plugins, so they can be installed right here
                                                expandedMarket = name
                                                marketQuery = ""
                                                scrollToMarket = name
                                                lastFailed = null
                                            }
                                            .onFailure {
                                                toast(friendly(it), ok = false)
                                                aiSetup = marketSource to friendly(it)
                                                lastFailed = aiSetup
                                            }
                                        addingMarket = false
                                    }
                                }, enabled = marketSource.isNotBlank() && !addingMarket, colors = ButtonDefaults.buttonColors(containerColor = CodexAccent)) {
                                    if (addingMarket) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Text(L.common.add)
                                }
                            }
                        }
                    }
                    d.marketplaces.forEach { m ->
                        val open = expandedMarket == m.name
                        item(key = "m_" + m.name) {
                            val op = ops["m:" + m.name]
                            // Codex' own catalog (cached under .tmp/plugins) can't be removed
                            val builtIn = m.path?.contains("/.tmp/plugins/") == true
                            ClaudeCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        Modifier.weight(1f).clickable { expandedMarket = if (open) null else m.name; marketQuery = "" },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(m.title, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                                            Hint("${m.plugins.size} plugin(s)" + (if (builtIn) L.codex.plugins.builtIntoCodex else "") + (if (m.title != m.name) " · ${m.name}" else ""))
                                        }
                                        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) L.codex.plugins.hidePlugins else L.codex.plugins.showPlugins)
                                    }
                                    if (op != null) Progress(op)
                                    else {
                                        IconButton(onClick = {
                                            run("m:" + m.name, "update", "marketplace/upgrade", buildJsonObject { put("marketplaceName", m.name) }) { res ->
                                                val errs = (res as? JsonObject)?.a("errors").orEmpty().mapNotNull { (it as? JsonObject)?.str("message") }
                                                if (errs.isEmpty()) L.codex.plugins.titleIsUpToDate(m.title) else "${m.title}: ${errs.first()}"
                                            }
                                        }) { Icon(Icons.Outlined.Refresh, L.codex.plugins.updateCatalog) }
                                        if (!builtIn) IconButton(onClick = {
                                            run("m:" + m.name, "remove", "marketplace/remove", buildJsonObject { put("marketplaceName", m.name) }) { "${m.title} removed" }
                                        }) { Icon(Icons.Outlined.Delete, L.common.remove, tint = QuotaRed.copy(alpha = 0.8f)) }
                                    }
                                }
                            }
                        }
                        if (open) {
                            // its plugins, to install right here (the same as from Discover)
                            val plugins = m.plugins.sortedBy { it.title.lowercase() }
                            if (plugins.size > 8) item(key = "mq_" + m.name) {
                                OutlinedTextField(marketQuery, { marketQuery = it }, singleLine = true, placeholder = { Text(L.codex.plugins.searchPluginscountPluginsInTitle(plugins.size, m.title)) },
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp))
                            }
                            val mq = marketQuery.trim().lowercase()
                            val shown = if (mq.isEmpty()) plugins else plugins.filter { "${it.title}\n${it.name}\n${it.description}\n${it.category.orEmpty()}".lowercase().contains(mq) }
                            if (plugins.isEmpty()) item(key = "me_" + m.name) {
                                Column(Modifier.padding(start = 12.dp)) { Hint(L.codex.plugins.noPluginsInThisMarketplaceYet) }
                            }
                            items(shown.size, key = { "mp_" + m.name + "_" + shown[it].id }) { j ->
                                val p = shown[j]
                                Column(Modifier.padding(start = 12.dp)) {
                                    PluginCard(p, ops[p.id]) {
                                        if (!p.installed) Button(onClick = { install(p) }, enabled = ops[p.id] == null, colors = ButtonDefaults.buttonColors(containerColor = CodexAccent)) {
                                            Text(L.common.install, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "foot") { Hint(L.codex.plugins.pluginsAreLoadedWhenAChat) }
        }
    }
    aiSetup?.let { (src, why) ->
        ai.antigem.ui.settings.SetupWithAiDialog(src, ai.antigem.domain.model.AgentKind.CODEX, why) { aiSetup = null }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
}

@Composable
private fun Progress(action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CodexAccent)
        Spacer(Modifier.width(6.dp))
        Text(
            when (action) {
                "install" -> L.common.installing
                "uninstall", "remove" -> L.common.removing
                "update" -> L.common.updating
                else -> L.common.working
            },
            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = CodexAccent
        )
    }
}

@Composable
private fun PluginCard(p: CxPlugin, op: String?, onToggle: ((Boolean) -> Unit)? = null, action: @Composable () -> Unit) {
    ClaudeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                Hint(listOfNotNull(p.version?.let { "v$it" }, p.category, p.marketplace, if (p.installed && !p.enabled) L.common.disabled else null).joinToString(" · "))
            }
            if (op != null) Progress(op)
            else if (p.installed && onToggle != null) Switch(
                checked = p.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = CodexAccent)
            )
            else if (p.installed) Text(L.common.installed, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = QuotaGreen)
        }
        if (p.description.isNotBlank()) Text(p.description, fontSize = 11.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        if (op == null) Row(Modifier.padding(top = 6.dp)) { action() }
    }
}
