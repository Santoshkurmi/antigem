package ai.antigem.ui.netcap

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import ai.antigem.data.netcap.NetStore
import ai.antigem.data.netcap.NetcapManager
import ai.antigem.data.netcap.NetcapVpnService
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where the Network page is (its own back stack; Back pops it, then leaves the page). */
internal sealed interface NetDest {
    data object Home : NetDest
    data class Detail(val id: Long) : NetDest
    /** Edit and send: from a stored flow ([fromId]) or empty. */
    data class Composer(val fromId: Long?) : NetDest
    /** A rule: [rule] is the engine's JSON (new rules carry no id). */
    data class RuleEdit(val rule: JSONObject) : NetDest
    data class PausedEdit(val id: String) : NetDest
    data object AppPicker : NetDest
    data class ScriptLogs(val name: String) : NetDest
    data class Text(val title: String, val text: String) : NetDest
    data object Decoder : NetDest
    data class Compare(val a: Long?, val b: Long?) : NetDest
    data object Sessions : NetDest
    data object Findings : NetDest
    data object Dns : NetDest
}

/** The capture button sits this far above the bottom; lists leave [LIST_BOTTOM] free so their last rows scroll above it. */
internal val FAB_BOTTOM = 100.dp
internal val LIST_BOTTOM = 176.dp

internal enum class NetTab { TRAFFIC, PAUSED, RULES, SCRIPTS, TOOLS }

/** Things the page needs from the rest of the app. */
internal class NetHost(
    val navigate: (NetDest) -> Unit,
    val back: () -> Unit,
    /** Opens a file in the IDE page (big bodies, scripts). */
    val openInIde: (path: String) -> Unit,
    /** Puts [text] into the chat's message box and shows the chat. */
    val askAi: (text: String) -> Unit,
    val openSettings: () -> Unit
)

internal val LocalNetHost = staticCompositionLocalOf<NetHost> { error("no NetHost") }

/**
 * The Network Interceptor page, one of the app's pages like Chat, IDE and Files: pick apps, start a capture, watch the
 * traffic live, open a request, edit and send it again, pause flows at breakpoints, write rules and scripts. Agents use
 * the same engine through network_mcp, so both see the same capture, rules and paused flows.
 */
@Composable
fun NetworkScreen(
    isVisible: Boolean,
    onClose: () -> Unit,
    onOpenInIde: (path: String) -> Unit,
    onAskAi: (text: String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val stack = remember { mutableStateListOf<NetDest>() }
    var tab by rememberSaveable { mutableStateOf(NetTab.TRAFFIC) }
    val engine by NetcapManager.state.collectAsState()

    // the live stream runs while the page is in front
    DisposableEffect(isVisible) {
        if (isVisible) NetStore.watch()
        onDispose { if (isVisible) NetStore.unwatch() }
    }
    // the engine starts by itself when the page opens and it is already on the phone (a download waits for the button)
    LaunchedEffect(isVisible) {
        if (isVisible) {
            NetcapManager.init(context)
            if (engine.engine == NetcapManager.Engine.STOPPED && NetcapManager.engineInstalled(context)) {
                NetcapManager.ensureStarted(context)
            }
        }
    }
    NetFloating.Attach()

    val host = remember {
        NetHost(
            navigate = { d -> stack.add(d) },
            back = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else onClose() },
            openInIde = onOpenInIde,
            askAi = onAskAi,
            openSettings = onOpenSettings
        )
    }
    BackHandler(enabled = isVisible) { host.back() }
    // the bubble asked for a place (a request, a paused item, the list)
    val pendingDest by NetFloating.pending.collectAsState()
    LaunchedEffect(pendingDest) {
        val d = pendingDest ?: return@LaunchedEffect
        if (d is NetDest.PausedEdit) tab = NetTab.PAUSED
        stack.clear()
        if (d != NetDest.Home) stack.add(d)
        NetFloating.consumePending()
    }

    CompositionLocalProvider(LocalNetHost provides host) {
        Box(Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            val dest = stack.lastOrNull() ?: NetDest.Home
            val covered = stack.isNotEmpty()
            // the list stays built underneath (going back is instant, its scroll and filters stay); hidden from
            // TalkBack while another screen covers it
            Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics { } else Modifier)) {
                NetHome(tab = tab, onTab = { tab = it }, engineState = engine)
            }
            // deeper screens slide in over it from the right (nothing underneath moves or fades: cheap to draw) and
            // slide out again on Back
            AnimatedContent(
                targetState = stack.size to dest,
                transitionSpec = {
                    val forward = targetState.first > initialState.first
                    val spec = tween<IntOffset>(220, easing = FastOutSlowInEasing)
                    if (forward) {
                        slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { 0 }
                    } else {
                        EnterTransition.None togetherWith slideOutHorizontally(spec) { it }
                    }.apply { targetContentZIndex = if (forward) 1f else -1f }
                },
                label = "net"
            ) { (_, d) ->
                if (d == NetDest.Home) return@AnimatedContent
                // heavy parts (bodies, lists) wait until the slide has ended
                val settled = transition.currentState == EnterExitState.Visible
                CompositionLocalProvider(LocalNetSettled provides settled) {
                    Box(
                        Modifier.fillMaxSize().background(AppColors.background)
                            // touches stay on this screen (the list is underneath)
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    ) {
                        when (d) {
                            NetDest.Home -> Unit
                            is NetDest.Detail -> FlowDetailScreen(d.id)
                            is NetDest.Composer -> ComposerScreen(d.fromId)
                            is NetDest.RuleEdit -> RuleEditScreen(d.rule)
                            is NetDest.PausedEdit -> PausedEditScreen(d.id)
                            NetDest.AppPicker -> AppPickerScreen()
                            is NetDest.ScriptLogs -> ScriptLogsScreen(d.name)
                            is NetDest.Text -> TextScreen(d.title, d.text)
                            NetDest.Decoder -> DecoderScreen()
                            is NetDest.Compare -> CompareScreen(d.a, d.b)
                            NetDest.Sessions -> SessionsScreen()
                            NetDest.Findings -> FindingsScreen()
                            NetDest.Dns -> DnsScreen()
                        }
                    }
                }
            }
        }
    }
}

/** False while a screen is still sliding in: it shows its frame first and fills in the heavy parts after. */
internal val LocalNetSettled = compositionLocalOf { true }

@Composable
private fun NetHome(tab: NetTab, onTab: (NetTab) -> Unit, engineState: NetcapManager.State) {
    val host = LocalNetHost.current
    val st by NetStore.state.collectAsState()
    val paused by NetStore.paused.collectAsState()
    Column(Modifier.fillMaxSize()) {
        NetTopBar(
            title = L.netcap.ui.title,
            subtitle = if (st.sessionId.isNotEmpty()) L.netcap.ui.sessionLine(st.sessionName.ifEmpty { st.sessionId }, st.sessionFlows) else null,
            onBack = host.back
        ) {
            if (engineState.engine == NetcapManager.Engine.RUNNING) AppsButton()
            HomeMenu()
        }
        if (engineState.engine != NetcapManager.Engine.RUNNING) {
            EngineCard(engineState)
            return@Column
        }
        TabsRow(tab, onTab, paused.size, st.rulesOn, st.scriptsOn)
        Box(Modifier.weight(1f)) {
            when (tab) {
                NetTab.TRAFFIC -> TrafficTab()
                NetTab.PAUSED -> PausedTab()
                NetTab.RULES -> RulesTab()
                NetTab.SCRIPTS -> ScriptsTab()
                NetTab.TOOLS -> ToolsTab()
            }
            // the capture's Start / Stop (and Pause): fixed at the bottom, only on these main tabs
            CaptureFab(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = FAB_BOTTOM))
        }
    }
}

/** The apps to capture, as a small labeled button in the top bar ("2 apps"); opens the picker. */
@Composable
private fun AppsButton() {
    val context = LocalContext.current
    val host = LocalNetHost.current
    val st by NetStore.state.collectAsState()
    val mgr by NetcapManager.state.collectAsState()
    val on = mgr.capturing
    val apps = if (on) st.capture.apps.ifEmpty { mgr.apps } else NetcapManager.Prefs.lastApps(context)
    Row(
        Modifier.padding(end = 2.dp).clip(RoundedCornerShape(50)).border(1.dp, AppColors.border, RoundedCornerShape(50))
            .clickable { host.navigate(NetDest.AppPicker) }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Apps, null, tint = AppColors.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            when (apps.size) { 0 -> L.netcap.ui.chooseAppsShort; 1 -> L.netcap.ui.oneApp; else -> L.netcap.ui.appsCount(apps.size) },
            color = AppColors.onBackground, fontSize = 13.sp, maxLines = 1
        )
    }
}

/**
 * Start / Stop the capture: a round floating button (icon only), fixed a little above the bottom right, only on the
 * page's main tabs. Start without apps opens the picker. (Pausing stays in the notification and the bubble.)
 */
@Composable
private fun CaptureFab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    val st by NetStore.state.collectAsState()
    val mgr by NetcapManager.state.collectAsState()
    val on = mgr.capturing
    var busy by remember { mutableStateOf(false) }
    FloatingActionButton(
        onClick = {
            if (busy) return@FloatingActionButton
            if (on) {
                NetcapVpnService.stop(context)
            } else {
                val apps = NetcapManager.Prefs.lastApps(context)
                if (apps.isEmpty()) host.navigate(NetDest.AppPicker) else {
                    busy = true
                    scope.launch {
                        val r = NetcapVpnService.startCapture(context, apps)
                        busy = false
                        r.exceptionOrNull()?.let { ai.antigem.ui.components.AppToastHelper.showToast(it.message ?: it.toString()) }
                    }
                }
            }
        },
        shape = CircleShape,
        containerColor = if (on) AppColors.danger else AppColors.success,
        contentColor = Color.White,
        modifier = modifier
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
        else Icon(if (on) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (on) L.netcap.screen.stopCapture else L.netcap.screen.startCapture, Modifier.size(28.dp))
    }
}

@Composable
private fun HomeMenu() {
    val host = LocalNetHost.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, L.common.more, tint = AppColors.onBackground) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(L.netcap.ui.sessions) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                onClick = { open = false; host.navigate(NetDest.Sessions) })
            DropdownMenuItem(text = { Text(L.netcap.ui.newSession) }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                onClick = {
                    open = false
                    scope.launch { netTry { ai.antigem.data.netcap.NetcapApi.post("/v1/ui/sessions", JSONObject().put("action", "new")) }; NetStore.reload() }
                })
            DropdownMenuItem(text = { Text(L.netcap.ui.clearList) }, leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                onClick = { open = false; confirmClear = true })
            DropdownMenuItem(text = { Text(L.netcap.ui.floatingBubble) }, leadingIcon = { Icon(Icons.Outlined.BubbleChart, null) },
                trailingIcon = { Checkbox(checked = NetFloating.enabledState(), onCheckedChange = null) },
                onClick = { NetFloating.toggleEnabled(); open = false })
            DropdownMenuItem(text = { Text(L.netcap.ui.certAndSettings) }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { open = false; host.openSettings() })
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(L.netcap.ui.clearList) },
            text = { Text(L.netcap.ui.clearListHint) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; scope.launch { netTry { NetStore.clearAll() } } }) { Text(L.common.clear) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L.common.cancel) } }
        )
    }
}

/** Engine not running yet: download, start, or the error. */
@Composable
private fun EngineCard(st: NetcapManager.State) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        NetCard {
            when (st.engine) {
                NetcapManager.Engine.DOWNLOADING -> {
                    Text(L.netcap.screen.downloading(fmtSize(st.downloaded), fmtSize(st.downloadTotal)), color = AppColors.onBackground)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (st.downloadTotal > 0) st.downloaded.toFloat() / st.downloadTotal else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                NetcapManager.Engine.STARTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(L.netcap.screen.starting, color = AppColors.onBackground)
                }
                else -> {
                    val installed = remember(st.engine) { NetcapManager.engineInstalled(context) }
                    val msg = when {
                        st.engine == NetcapManager.Engine.ERROR -> L.netcap.screen.engineError(st.error.orEmpty())
                        st.engine == NetcapManager.Engine.DOWNLOAD_FAILED -> L.netcap.screen.downloadFailed(st.error.orEmpty())
                        !installed -> L.netcap.screen.downloadHint(fmtSize(NetcapManager.downloadSize))
                        else -> L.netcap.screen.notCapturing
                    }
                    Text(msg, color = AppColors.onBackground, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { scope.launch { NetcapManager.ensureStarted(context) } }) {
                        Text(if (installed) L.netcap.ui.startEngine else L.netcap.screen.downloadButton(fmtSize(NetcapManager.downloadSize)))
                    }
                }
            }
        }
    }
}

@Composable
private fun TabsRow(tab: NetTab, onTab: (NetTab) -> Unit, paused: Int, rules: Int, scripts: Int) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NetTab.entries.forEach { t ->
            val label = when (t) {
                NetTab.TRAFFIC -> L.netcap.ui.tabTraffic
                NetTab.PAUSED -> if (paused > 0) L.netcap.ui.tabPausedCount(paused) else L.netcap.ui.tabPaused
                NetTab.RULES -> if (rules > 0) L.netcap.ui.tabRulesCount(rules) else L.netcap.ui.tabRules
                NetTab.SCRIPTS -> if (scripts > 0) L.netcap.ui.tabScriptsCount(scripts) else L.netcap.ui.tabScripts
                NetTab.TOOLS -> L.netcap.ui.tabTools
            }
            val icon = when (t) {
                NetTab.TRAFFIC -> Icons.Outlined.SwapVert
                NetTab.PAUSED -> Icons.Outlined.PauseCircle
                NetTab.RULES -> Icons.Outlined.Rule
                NetTab.SCRIPTS -> Icons.Outlined.Code
                NetTab.TOOLS -> Icons.Outlined.Handyman
            }
            ToggleChip(
                label, tab == t, icon,
                color = if (t == NetTab.PAUSED && paused > 0) AppColors.warning else AppColors.primary
            ) { onTab(t) }
        }
    }
}

/** A text result (tool output) with copy. */
@Composable
internal fun TextScreen(title: String, text: String) {
    val host = LocalNetHost.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        NetTopBar(title, onBack = host.back) {
            IconButton(onClick = { copyText(context, title, text) }) { Icon(Icons.Outlined.ContentCopy, L.common.copy, tint = AppColors.onBackground) }
        }
        MonoTextBlock(text, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/** Monospace text in a scrollable code box (both directions). */
@Composable
internal fun MonoTextBlock(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.selection.SelectionContainer(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.codeBg)
    ) {
        Text(
            text, color = AppColors.codeText, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 17.sp,
            modifier = Modifier.verticalScrollCompat().horizontalScroll(rememberScrollState()).padding(10.dp)
        )
    }
}

@Composable
internal fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

/** "+ New …" as the first row of a list (the bottom corner belongs to the capture button). */
@Composable
internal fun NewRowButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}
