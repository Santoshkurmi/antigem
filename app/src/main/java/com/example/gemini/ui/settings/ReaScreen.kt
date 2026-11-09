package com.example.gemini.ui.settings

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.adb.AdbConnectManager
import com.example.gemini.data.rea.ReaManager
import com.example.gemini.data.rea.ReaManager.Component
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                    IconButton(onClick = ReaManager::clearMessage) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }

        if (!state.ready) {
            Hint("Checking…")
            return@Column
        }
        if (!state.termuxBuild) {
            InfoCard("Needs a com.termux build", cardBg, BorderStroke(1.dp, Amber.copy(alpha = 0.5f))) {
                Hint("REA runs in a proot-distro Ubuntu container installed with Termux packages, which only work in AntiGem's com.termux builds. Install one of those builds from AntiGem's releases to use REA.")
            }
            return@Column
        }
        if (!state.bootstrapInstalled) {
            InfoCard("Install the terminal first", cardBg, cardBorder) {
                Hint("REA installs into AntiGem's terminal. Install it in Settings → Terminal & Shell, then come back here.")
            }
            return@Column
        }

        // Step 1: setup files, downloaded on demand (nothing of REA ships in the app)
        StepCard(
            1, "Setup files",
            when {
                state.filesUpdate != null -> "Update"
                state.filesVersion != null -> "v${state.filesVersion}"
                else -> null
            },
            cardBg, cardBorder,
            badgeColor = if (state.filesUpdate != null) Amber else QuotaGreen
        ) {
            Hint("REA's setup scripts (rea-setup.sh, the rea-mcp launcher and the default tool groups) are not part of the app. They are downloaded from the AntiGem plugins repository when you need them.")
            SourceRow(state.source, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.filesVersion == null -> Button(enabled = !busy, onClick = ReaManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Get setup files")
                    }
                    state.filesUpdate != null -> Button(enabled = !busy, onClick = ReaManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Update to v${state.filesUpdate}")
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = ReaManager::downloadSetupFiles) { Text("Download again") }
                }
                if (state.filesVersion != null && state.filesUpdate == null) {
                    TextButton(enabled = !busy, onClick = ReaManager::checkForUpdate) { Text("Check for update") }
                }
            }
            ReaTaskOutput(state, "files")
        }

        // Step 2: the parts, installed one by one or several at once
        InstallStep(state, cardBg, cardBorder)

        // Step 3: which tools agents see
        StepCard(
            3, "Tool groups",
            if (state.groups.isNotEmpty()) "${state.groups.size} groups" else null,
            cardBg, cardBorder
        ) {
            Hint("REA has over 200 tools. Tool groups decide which tools each REA server lets the agent use; on a server that shows its tools directly, every tool also costs context on each request. Start with the built-in groups and change them any time: add or remove tools, or create your own.")
            if (state.groups.isNotEmpty()) {
                val grouped = state.allToolNames.size - state.ungrouped.size
                Mono(
                    "${state.groups.size} groups · $grouped of ${state.allToolNames.size} tools grouped" +
                        (if (state.ungrouped.isNotEmpty()) "\n${state.ungrouped.size} tools in no group" else "") +
                        (state.toolsServer?.let { "\nTool list from $it" } ?: "\nTool list: install REA to read it")
                )
            } else {
                Hint("The default groups come with the setup files.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = state.groups.isNotEmpty() || state.filesReady, onClick = onOpenGroups) {
                    Icon(Icons.Outlined.Category, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Edit tool groups")
                }
                OutlinedButton(enabled = !busy && state.usable, onClick = ReaManager::refreshTools) { Text("Refresh tool list") }
            }
            ReaTaskOutput(state, "tools")
        }

        // Step 4: connecting it to an agent happens in that agent's MCP settings, the same way for every agent
        StepCard(4, "Connect REA to your agent", null, cardBg, cardBorder) {
            ConnectGuide(state)
        }

        // Step 5: health check
        StepCard(5, "Check it works", null, cardBg, cardBorder) {
            Hint("Runs rea doctor in the container: it reports which engines REA can use (JADX, apktool, objdump, rizin, adb…). Engines you did not install show as missing, which is fine.")
            OutlinedButton(enabled = !busy && state.usable, onClick = ReaManager::runDoctor) {
                Icon(Icons.Outlined.HealthAndSafety, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Run health check")
            }
            ReaTaskOutput(state, "doctor")
            Text("Try in chat", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            listOf(
                "Use REA to find the APK of the installed app com.example.app and summarize its manifest and permissions.",
                "With REA, is com.example.app a Flutter, React Native or native app? Show the evidence.",
                "Decompile the MainActivity of com.example.app with REA and explain what it does."
            ).forEach { CopyLine(it) }
        }

        SkillPluginCard(state, busy, cardBg, cardBorder, onOpenPlugins)

        // Optional: adb to this phone, for REA's device tools
        InfoCard("Phone access (optional)", cardBg, cardBorder, pill = if (adb.connectedDevice != null) "Connected" to QuotaGreen else "Optional" to MaterialTheme.colorScheme.primary) {
            Hint("REA's phone tools (installed apps, pulling APKs, logcat, screenshots, starting apps) use adb. Install adb above, then connect the terminal's adb to this phone once over Wireless debugging. Without it, agents can still read installed apps' APKs: pm path <package> gives the path.")
            val device = adb.connectedDevice
            if (device != null) Mono("adb connected: ${device.serial}") else OutlinedButton(onClick = onOpenAdbConnect) { Text("Set up ADB Connect") }
        }

        ManualSetupCard(state, cardBg, cardBorder)

        RemoveCard(state, busy, cardBg, cardBorder) { confirm = it }
    }

    confirm?.let { what ->
        val (title, text, action) = when (what) {
            "uninstall" -> Triple(
                "Remove REA?",
                "Removes REA, the JADX and apktool jars, hermes-dec and the rea-mcp launcher. The Ubuntu container, Java, Node.js and your tool groups stay. REA servers in your agents stop working until you install it again.",
                { ReaManager.uninstallRea() }
            )
            else -> Triple(
                "Delete the Ubuntu container?",
                "Deletes the whole '${ReaManager.CONTAINER}' proot-distro container: REA, Java, Node.js and anything else you installed or saved in it (also outside REA). This cannot be undone.",
                { ReaManager.removeContainer() }
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { action(); confirm = null }) { Text(if (what == "uninstall") "Remove" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
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
                Text("Reverse engineering with REA", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when {
                    state.usable && state.requiredDone -> Pill("Ready", QuotaGreen)
                    state.installed.isNotEmpty() -> Pill("Partly set up", Amber)
                    else -> Pill("Not set up", MaterialTheme.colorScheme.primary)
                }
            }
            Hint("Let agents take apart Android apps (APK, Flutter, React Native) and native code: decompile classes, read manifests and resources, inspect .so libraries, look at installed apps. REA is an open-source MCP server; AntiGem runs it in an Ubuntu container (proot-distro) inside the terminal.")
            Hint("Install only the parts you need. Everything for APK analysis takes about 1 GB.")
            TextButton(onClick = { openReaUrl(context, ReaManager.REA_HOMEPAGE) }, contentPadding = PaddingValues(0.dp)) {
                Text("About REA (github.com/morluto/rea)", fontSize = 12.5.sp)
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
            Text("From $source", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
            TextButton(enabled = enabled, onClick = { editing = true }) { Text("Change", fontSize = 12.sp) }
        }
        return
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it; error = null }, singleLine = true,
        label = { Text("owner/repo@branch") }, isError = error != null,
        supportingText = { Text(error ?: "A fork of the AntiGem plugins repository, for testing changes to the setup.") },
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            runCatching { ReaManager.setSource(text) }
                .onSuccess { editing = false; ReaManager.checkForUpdate() }
                .onFailure { error = it.message }
        }) { Text("Save") }
        TextButton(onClick = { text = ReaManager.DEFAULT_SOURCE }) { Text("Default") }
        TextButton(onClick = { editing = false; text = source }) { Text("Cancel") }
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
        2, "Install",
        when {
            state.requiredDone -> "$installedCount of $total"
            installedCount > 0 -> "$installedCount of $total"
            else -> null
        },
        cardBg, cardBorder,
        badgeColor = if (state.requiredDone) QuotaGreen else Amber
    ) {
        Hint("Pick the parts you need and install them together, or install them one at a time with the button on each. Anything a part needs is added for you.")
        val facts = listOfNotNull(
            state.containerName?.let { "Container: $it" },
            state.nodeVersion?.let { "Node.js $it" },
            state.reaVersion?.let { "REA $it" }
        )
        if (facts.isNotEmpty()) Mono(facts.joinToString("\n"))

        Text("Needed for APK analysis", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Component.entries.filter { it.required }.forEach { c -> ComponentRow(c, state, c in selected, busy) { on -> if (on) selected.add(c) else selected.remove(c) } }
        Text("Optional", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        Component.entries.filter { !it.required }.forEach { c -> ComponentRow(c, state, c in selected, busy) { on -> if (on) selected.add(c) else selected.remove(c) } }

        if (isInstallTask(state.task) || isInstallTask(state.logTask)) BatchProgress(state)
        if (!busy) {
            if (plan.isNotEmpty()) {
                val extra = plan.filter { it !in selected }
                Hint(
                    "Installs ${plan.size} part${if (plan.size == 1) "" else "s"}: ${plan.joinToString(", ") { it.label }}" +
                        if (extra.isNotEmpty()) " (${extra.joinToString(", ") { it.label }} added because the others need them)." else "."
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = plan.isNotEmpty(), onClick = { ReaManager.installAll(plan) }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (plan.isEmpty()) "Nothing to install" else "Install selected (${plan.size})")
                }
                TextButton(onClick = {
                    selected.clear(); selected.addAll(Component.entries.filter { it !in state.installed })
                }) { Text("Select all") }
                TextButton(onClick = { selected.clear() }) { Text("Clear") }
            }
            if (!state.filesReady) Hint("The setup files are downloaded first.")
        } else {
            Hint("Keep AntiGem open: the screen stays on while this runs. Stopping half way is safe; install the part again to finish it.")
            OutlinedButton(onClick = ReaManager::stop, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop")
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
                    Icon(Icons.Outlined.CheckCircle, "Installed", tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
                } else {
                    Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
                }
                Column(Modifier.weight(1f)) {
                    Text(c.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        c.size + when {
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
                } else {
                    TextButton(enabled = !busy, onClick = { ReaManager.install(c) }) {
                        Text(if (installed) (if (c == Component.REA) "Update" else "Reinstall") else "Install", fontSize = 12.5.sp)
                    }
                }
            }
            if (expanded || running) {
                Text(c.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                val missing = c.needs.filter { it !in state.installed }
                if (missing.isNotEmpty() && !installed) {
                    Text("Also installs: ${missing.joinToString(", ") { it.label }}", fontSize = 11.5.sp, color = Amber, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
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
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: "Starting…")
    }
    if (isInstallTask(state.logTask) && state.log.isNotEmpty()) LogBox(state.log)
}

/** Progress and the log of [task], shown in its own card while it runs and after it finishes. */
@Composable
private fun ReaTaskOutput(state: ReaManager.State, task: String) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: "Starting…")
        OutlinedButton(onClick = ReaManager::stop) { Text("Stop") }
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

/** Where REA is connected (each agent's own MCP settings), what the two ways of offering its tools cost, and the plain command. */
@Composable
private fun ConnectGuide(state: ReaManager.State) {
    val onDemand = state.serverTokens(ReaManager.Mode.ON_DEMAND, listOf("all"), emptyList(), false)
    val directDefault = state.serverTokens(ReaManager.Mode.DIRECT, state.defaultSelection.ifEmpty { listOf("all") }, emptyList(), false)
    val directAll = state.serverTokens(ReaManager.Mode.DIRECT, listOf("all"), emptyList(), false)
    Hint("REA is not tied to an agent: any agent can use it. Add it where you manage that agent's MCP servers, with the REA preset:")
    Mono("Antigravity → MCP Servers → REA\nClaude Code → MCP → REA\nOpenCode → MCP servers → REA\nCodex → MCP servers → REA")
    if (!state.usable) Hint("Install REA and the MCP launcher above first.")
    Text("The preset asks how the agent should see REA's tools", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "On demand (recommended): the agent gets 4 small tools. It lists REA's tools by name, reads the ones it needs and runs them; tools that change things run through their own tool. " +
            "≈${formatTokens(onDemand ?: 700)} tokens per request, however many tools you allow.",
        fontSize = 12.sp
    )
    Text(
        "Directly: every allowed tool is a normal MCP tool, so there is no lookup step, but each is sent with every request: " +
            (directDefault?.let { "≈${formatTokens(it)} tokens for the default groups" } ?: "≈24k tokens for the default groups") +
            (directAll?.let { ", ≈${formatTokens(it)} for all of REA." } ?: ", ≈75k for all of REA."),
        fontSize = 12.sp
    )
    Hint("It also asks which tool groups the agent may use (step 3). You can add REA to several agents, each with its own choice.")
    Text("Another agent, or by hand", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Hint("Add a stdio (local command) MCP server with this command and these environment variables. REA_MODE is lazy (on demand) or direct; REA_GROUPS is all or group ids such as core,android.")
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
        "REA skill for agents (optional)", cardBg, cardBorder,
        pill = if (state.pluginInstalled) "v${state.pluginVersion ?: "?"}" to QuotaGreen else "Optional" to MaterialTheme.colorScheme.primary
    ) {
        Hint("A skills-only plugin from the AntiGem plugins repository. It teaches agents how to use REA well (installed apps, split APKs, Flutter, React Native, native code), to check that REA works and tell you what to fix, and how to install REA from the terminal if you prefer that to this page.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = ReaManager::installSkillPlugin) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (state.pluginInstalled) "Update skill" else "Install skill")
            }
            TextButton(onClick = onOpenPlugins) { Text("Plugins") }
        }
        ReaTaskOutput(state, "plugin")
        if (state.pluginInstalled) {
            Hint("To give an agent the skill, type /plu: in its chat and pick REA, or add the plugin in that agent's MCP settings with the Custom Plugin MCP preset → REA.")
        }
    }
}

@Composable
private fun ManualSetupCard(state: ReaManager.State, cardBg: Color, cardBorder: BorderStroke) {
    var open by remember { mutableStateOf(false) }
    val repo = runCatching { com.example.gemini.ui.browser.PluginRepositories.parse(state.source) }.getOrNull()
    val raw = "https://raw.githubusercontent.com/${repo?.slug ?: "Santoshkurmi/antigem_plugins"}/${repo?.ref?.ifBlank { "main" } ?: "main"}/rea/skills/rea/scripts"
    val steps = listOf(
        "1. Download the setup files" to
            "mkdir -p \$PREFIX/share/antigem-rea && cd \$PREFIX/share/antigem-rea && for f in files.json rea-setup.sh rea-mcp rea-shell rea-filter.mjs groups.default.json; do curl -fsSLO $raw/\$f; done",
        "2. See every step" to "bash \$PREFIX/share/antigem-rea/rea-setup.sh help",
        "3. Install what APK analysis needs (or name single steps: container base java node rea jadx apktool native launcher)" to
            "bash \$PREFIX/share/antigem-rea/rea-setup.sh android",
        "4. Optional extras" to "bash \$PREFIX/share/antigem-rea/rea-setup.sh adb hermes debug firmware",
        "5. Check it" to "rea-mcp --doctor",
        "6. Connect it to an agent: in the app, use the REA preset in the agent's MCP settings. By hand, a stdio MCP server with this command and env (REA_MODE lazy = on demand, ≈0.7k tokens; direct = every tool, each costs context; REA_GROUPS all or group ids from ~/.config/antigem/rea/groups.json)" to
            "${ReaManager.LAUNCHER}   REA_MODE=lazy  REA_GROUPS=all",
        "7. Run REA's tools by hand (hbc-decompiler, apktool, rea …) inside the container" to "rea-shell"
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Set up in the terminal", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show", fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint("Prefer the terminal, or setting up Claude Code, OpenCode or Codex? These are the same steps as commands.")
                return@Column
            }
            steps.forEach { (label, command) ->
                Hint(label)
                CopyLine(command, mono = true)
            }
            Hint("Each step can be run again to repair or update it. The REA skill plugin gives agents these steps too, so you can also ask an agent to install REA for you.")
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
                Text("Remove", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show", fontSize = 12.5.sp) }
            }
            if (!open) return@Column
            Hint("Remove REA keeps the Ubuntu container (and Java and Node.js in it) for other uses. Deleting the container frees all the space.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy && state.filesReady && (Component.REA in state.installed || Component.LAUNCHER in state.installed), onClick = { onConfirm("uninstall") }) { Text("Remove REA") }
                OutlinedButton(
                    enabled = !busy && state.filesReady && Component.CONTAINER in state.installed,
                    onClick = { onConfirm("remove-container") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete container") }
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
        name.isBlank() -> "Enter a name"
        !Regex("[A-Za-z0-9_-]+").matches(name) -> "Letters, digits, - and _ only"
        current == null && name in takenNames -> "Another server has this name"
        current != null && !canReplace -> "Already added. To change it, remove it from the list first, or use another name."
        else -> null
    }
    val scope = if (allTools) listOf("all") else chosen.toList()
    val directIds = direct.filter { allTools || it in chosen }
    val usable = state.toolsFor(scope).size
    val directCount = state.toolsFor(scope).intersect(if (directIds.isEmpty()) emptySet() else state.toolsFor(directIds)).size
    val onDemandTokens = state.serverTokens(ReaManager.Mode.ON_DEMAND, scope, directIds, fullSchemas)
    val directTokens = state.serverTokens(ReaManager.Mode.DIRECT, scope, emptyList(), fullSchemas)
    val tokens = if (mode == ReaManager.Mode.ON_DEMAND) onDemandTokens else directTokens
    fun tk(t: Int?) = t?.let { "≈${formatTokens(it)} tokens" } ?: "size unknown until REA is installed"
    val dialogScroll = rememberScrollState()
    val scrollScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current != null && canReplace) "REA server $name · $agent" else "Add REA to $agent") },
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
                    Text("REA is not installed yet. Set it up first, then add the server.", fontSize = 12.5.sp, color = Amber)
                    if (onOpenRea != null) OutlinedButton(onClick = onOpenRea) { Text("Open REA setup") }
                }
                if (com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode) {
                    Text(
                        "Server mode is on: agents start MCP servers on the server, where this phone's REA is not installed. REA works when the agent runs on this phone (Settings → Servers & Connectivity).",
                        fontSize = 12.5.sp, color = Amber
                    )
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it.trim() }, singleLine = true,
                    label = { Text("Server name") }, isError = nameError != null,
                    supportingText = {
                        Text(nameError ?: if (current != null) "Changes the existing REA server $name." else if (existing.isNotEmpty()) "A new REA server (type an existing name to change that one: ${existing.keys.joinToString()})." else "A new REA server.")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("How the agent sees REA's tools", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ModeCard(
                    selected = mode == ReaManager.Mode.ON_DEMAND,
                    title = "On demand",
                    badge = "Recommended",
                    cost = tk(onDemandTokens),
                    text = "The agent gets 4 small tools: it lists REA's tools by name, reads the details of the ones it needs, then runs them. " +
                        "The context stays small however many tools you allow. The first time it uses a tool takes 1–2 extra steps. " +
                        "Tools that change things (installing apps, debuggers, Frida…) run through their own tool, so you can approve them separately."
                ) { mode = ReaManager.Mode.ON_DEMAND }
                ModeCard(
                    selected = mode == ReaManager.Mode.DIRECT,
                    title = "Directly",
                    badge = null,
                    cost = tk(directTokens),
                    text = "Every allowed tool is a normal MCP tool, so the agent uses it straight away. But every tool's description is sent with every request: " +
                        "allow only a few groups (the default five cost about 24k tokens; all of REA about 75k)."
                ) { mode = ReaManager.Mode.DIRECT }

                Text("Tools the agent may use", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allTools = false }) {
                    RadioButton(selected = !allTools, onClick = { allTools = false }); Text("Chosen tool groups", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { allTools = true }) {
                    RadioButton(selected = allTools, onClick = { allTools = true }); Text("All REA tools (${state.allToolNames.size})", fontSize = 13.sp)
                }
                if (!allTools) {
                    if (state.groups.isEmpty()) {
                        Hint("No tool groups yet: download the setup files on the REA page.")
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
                            if (mode == ReaManager.Mode.ON_DEMAND) "On demand, allowing more groups costs nothing up front. \"Decompiler session\" needs Ghidra, IDA, Hopper or Kuna, which are not installed."
                            else "Directly, each group adds its size (tokens shown on the chip) to every request."
                        )
                        val missing = initialGroups.orEmpty().filter { id -> state.groups.none { it.id == id } }
                        if (missing.isNotEmpty()) Text("Removed groups still named here: ${missing.joinToString()}. Saving drops them.", fontSize = 11.5.sp, color = Amber)
                        if (onOpenGroups != null) TextButton(onClick = onOpenGroups, contentPadding = PaddingValues(0.dp)) { Text("Edit tool groups", fontSize = 12.5.sp) }
                    }
                }

                if (mode == ReaManager.Mode.ON_DEMAND) {
                    Text("Also offer directly (optional)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Hint("For groups the agent uses all the time: their tools skip the lookup step, but each adds its size to every request. Usually not needed.")
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
                        Text("${tk(tokens).replaceFirstChar { it.uppercase() }} of context per request", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (mode == ReaManager.Mode.ON_DEMAND) "$usable tools usable" + if (directCount > 0) ", $directCount offered directly" else ", all through the 4 gateway tools"
                            else "$usable tools, each one an MCP tool",
                            fontSize = 12.sp
                        )
                        if (mode == ReaManager.Mode.ON_DEMAND && directTokens != null) {
                            Text("Directly, the same tools would cost ${tk(directTokens)}.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        if (tokens != null && tokens > 40_000) Text("That is a lot: agents get slower and use up their context sooner. Use On demand, or fewer groups.", fontSize = 11.5.sp, color = Amber)
                    }
                }
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable { fullSchemas = !fullSchemas }) {
                    Checkbox(checked = fullSchemas, onCheckedChange = { fullSchemas = it })
                    Column(Modifier.padding(top = 12.dp)) {
                        Text("Full output schemas", fontSize = 13.sp)
                        Text(
                            "Describes the shape of every REA result in the tool list: about ${ReaManager.FULL_SCHEMA_FACTOR}× bigger for tools offered directly. Leave off unless your agent needs it.",
                            fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Hint("Changes apply when the server restarts.")
            }
            }
            if (dialogScroll.canScrollForward) {
                TextButton(
                    onClick = { scrollScope.launch { dialogScroll.animateScrollTo(dialogScroll.maxValue) } },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Outlined.KeyboardDoubleArrowDown, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("More options below", fontSize = 12.5.sp)
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
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
                Text(cost + " per request", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
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
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(18.dp)) }
    }
}

internal fun formatTokens(tokens: Int): String = if (tokens >= 1000) "${(tokens + 500) / 1000}k" else "$tokens"

private fun openReaUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
