package ai.antigem.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.rea.ReaManager
import ai.antigem.data.rea.ReaManager.Component
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.mergedIntoRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/**
 * Settings → REA: sets up REA (reverse engineering for agents) in a proot-distro Ubuntu container, part by part,
 * and its tool groups. REA works with any agent: it is connected in each agent's own MCP settings with the REA
 * preset ([ReaServerDialog]); this page only says where and shows the plain command for other agents.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaScreen(
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenGroups: () -> Unit,
    onOpenAdbConnect: () -> Unit,
    onOpenPlugins: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ReaManager.init(context)
        AdbConnectManager.init(context)
        AdbConnectManager.refresh(quiet = true)
        ReaManager.refresh()
        ReaManager.checkForUpdate()
        // The terminal and other screens change things while the user is away; keep the page live
        while (true) {
            delay(2000)
            if (!ReaManager.state.value.busy) ReaManager.refresh()
        }
    }
    val state by ReaManager.state.collectAsState()
    val adb by AdbConnectManager.state.collectAsState()
    val busy = state.busy

    // Installs take minutes: keep the screen on while one runs (the manager also holds a wake lock)
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    var confirm by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ReaIntroCard(state, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(shape = RoundedCornerShape(12.dp), color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = ReaManager::clearMessage) { Icon(Icons.Default.Close, L.common.dismiss, Modifier.size(18.dp)) }
                }
            }
        }

        if (!state.ready) {
            Hint(L.common.checking)
            return@Column
        }
        if (!state.bootstrapInstalled) {
            InfoCard(L.settings.rea.installTheTerminalFirst, cardBg, cardBorder) {
                Hint(L.settings.rea.reaInstallsIntoAntigemsTerminalInstall)
            }
            return@Column
        }

        // Step 1: setup files, downloaded on demand (nothing of REA ships in the app)
        StepCard(
            1, L.settings.rea.setupFiles,
            when {
                state.filesUpdate != null -> L.common.update
                state.filesVersion != null -> "v${state.filesVersion}"
                else -> null
            },
            cardBg, cardBorder,
            badgeColor = if (state.filesUpdate != null) Amber else QuotaGreen
        ) {
            Hint(L.settings.rea.reasSetupScriptsReaSetupSh)
            SourceRow(state.source, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.filesVersion == null -> Button(enabled = !busy, onClick = ReaManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.rea.getSetupFiles)
                    }
                    state.filesUpdate != null -> Button(enabled = !busy, onClick = ReaManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.rea.updateToVFilesupdate(state.filesUpdate))
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = ReaManager::downloadSetupFiles) { Text(L.settings.rea.downloadAgain) }
                }
                if (state.filesVersion != null && state.filesUpdate == null) {
                    TextButton(enabled = !busy, onClick = ReaManager::checkForUpdate) { Text(L.common.checkForUpdate) }
                }
            }
            ReaTaskOutput(state, "files")
        }

        // Step 2: the parts, installed one by one or several at once
        InstallStep(state, cardBg, cardBorder)

        // Step 3: which tools agents see
        StepCard(
            3, L.settings.rea.toolGroups,
            if (state.groups.isNotEmpty()) "${state.groups.size} groups" else null,
            cardBg, cardBorder
        ) {
            Hint(L.settings.rea.reaHasOver200ToolsTool)
            if (state.groups.isNotEmpty()) {
                val grouped = state.allToolNames.size - state.ungrouped.size
                Mono(
                    L.settings.rea.groupscountGroupsGroupedOf(state.groups.size, grouped, state.allToolNames.size, (if (state.ungrouped.isNotEmpty()) L.settings.rea.ungroupedcountToolsInNoGroup(state.ungrouped.size) else ""), (state.toolsServer?.let { L.settings.rea.toolListFromValue(it) } ?: L.settings.rea.toolListInstallReaToRead))
                )
            } else {
                Hint(L.settings.rea.theDefaultGroupsComeWithThe)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = state.groups.isNotEmpty() || state.filesReady, onClick = onOpenGroups) {
                    Icon(Icons.Outlined.Category, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.rea.editToolGroups)
                }
                OutlinedButton(enabled = !busy && state.usable, onClick = ReaManager::refreshTools) { Text(L.settings.rea.refreshToolList) }
            }
            ReaTaskOutput(state, "tools")
        }

        // Step 4: connecting it to an agent happens in that agent's MCP settings, the same way for every agent
        StepCard(4, L.settings.rea.connectReaToYourAgent, null, cardBg, cardBorder) {
            ConnectGuide(state)
        }

        // Step 5: health check
        StepCard(5, L.settings.rea.checkItWorks, null, cardBg, cardBorder) {
            Hint(L.settings.rea.runsReaDoctorInUbuntuIt)
            OutlinedButton(enabled = !busy && state.usable, onClick = ReaManager::runDoctor) {
                Icon(Icons.Outlined.HealthAndSafety, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.rea.runHealthCheck)
            }
            ReaTaskOutput(state, "doctor")
            Text(L.settings.rea.tryInChat, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            listOf(
                L.settings.rea.useReaToFindTheApk,
                L.settings.rea.withReaIsComExampleApp,
                L.settings.rea.decompileTheMainactivityOfComExample
            ).forEach { CopyLine(it) }
        }

        SkillPluginCard(state, busy, cardBg, cardBorder, onOpenPlugins)

        // Optional: adb to this phone, for REA's device tools
        InfoCard(L.settings.rea.phoneAccessOptional, cardBg, cardBorder, pill = if (adb.connectedDevice != null) L.settings.rea.connected to QuotaGreen else L.common.optional to MaterialTheme.colorScheme.primary) {
            Hint(L.settings.rea.reasPhoneToolsInstalledAppsPulling)
            val device = adb.connectedDevice
            if (device != null) Mono("adb connected: ${device.serial}") else OutlinedButton(onClick = onOpenAdbConnect) { Text(L.common.setUpAdbConnect) }
        }

        WebsitesCard(state, cardBg, cardBorder)

        ManualSetupCard(state, cardBg, cardBorder)

        RemoveCard(state, busy, cardBg, cardBorder) { confirm = it }
    }

    confirm?.let { what ->
        val (title, text, action) = when (what) {
            "uninstall" -> Triple(
                L.settings.rea.removeRea,
                L.settings.rea.removesReaTheJadxAndApktool((if (state.chroot) L.settings.rea.javaNodeJsAndYourTool else L.settings.rea.theUbuntuContainerJavaNodeJs)),
                { ReaManager.uninstallRea() }
            )
            else -> Triple(
                L.settings.rea.deleteTheUbuntuContainer,
                L.settings.rea.deletesTheWholeContainerProotDistro(ReaManager.CONTAINER),
                { ReaManager.removeContainer() }
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { action(); confirm = null }) { Text(if (what == "uninstall") L.common.remove else L.common.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(L.common.cancel) } }
        )
    }
}

@Composable
private fun ReaIntroCard(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BugReport, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(L.settings.rea.reverseEngineeringWithRea, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when {
                    state.usable && state.requiredDone -> Pill(L.common.ready, QuotaGreen)
                    state.installed.isNotEmpty() -> Pill(L.common.partlySetUp, Amber)
                    else -> Pill(L.settings.rea.notSetUp, MaterialTheme.colorScheme.primary)
                }
            }
            Hint(if (state.chroot) L.settings.rea.letAgentsTakeApartAndroidApps else L.settings.rea.letAgentsTakeApartAndroidApps2)
            Hint(L.settings.rea.installOnlyThePartsYouNeed)
            TextButton(onClick = { openReaUrl(context, ReaManager.REA_HOMEPAGE) }, contentPadding = PaddingValues(0.dp)) {
                Text(L.settings.rea.aboutReaGithubComMorlutoRea, fontSize = 12.5.sp)
            }
        }
    }
}

@Composable
private fun SourceRow(source: String, enabled: Boolean) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(source) { mutableStateOf(source) }
    var error by remember { mutableStateOf<String?>(null) }
    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L.settings.rea.fromSource(source), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
            TextButton(enabled = enabled, onClick = { editing = true }) { Text(L.common.change, fontSize = 12.sp) }
        }
        return
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it; error = null }, singleLine = true,
        label = { Text("owner/repo@branch") }, isError = error != null,
        supportingText = { Text(error ?: L.settings.rea.aForkOfTheAntigemPlugins) },
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            runCatching { ReaManager.setSource(text) }
                .onSuccess { editing = false; ReaManager.checkForUpdate() }
                .onFailure { error = it.message }
        }) { Text(L.common.save) }
        TextButton(onClick = { text = ReaManager.DEFAULT_SOURCE }) { Text(L.common.default) }
        TextButton(onClick = { editing = false; text = source }) { Text(L.common.cancel) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstallStep(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val busy = state.busy
    val installedCount = state.installed.size
    val total = Component.entries.size
    // What the user ticked; starts with what APK analysis needs plus the native tools
    val selected = remember { mutableStateListOf<Component>() }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(state.ready, state.installed) {
        if (!initialized && state.ready) {
            selected.clear()
            selected.addAll(Component.entries.filter { (it.required || it == Component.NATIVE) && it !in state.installed })
            initialized = true
        } else {
            selected.removeAll { it in state.installed }
        }
    }
    val plan = ReaManager.plan(selected.toList())

    StepCard(
        2, L.common.install,
        when {
            state.requiredDone -> "$installedCount of $total"
            installedCount > 0 -> "$installedCount of $total"
            else -> null
        },
        cardBg, cardBorder,
        badgeColor = if (state.requiredDone) QuotaGreen else Amber
    ) {
        Hint(L.settings.rea.pickThePartsYouNeedAnd)
        val facts = listOfNotNull(
            state.containerName?.let { if (state.chroot) L.settings.rea.ubuntuChrootValue(it) else L.settings.rea.containerValue(it) },
            state.nodeVersion?.let { "Node.js $it" },
            state.reaVersion?.let { "REA $it" }
        )
        if (facts.isNotEmpty()) Mono(facts.joinToString("\n"))

        Text(L.settings.rea.neededForApkAnalysis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Component.entries.filter { it.required }.forEach { c -> ComponentRow(c, state, c in selected, busy) { on -> if (on) selected.add(c) else selected.remove(c) } }
        Text(L.common.optional, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        Component.entries.filter { !it.required }.forEach { c -> ComponentRow(c, state, c in selected, busy) { on -> if (on) selected.add(c) else selected.remove(c) } }

        if (isInstallTask(state.task) || isInstallTask(state.logTask)) BatchProgress(state)
        if (!busy) {
            if (plan.isNotEmpty()) {
                val extra = plan.filter { it !in selected }
                Hint(
                    if (extra.isNotEmpty()) L.settings.rea.installsCountPartsPartsExtraAdded(plan.size, plan.joinToString(", ") { it.label }, extra.joinToString(", ") { it.label })
                    else L.settings.rea.installsCountPartsParts(plan.size, plan.joinToString(", ") { it.label })
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = plan.isNotEmpty(), onClick = { ReaManager.installAll(plan) }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (plan.isEmpty()) L.settings.rea.nothingToInstall else "Install selected (${plan.size})")
                }
                TextButton(onClick = {
                    selected.clear(); selected.addAll(Component.entries.filter { it !in state.installed })
                }) { Text(L.common.selectAll) }
                TextButton(onClick = { selected.clear() }) { Text(L.common.clear) }
            }
            if (!state.filesReady) Hint(L.settings.rea.theSetupFilesAreDownloadedFirst)
        } else {
            Hint(L.settings.rea.keepAntigemOpenTheScreenStays)
            OutlinedButton(onClick = ReaManager::stop, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.common.stop)
            }
        }
    }
}

@Composable
private fun ComponentRow(c: Component, state: ReaManager.State, checked: Boolean, busy: Boolean, onCheck: (Boolean) -> Unit) {
    val installed = c in state.installed
    val running = state.installing == c
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.08f else 0.03f)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    Icon(Icons.Outlined.CheckCircle, L.common.installed, tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
                } else {
                    Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
                }
                Column(Modifier.weight(1f)) {
                    Text(c.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (state.chroot && c == Component.CONTAINER) L.settings.rea.thisPhonesRuntime else c.size + when {
                            running -> " · installing…"
                            installed -> " · installed"
                            else -> ""
                        },
                        fontSize = 11.5.sp,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                if (running) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
                } else if (!(state.chroot && c == Component.CONTAINER)) {
                    // the Ubuntu chroot is the phone's runtime: nothing to install or reinstall here
                    TextButton(enabled = !busy, onClick = { ReaManager.install(c) }) {
                        Text(if (installed) (if (c == Component.REA) L.common.update else L.common.reinstall) else L.common.install, fontSize = 12.5.sp)
                    }
                }
            }
            if (expanded || running) {
                Text(c.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                val missing = c.needs.filter { it !in state.installed }
                if (missing.isNotEmpty() && !installed) {
                    Text(L.settings.rea.alsoInstallsValue(missing.joinToString(", ") { it.label }), fontSize = 11.5.sp, color = Amber, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                }
            }
        }
    }
}

private fun isInstallTask(key: String?): Boolean = key == "batch" || Component.entries.any { it.step == key }

@Composable
private fun BatchProgress(state: ReaManager.State) {
    if (isInstallTask(state.task)) {
        val n = state.batch.size
        if (n > 1) {
            LinearProgressIndicator(
                progress = { (state.batchIndex.toFloat() / n).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(CircleShape)
            )
        }
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: L.common.starting)
    }
    if (isInstallTask(state.logTask) && state.log.isNotEmpty()) LogBox(state.log)
}

/** Progress and the log of [task], shown in its own card while it runs and after it finishes. */
@Composable
private fun ReaTaskOutput(state: ReaManager.State, task: String) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: L.common.starting)
        OutlinedButton(onClick = ReaManager::stop) { Text(L.common.stop) }
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

/** Where REA is connected (each agent's own MCP settings), what the two ways of offering its tools cost, and the plain command. */
@Composable
private fun ConnectGuide(state: ReaManager.State) {
    val onDemand = state.serverTokens(ReaManager.Mode.ON_DEMAND, listOf("all"), emptyList(), false)
    val directDefault = state.serverTokens(ReaManager.Mode.DIRECT, state.defaultSelection.ifEmpty { listOf("all") }, emptyList(), false)
    val directAll = state.serverTokens(ReaManager.Mode.DIRECT, listOf("all"), emptyList(), false)
    Hint(L.settings.rea.reaIsNotTiedToAn)
    Mono(L.settings.rea.antigravityMcpServersReaClaudeCode)
    if (!state.usable) Hint(L.settings.rea.installReaAndTheMcpLauncher)
    Text(L.settings.rea.thePresetAsksHowTheAgent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(
        L.settings.rea.onDemandRecommendedTheAgentGets(formatTokens(onDemand ?: 700)),
        fontSize = 12.sp
    )
    Text(
        L.settings.rea.directlyEveryAllowedToolIsA((directDefault?.let { L.settings.rea.formattokensTokensForTheDefaultGroups(formatTokens(it)) } ?: L.settings.rea.n24kTokensForTheDefaultGroups), (directAll?.let { L.settings.rea.formattokensForAllOfRea(formatTokens(it)) } ?: L.settings.rea.n75kForAllOfRea)),
        fontSize = 12.sp
    )
    Hint(L.settings.rea.itAlsoAsksWhichToolGroups)
    Text(L.settings.rea.anotherAgentOrByHand, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Hint(L.settings.rea.addAStdioLocalCommandMcp)
    CopyLine(ReaManager.LAUNCHER, mono = true)
    CopyLine("REA_MODE=lazy\nREA_GROUPS=all", mono = true)
}

@Composable
private fun SkillPluginCard(
    state: ReaManager.State,
    busy: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenPlugins: () -> Unit
) {
    InfoCard(
        L.settings.rea.reaSkillForAgentsOptional, cardBg, cardBorder,
        pill = if (state.pluginInstalled) "v${state.pluginVersion ?: "?"}" to QuotaGreen else L.common.optional to MaterialTheme.colorScheme.primary
    ) {
        Hint(L.settings.rea.aSkillsOnlyPluginFromThe)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = ReaManager::installSkillPlugin) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (state.pluginInstalled) L.settings.rea.updateSkill else L.settings.rea.installSkill)
            }
            TextButton(onClick = onOpenPlugins) { Text(L.common.plugins) }
        }
        ReaTaskOutput(state, "plugin")
        if (state.pluginInstalled) {
            Hint(L.settings.rea.toGiveAnAgentTheSkill)
        }
    }
}

/**
 * Website analysis: REA's website tools on the browser's tabs. One switch adds the "web" tool group (and tells agents to
 * analyze with REA first); the other runs the loopback DevTools bridge REA connects to, with a warning that any app on
 * the phone can reach it while it is on.
 */
@Composable
private fun WebsitesCard(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val bridge by ai.antigem.data.rea.ReaWebBridge.state.collectAsState()
    InfoCard(
        L.settings.rea.websitesOptional, cardBg, cardBorder,
        pill = if (bridge.running) L.common.listening to Amber else null
    ) {
        Hint(L.settings.rea.letAgentsAnalyzeWebsitesOpenIn)
        SwitchRow(
            L.settings.rea.websiteAnalysis,
            L.settings.rea.addsTheWebsitesToolGroupAnd,
            state.webEnabled, enabled = state.ready
        ) { ReaManager.setWebEnabled(it) }
        if (!state.webEnabled) return@InfoCard
        if (!state.usable) Hint(L.settings.rea.installReaAndTheMcpLauncher)
        Hint(L.settings.rea.reaServersYouAlreadyAddedOnly)

        SwitchRow(
            L.settings.rea.browserConnectionPortPort(bridge.port),
            L.settings.rea.opensEndpointSoReaCanReach(bridge.endpoint),
            bridge.running
        ) { ReaManager.setBrowserConnection(context, it) }
        Surface(shape = RoundedCornerShape(10.dp), color = Amber.copy(alpha = 0.12f)) {
            Text(
                L.settings.rea.whileThisIsOnAnyApp(bridge.port),
                fontSize = 12.sp, color = Amber, modifier = Modifier.padding(10.dp)
            )
        }
        bridge.error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        if (bridge.running) {
            CopyLine(bridge.endpoint, mono = true)
            Mono(
                L.settings.rea.connectionsReaConnectionValueOpen(bridge.connections, if (bridge.connections == 1) "" else "s", bridge.requests, (bridge.lastActivity?.let { L.settings.rea.lastValue(it) } ?: ""))
            )
        }
        Text(L.settings.rea.tryInChat, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        listOf(
            "Open https://example.com in the browser, then analyze it with REA: structure, technologies, scripts and the APIs it calls, and give recommendations.",
            L.settings.rea.withReaCheckTheOpenBrowser,
            L.settings.rea.useReaToFindWhichFrameworks
        ).forEach { CopyLine(it) }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }
            .semantics { role = Role.Switch; toggleableState = ToggleableState(checked) }
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange, modifier = Modifier.mergedIntoRow())
    }
}

@Composable
private fun ManualSetupCard(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    var open by remember { mutableStateOf(false) }
    val repo = runCatching { ai.antigem.ui.browser.PluginRepositories.parse(state.source) }.getOrNull()
    val raw = "https://raw.githubusercontent.com/${repo?.slug ?: "Santoshkurmi/antigem_plugins"}/${repo?.ref?.ifBlank { "main" } ?: "main"}/rea/skills/rea/scripts"
    // In the Ubuntu chroot the scripts run inside it, as root (AntiGem copies the files to /opt/antigem/share/antigem-rea)
    val chrootSetup = "sudo env ANTIGEM_RUNTIME=chroot bash /opt/antigem/share/antigem-rea/rea-setup.sh"
    val steps = if (state.chroot) listOf(
        L.settings.rea.n1TheSetupFilesAreIn to "$chrootSetup help",
        L.settings.rea.n2InstallWhatApkAnalysisNeeds to "$chrootSetup android",
        L.settings.rea.n3OptionalExtrasAdbAndFastboot to "$chrootSetup adb hermes debug firmware",
        L.settings.rea.n4CheckIt to L.settings.rea.reaMcpDoctor,
        L.settings.rea.n5ConnectItToAnAgent to
            "${ReaManager.CHROOT_LAUNCHER}   REA_MODE=lazy  REA_GROUPS=all",
        L.settings.rea.n6RunReasToolsByHand to "rea-shell"
    ) else listOf(
        L.settings.rea.n1DownloadTheSetupFiles to
            "mkdir -p \$PREFIX/share/antigem-rea && cd \$PREFIX/share/antigem-rea && for f in files.json rea-setup.sh rea-mcp rea-shell rea-filter.mjs groups.default.json; do curl -fsSLO $raw/\$f; done",
        L.settings.rea.n2SeeEveryStep to "bash \$PREFIX/share/antigem-rea/rea-setup.sh help",
        L.settings.rea.n3InstallWhatApkAnalysisNeeds to
            "bash \$PREFIX/share/antigem-rea/rea-setup.sh android",
        L.settings.rea.n4OptionalExtras to "bash \$PREFIX/share/antigem-rea/rea-setup.sh adb hermes debug firmware",
        L.settings.rea.n5CheckIt to L.settings.rea.reaMcpDoctor,
        L.settings.rea.n6ConnectItToAnAgent to
            "${ReaManager.LAUNCHER}   REA_MODE=lazy  REA_GROUPS=all",
        L.settings.rea.n7RunReasToolsByHand to "rea-shell"
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.settings.rea.setUpInTheTerminal, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) L.common.hide else L.common.show, fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint(L.settings.rea.preferTheTerminalOrSettingUp)
                return@Column
            }
            steps.forEach { (label, command) ->
                Hint(label)
                CopyLine(command, mono = true)
            }
            Hint(L.settings.rea.eachStepCanBeRunAgain)
        }
    }
}

@Composable
private fun RemoveCard(state: ReaManager.State, busy: Boolean, cardBg: Color, cardBorder: BorderStroke, onConfirm: (String) -> Unit) {
    if (state.installed.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.common.remove, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) L.common.hide else L.common.show, fontSize = 12.5.sp) }
            }
            if (!open) return@Column
            Hint(if (state.chroot) L.settings.rea.removeReaKeepsJavaAndNode
                else L.settings.rea.removeReaKeepsTheUbuntuContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && state.filesReady && (Component.REA in state.installed || Component.LAUNCHER in state.installed), onClick = { onConfirm("uninstall") }) { Text(L.settings.rea.removeRea2) }
                // the chroot is the phone's runtime, not REA's container: never deleted from here
                if (!state.chroot) OutlinedButton(
                    enabled = !busy && state.filesReady && Component.CONTAINER in state.installed,
                    onClick = { onConfirm("remove-container") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(L.settings.rea.deleteContainer) }
            }
            ReaTaskOutput(state, "uninstall")
            ReaTaskOutput(state, "remove-container")
        }
    }
}

/**
 * The REA preset of every agent's MCP settings (Antigravity, Claude Code, OpenCode, Codex): adds or updates an REA MCP server.
 * The user chooses how the agent sees REA's tools (on demand through four small gateway tools, or every tool
 * directly), which tools it may use (tool groups or all), and in on-demand mode which groups to offer directly as
 * well; each choice shows what it does and what it costs in context per request.
 *
 * Agent-neutral: [existing] are this agent's REA servers (name → env), [takenNames] all its server names. [onSave]
 * gets the name and env; the page saves a stdio server with the command [ReaManager.LAUNCHER] in its own format.
 * [canReplace]: the agent can update a server in place (otherwise an existing name must be removed first).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaServerDialog(
    agent: String,
    existing: Map<String, Map<String, String>>,
    takenNames: Set<String>,
    canReplace: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, env: Map<String, String>) -> Unit,
    onOpenRea: (() -> Unit)? = null,
    onOpenGroups: (() -> Unit)? = null
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ReaManager.init(context)
        ReaManager.refresh()
    }
    val state by ReaManager.state.collectAsState()
    // Opens on the agent's first REA server (to change it) or a free name for a new one
    var name by remember { mutableStateOf(existing.keys.firstOrNull() ?: generateSequence(1) { it + 1 }.map { if (it == 1) "rea" else "rea-$it" }.first { it !in takenNames }) }
    val current = existing[name]
    var mode by remember { mutableStateOf(ReaManager.Mode.ON_DEMAND) }
    var allTools by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    val direct = remember { mutableStateListOf<String>() }
    var fullSchemas by remember { mutableStateOf(false) }
    // Load the settings of the REA server the name points at; a new name starts from the recommended settings
    var loadedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name, state.ready, state.groups) {
        if (!state.ready || loadedFor == name) return@LaunchedEffect
        val env = existing[name]
        if (env == null && loadedFor != null) return@LaunchedEffect // typing a new name keeps the choices made
        chosen.clear(); direct.clear()
        if (env != null) {
            mode = ReaManager.modeOf(env)
            val groups = ReaManager.groupsOf(env)
            allTools = groups.isEmpty()
            chosen.addAll(groups)
            direct.addAll(ReaManager.directGroupsOf(env))
            fullSchemas = ReaManager.fullOutputSchemas(env)
        } else {
            // New server: on demand costs the same whatever it allows, so allow every group that works without
            // extra engines (the decompiler session needs Ghidra/IDA/Hopper/Kuna)
            mode = ReaManager.Mode.ON_DEMAND
            allTools = false
            chosen.addAll(state.groups.map { it.id }.filter { it != "decompiler" })
            fullSchemas = false
        }
        loadedFor = name
    }
    val initialGroups = current?.let { ReaManager.groupsOf(it) }
    val nameError = when {
        name.isBlank() -> L.settings.rea.enterAName
        !Regex("[A-Za-z0-9_-]+").matches(name) -> L.settings.rea.lettersDigitsAndOnly
        current == null && name in takenNames -> L.settings.rea.anotherServerHasThisName
        current != null && !canReplace -> L.settings.rea.alreadyAddedToChangeItRemove
        else -> null
    }
    val scope = if (allTools) listOf("all") else chosen.toList()
    val directIds = direct.filter { allTools || it in chosen }
    val usable = state.toolsFor(scope).size
    val directCount = state.toolsFor(scope).intersect(if (directIds.isEmpty()) emptySet() else state.toolsFor(directIds)).size
    val onDemandTokens = state.serverTokens(ReaManager.Mode.ON_DEMAND, scope, directIds, fullSchemas)
    val directTokens = state.serverTokens(ReaManager.Mode.DIRECT, scope, emptyList(), fullSchemas)
    val tokens = if (mode == ReaManager.Mode.ON_DEMAND) onDemandTokens else directTokens
    fun tk(t: Int?) = t?.let { "≈${formatTokens(it)} tokens" } ?: L.settings.rea.sizeUnknownUntilReaIsInstalled
    val dialogScroll = rememberScrollState()
    val scrollScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current != null && canReplace) L.settings.rea.reaServerNameAgent(name, agent) else L.settings.rea.addReaToAgent(agent)) },
        text = {
            // The dialog has more than fits on a screen: a scrollbar, a fade at the bottom and a "More options
            // below" button make that visible, so the choices further down are not missed
            Column {
            Box(Modifier.weight(1f, fill = false).scrollIndicator(dialogScroll, AlertDialogDefaults.containerColor)) {
            Column(
                Modifier.bouncyVerticalScroll(dialogScroll).padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!state.usable) {
                    Text(L.settings.rea.reaIsNotInstalledYetSet, fontSize = 12.5.sp, color = Amber)
                    if (onOpenRea != null) OutlinedButton(onClick = onOpenRea) { Text(L.settings.rea.openReaSetup) }
                }
                if (ai.antigem.data.preferences.AuthPreferences.currentRemoteMode) {
                    Text(
                        L.settings.rea.serverModeIsOnAgentsStart,
                        fontSize = 12.5.sp, color = Amber
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.trim() }, singleLine = true,
                    label = { Text(L.settings.rea.serverName) }, isError = nameError != null,
                    supportingText = {
                        Text(nameError ?: if (current != null) L.settings.rea.changesTheExistingReaServerName(name) else if (existing.isNotEmpty()) L.settings.rea.aNewReaServerTypeAn(existing.keys.joinToString()) else L.settings.rea.aNewReaServer)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(L.settings.rea.howTheAgentSeesReasTools, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ModeCard(
                    selected = mode == ReaManager.Mode.ON_DEMAND,
                    title = L.settings.rea.onDemand,
                    badge = L.common.recommended,
                    cost = tk(onDemandTokens),
                    text = L.settings.rea.theAgentGets4SmallTools
                ) { mode = ReaManager.Mode.ON_DEMAND }
                ModeCard(
                    selected = mode == ReaManager.Mode.DIRECT,
                    title = L.settings.rea.directly,
                    badge = null,
                    cost = tk(directTokens),
                    text = L.settings.rea.everyAllowedToolIsANormal
                ) { mode = ReaManager.Mode.DIRECT }

                Text(L.settings.rea.toolsTheAgentMayUse, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allTools = false }) {
                    RadioButton(selected = !allTools, onClick = { allTools = false }); Text(L.settings.rea.chosenToolGroups, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allTools = true }) {
                    RadioButton(selected = allTools, onClick = { allTools = true }); Text("All REA tools (${state.allToolNames.size})", fontSize = 13.sp)
                }
                if (!allTools) {
                    if (state.groups.isEmpty()) {
                        Hint(L.settings.rea.noToolGroupsYetDownloadThe)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            state.groups.forEach { g ->
                                FilterChip(
                                    selected = g.id in chosen,
                                    onClick = { if (g.id in chosen) chosen.remove(g.id) else chosen.add(g.id) },
                                    label = {
                                        val cost = if (mode == ReaManager.Mode.DIRECT) state.tokensFor(listOf(g.id))?.let { " · ${formatTokens(it)}" }.orEmpty() else ""
                                        Text("${g.name} · ${g.tools.size}$cost", fontSize = 12.sp)
                                    }
                                )
                            }
                        }
                        Hint(
                            if (mode == ReaManager.Mode.ON_DEMAND) L.settings.rea.onDemandAllowingMoreGroupsCosts
                            else L.settings.rea.directlyEachGroupAddsItsSize
                        )
                        val missing = initialGroups.orEmpty().filter { id -> state.groups.none { it.id == id } }
                        if (missing.isNotEmpty()) Text(L.settings.rea.removedGroupsStillNamedHereJointostring(missing.joinToString()), fontSize = 11.5.sp, color = Amber)
                        if (onOpenGroups != null) TextButton(onClick = onOpenGroups, contentPadding = PaddingValues(0.dp)) { Text(L.settings.rea.editToolGroups, fontSize = 12.5.sp) }
                    }
                }

                if (mode == ReaManager.Mode.ON_DEMAND) {
                    Text(L.settings.rea.alsoOfferDirectlyOptional, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Hint(L.settings.rea.forGroupsTheAgentUsesAll)
                    val offerable = state.groups.filter { allTools || it.id in chosen }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        offerable.forEach { g ->
                            FilterChip(
                                selected = g.id in direct,
                                onClick = { if (g.id in direct) direct.remove(g.id) else direct.add(g.id) },
                                label = {
                                    val cost = state.tokensFor(listOf(g.id))?.let { " +${formatTokens(it)}" }.orEmpty()
                                    Text("${g.name}$cost", fontSize = 12.sp)
                                }
                            )
                        }
                    }
                }

                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(L.settings.rea.valueOfContextPerRequest(tk(tokens).replaceFirstChar { it.uppercase() }), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (mode == ReaManager.Mode.ON_DEMAND) (if (directCount > 0) L.settings.rea.countToolsUsableDirectcountOffered(usable, directCount) else L.settings.rea.countToolsUsableAllThroughThe(usable))
                            else L.settings.rea.usableToolsEachOneAnMcp(usable),
                            fontSize = 12.sp
                        )
                        if (mode == ReaManager.Mode.ON_DEMAND && directTokens != null) {
                            Text(L.settings.rea.directlyTheSameToolsWouldCost(tk(directTokens)), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        if (tokens != null && tokens > 40_000) Text(L.settings.rea.thatIsALotAgentsGet, fontSize = 11.5.sp, color = Amber)
                    }
                }
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable { fullSchemas = !fullSchemas }.semantics { role = Role.Checkbox; toggleableState = ToggleableState(fullSchemas) }) {
                    Checkbox(checked = fullSchemas, onCheckedChange = { fullSchemas = it }, modifier = Modifier.mergedIntoRow())
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(L.settings.rea.fullOutputSchemas, fontSize = 13.sp)
                        Text(
                            L.settings.rea.describesTheShapeOfEveryRea(ReaManager.FULL_SCHEMA_FACTOR),
                            fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Hint(L.settings.rea.changesApplyWhenTheServerRestarts)
            }
            }
            if (dialogScroll.canScrollForward) {
                TextButton(
                    onClick = { scrollScope.launch { dialogScroll.animateScrollTo(dialogScroll.maxValue) } },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Outlined.KeyboardDoubleArrowDown, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(L.settings.rea.moreOptionsBelow, fontSize = 12.5.sp)
                }
            }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameError == null && (allTools || chosen.isNotEmpty()),
                onClick = {
                    onSave(
                        name,
                        ReaManager.serverEnv(
                            if (allTools) emptyList() else chosen.toList(), fullSchemas, mode,
                            directGroups = if (mode == ReaManager.Mode.ON_DEMAND) directIds else emptyList(),
                            base = current.orEmpty()
                        )
                    )
                }
            ) { Text(L.common.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/** One choice of how the agent sees the tools: a selectable card with what it does and what it costs. */
@Composable
private fun ModeCard(selected: Boolean, title: String, badge: String?, cost: String, text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(end = 10.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    badge?.let { Pill(it, QuotaGreen) }
                }
                Text(L.settings.rea.costPerRequest(cost), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * Compose has no scrollbar of its own: draws a thin thumb on the right of a scrolling area and fades its bottom edge
 * into [background] while there is more below, so it is clear the content continues.
 */
internal fun Modifier.scrollIndicator(state: ScrollState, background: Color): Modifier = drawWithContent {
    drawContent()
    val max = state.maxValue
    if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
    if (state.canScrollForward) {
        val fade = 36.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(listOf(background.copy(alpha = 0f), background), startY = size.height - fade, endY = size.height),
            topLeft = Offset(0f, size.height - fade),
            size = Size(size.width, fade)
        )
    }
    val track = size.height - 8.dp.toPx()
    val thumbH = (track * size.height / (size.height + max)).coerceAtLeast(24.dp.toPx())
    val thumbY = 4.dp.toPx() + (track - thumbH) * state.value / max
    val thumbW = 4.dp.toPx()
    drawRoundRect(
        color = Color.Gray.copy(alpha = 0.55f),
        topLeft = Offset(size.width - thumbW, thumbY),
        size = Size(thumbW, thumbH),
        cornerRadius = CornerRadius(thumbW / 2)
    )
}

@Composable
private fun InfoCard(
    title: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    pill: Pair<String, Color>? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                pill?.let { Pill(it.first, it.second) }
            }
            content()
        }
    }
}

@Composable
private fun CopyLine(text: String, mono: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (mono) Mono(text) else Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Outlined.ContentCopy, L.common.copy, Modifier.size(18.dp)) }
    }
}

internal fun formatTokens(tokens: Int): String = if (tokens >= 1000) "${(tokens + 500) / 1000}k" else "$tokens"

private fun openReaUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
