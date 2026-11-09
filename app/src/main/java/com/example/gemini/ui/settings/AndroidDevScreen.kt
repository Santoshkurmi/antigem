package com.example.gemini.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.adb.AdbConnectManager
import com.example.gemini.data.androiddev.AndroidDevManager
import com.example.gemini.data.androiddev.AndroidDevManager.Component
import com.example.gemini.data.androiddev.AndroidDevManager.Framework
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay

/**
 * Settings → Android Development: installs what is needed to build Android apps on the phone (Kotlin / Java /
 * Compose, Flutter, React Native) in the proot-distro Ubuntu container, part by part or per framework, builds a
 * hello world to check it, and installs the skill that teaches agents to use it. Agent-neutral: every agent uses
 * the same `adev` command in the terminal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AndroidDevScreen(
    cardBg: Color,
    cardBorder: BorderStroke,
    onOpenAdbConnect: () -> Unit,
    onOpenPlugins: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AndroidDevManager.init(context)
        AdbConnectManager.init(context)
        AdbConnectManager.refresh(quiet = true)
        AndroidDevManager.refresh()
        AndroidDevManager.checkForUpdate()
        // The terminal (or an agent) changes things while the user is away; keep the page live
        while (true) {
            delay(2000)
            if (!AndroidDevManager.state.value.busy) AndroidDevManager.refresh()
        }
    }
    val state by AndroidDevManager.state.collectAsState()
    val adb by AdbConnectManager.state.collectAsState()
    val busy = state.busy

    // Installs and first builds take minutes: keep the screen on while one runs (the manager also holds a wake lock)
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
        IntroCard(state, cardBg, cardBorder)

        state.message?.let { msg ->
            Surface(shape = RoundedCornerShape(12.dp), color = (if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen).copy(alpha = 0.1f)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, fontSize = 12.5.sp, color = if (state.isError) MaterialTheme.colorScheme.error else QuotaGreen, modifier = Modifier.weight(1f))
                    IconButton(onClick = AndroidDevManager::clearMessage) { Icon(Icons.Default.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }

        if (!state.ready) {
            Hint("Checking…")
            return@Column
        }
        if (!state.termuxBuild) {
            DevInfoCard("Needs a com.termux build", cardBg, BorderStroke(1.dp, Amber.copy(alpha = 0.5f))) {
                Hint("The build tools run in a proot-distro Ubuntu container installed with Termux packages, which only work in AntiGem's com.termux builds. Install one of those builds from AntiGem's releases to build apps on the phone.")
            }
            return@Column
        }
        if (!state.bootstrapInstalled) {
            DevInfoCard("Install the terminal first", cardBg, cardBorder) {
                Hint("The tools install into AntiGem's terminal. Install it in Settings → Terminal & Shell, then come back here.")
            }
            return@Column
        }

        // Step 1: setup files, downloaded on demand (nothing of this ships in the app)
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
            Hint("The setup script (android-setup.sh) and the adev commands are not part of the app. They are downloaded from the AntiGem plugins repository when you need them.")
            DevSourceRow(state.source, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.filesVersion == null -> Button(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Get setup files")
                    }
                    state.filesUpdate != null -> Button(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Update to v${state.filesUpdate}")
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) { Text("Download again") }
                }
                if (state.filesVersion != null && state.filesUpdate == null) {
                    TextButton(enabled = !busy, onClick = AndroidDevManager::checkForUpdate) { Text("Check for update") }
                }
            }
            TaskOutput(state, "files")
        }

        // Step 2: what to build → the parts it needs
        InstallStep(state, cardBg, cardBorder)

        // Step 3: a hello world per framework proves the setup works
        StepCard(3, "Build a test app", if (state.testApks.isNotEmpty()) "${state.testApks.size} built" else null, cardBg, cardBorder) {
            Hint("Creates a hello-world app in ~/${AndroidDevManager.PROJECTS_DIR} and builds its debug APK. The first build of each kind downloads Gradle and libraries (several hundred MB) and takes 5–15 minutes; later builds are much faster.")
            Framework.entries.forEach { f -> TestBuildRow(f, state, busy) }
            if (state.task?.startsWith("test-") == true || state.logTask?.startsWith("test-") == true) {
                TaskOutput(state, state.task ?: state.logTask!!)
            }
        }

        // Step 4: agents use it from the terminal, the same way whatever the agent
        StepCard(4, "Use it", null, cardBg, cardBorder) {
            UseGuide(state)
        }

        SkillCard(state, busy, cardBg, cardBorder, onOpenPlugins)

        DevInfoCard("Install on this phone", cardBg, cardBorder, pill = if (adb.connectedDevice != null) "adb connected" to QuotaGreen else "Optional" to MaterialTheme.colorScheme.primary) {
            Hint("The Install buttons above open Android's installer. Agents can also install and start apps themselves with adb (and read logcat): install adb below, then connect the terminal's adb to this phone once over Wireless debugging.")
            val device = adb.connectedDevice
            if (device != null) Mono("adb connected: ${device.serial}") else OutlinedButton(onClick = onOpenAdbConnect) { Text("Set up ADB Connect") }
        }

        // Check
        DevInfoCard("Check the tools", cardBg, cardBorder) {
            Hint("Prints the version of every tool in the container: Java, the SDK platforms, aapt2, adb, Node.js, Flutter, CMake.")
            OutlinedButton(enabled = !busy && Component.CONTAINER in state.installed && state.filesReady, onClick = AndroidDevManager::runDoctor) {
                Icon(Icons.Outlined.HealthAndSafety, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Check")
            }
            TaskOutput(state, "doctor")
        }

        TerminalCard(state, cardBg, cardBorder)

        RemoveCard(state, busy, cardBg, cardBorder) { confirm = it }
    }

    confirm?.let { what ->
        val (title, text, action) = when (what) {
            "uninstall-flutter" -> Triple("Remove Flutter?", "Deletes Flutter and its package cache (≈1.5 GB). Your Flutter projects stay.", { AndroidDevManager.uninstallFlutter() })
            "clean-cache" -> Triple("Delete Gradle's caches?", "Frees the space Gradle's downloads use. The next build of every project downloads them again (slow).", { AndroidDevManager.cleanCache() })
            else -> Triple(
                "Remove the Android tools?",
                "Deletes the Android SDK, Flutter, Gradle's files and the adev commands. The Ubuntu container, Java, Node.js (REA uses them) and your projects stay.",
                { AndroidDevManager.uninstall() }
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { action(); confirm = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun IntroCard(state: AndroidDevManager.State, cardBg: Color, cardBorder: BorderStroke) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Build Android apps on this phone", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when {
                    state.anyReady -> Pill("Ready", QuotaGreen)
                    state.installed.isNotEmpty() -> Pill("Partly set up", Amber)
                    else -> Pill("Not set up", MaterialTheme.colorScheme.primary)
                }
            }
            Hint("Let agents (and you) create, build and install Android apps right here: Kotlin or Java with Jetpack Compose, Flutter, and React Native. Gradle, the Android SDK and Flutter run in an Ubuntu container (proot-distro) inside the terminal, the same container REA uses.")
            Hint("Install only what you need: Kotlin / Java first (the others build on it), then Flutter or React Native.")
        }
    }
}

@Composable
private fun DevSourceRow(source: String, enabled: Boolean) {
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
            runCatching { AndroidDevManager.setSource(text) }
                .onSuccess { editing = false; AndroidDevManager.checkForUpdate() }
                .onFailure { error = it.message }
        }) { Text("Save") }
        TextButton(onClick = { text = AndroidDevManager.DEFAULT_SOURCE }) { Text("Default") }
        TextButton(onClick = { editing = false; text = source }) { Text("Cancel") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstallStep(state: AndroidDevManager.State, cardBg: Color, cardBorder: BorderStroke) {
    val busy = state.busy
    // What the user wants to build; starts with Kotlin / Java (everything needs it)
    val frameworks = remember { mutableStateListOf(Framework.KOTLIN) }
    val extras = remember { mutableStateListOf<Component>() }
    val wanted = (frameworks.flatMap { AndroidDevManager.componentsFor(it) } + extras).distinct()
    val plan = AndroidDevManager.plan(wanted)
    val readyCount = Framework.entries.count { state.ready(it) }

    StepCard(
        2, "Install",
        if (readyCount > 0) "$readyCount of ${Framework.entries.size} ready" else null,
        cardBg, cardBorder,
        badgeColor = if (readyCount > 0) QuotaGreen else Amber
    ) {
        Hint("Pick what you want to build. Everything it needs is installed for you, one part after another.")
        val facts = listOfNotNull(
            state.containerName?.let { "Container: $it" },
            state.platforms.takeIf { it.isNotEmpty() }?.let { "SDK platforms: ${it.joinToString()}" },
            state.flutterVersion?.let { "Flutter $it" },
            state.nodeVersion?.let { "Node.js $it" }
        )
        if (facts.isNotEmpty()) Mono(facts.joinToString("\n"))

        Text("What will you build?", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Framework.entries.forEach { f ->
            FrameworkRow(f, state, f in frameworks, busy) { on ->
                if (on) frameworks.add(f) else frameworks.remove(f)
            }
        }
        Text("Extras", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        listOf(Component.ADB, Component.NDK).forEach { c ->
            PartRow(c, state, checked = c in extras, busy = busy) { on -> if (on) extras.add(c) else extras.remove(c) }
        }

        if (isInstallTask(state.task) || isInstallTask(state.logTask)) BatchProgress(state)
        if (!busy) {
            if (plan.isNotEmpty()) Hint("Installs ${plan.size} part${if (plan.size == 1) "" else "s"}: ${plan.joinToString(", ") { it.label }}.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = plan.isNotEmpty(), onClick = { AndroidDevManager.installAll(plan) }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (plan.isEmpty()) "Nothing to install" else "Install selected (${plan.size})")
                }
            }
            if (!state.filesReady) Hint("The setup files are downloaded first.")
        } else if (isInstallTask(state.task)) {
            Hint("Keep AntiGem open: the screen stays on while this runs. Stopping half way is safe; install the part again to finish it.")
            OutlinedButton(onClick = AndroidDevManager::stop, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop")
            }
        }

        var showParts by remember { mutableStateOf(false) }
        TextButton(onClick = { showParts = !showParts }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showParts) "Hide the parts" else "All parts (install, repair or update one)", fontSize = 12.5.sp)
        }
        if (showParts) {
            Component.entries.forEach { c -> PartRow(c, state, checked = false, busy = busy, onCheck = null) }
        }
    }
}

@Composable
private fun FrameworkRow(f: Framework, state: AndroidDevManager.State, checked: Boolean, busy: Boolean, onCheck: (Boolean) -> Unit) {
    val ready = state.ready(f)
    val missing = AndroidDevManager.componentsFor(f).filter { it !in state.installed }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !busy && !ready) { onCheck(!checked) }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            if (ready) Icon(Icons.Outlined.CheckCircle, "Ready", tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
            else Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
            Column(Modifier.weight(1f).padding(top = 10.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (ready) "Ready" else "${f.size} · needs ${missing.joinToString(", ") { it.label }}",
                    fontSize = 11.5.sp, color = if (ready) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Text(f.description, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
    }
}

/** One part: a checkbox when [onCheck] is set (extras), else install / reinstall only. */
@Composable
private fun PartRow(c: Component, state: AndroidDevManager.State, checked: Boolean, busy: Boolean, onCheck: ((Boolean) -> Unit)?) {
    val installed = c in state.installed
    val running = state.installing == c
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.08f else 0.03f)) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    installed -> Icon(Icons.Outlined.CheckCircle, "Installed", tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
                    onCheck != null -> Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
                    else -> Icon(Icons.Outlined.RadioButtonUnchecked, "Not installed", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.padding(12.dp).size(22.dp))
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
                    TextButton(enabled = !busy, onClick = { AndroidDevManager.install(c) }) {
                        Text(if (installed) (if (c == Component.FLUTTER) "Update" else "Reinstall") else "Install", fontSize = 12.5.sp)
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

@Composable
private fun TestBuildRow(f: Framework, state: AndroidDevManager.State, busy: Boolean) {
    val running = state.task == "test-${f.key}"
    val apk = state.testApks[f.key]
    val ready = state.ready(f)
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (running) 0.08f else 0.03f)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        running -> "Building…"
                        apk != null -> "~/${AndroidDevManager.PROJECTS_DIR}/hello-${f.key} · APK built"
                        !ready -> "Install it first"
                        else -> "~/${AndroidDevManager.PROJECTS_DIR}/hello-${f.key}"
                    },
                    fontSize = 11.5.sp, color = if (apk != null && !running) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (running) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            } else {
                if (apk != null) TextButton(onClick = { AndroidDevManager.installApk(apk) }) { Text("Install", fontSize = 12.5.sp) }
                TextButton(enabled = !busy && ready, onClick = { AndroidDevManager.testBuild(f) }) { Text(if (apk != null) "Rebuild" else "Build", fontSize = 12.5.sp) }
            }
        }
    }
}

private fun isInstallTask(key: String?): Boolean = key == "batch" || Component.entries.any { it.step == key }

@Composable
private fun BatchProgress(state: AndroidDevManager.State) {
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

/** Progress and the log of [task], shown while it runs and after it finishes. */
@Composable
private fun TaskOutput(state: AndroidDevManager.State, task: String) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: "Starting…")
        OutlinedButton(onClick = AndroidDevManager::stop) { Text("Stop") }
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

/** How agents (any agent) and the user build: the adev commands in the terminal. */
@Composable
private fun UseGuide(state: AndroidDevManager.State) {
    Hint("Any agent that can run terminal commands can build apps: Antigravity, Claude Code, OpenCode or Codex. Ask it in chat; the Android Development skill (below) tells it the commands. In the terminal yourself:")
    if (Component.LAUNCHER !in state.installed) Hint("Install Kotlin / Java above first.")
    DevCopyLine("adev-new compose ~/${AndroidDevManager.PROJECTS_DIR}/my-app", mono = true)
    DevCopyLine("cd ~/${AndroidDevManager.PROJECTS_DIR}/my-app && adev ./gradlew assembleDebug", mono = true)
    Hint("adev runs a command in the container with Java, the SDK, Gradle, Flutter and Node.js on PATH, in the current folder. adev-new also makes Flutter (flutter) and React Native (rn) projects. Keep projects in the Termux home, not on shared storage.")
    Text("Try in chat", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    listOf(
        "Create a Jetpack Compose app called Notes in ~/AndroidProjects/notes with a list and an add button, build it and give me the APK.",
        "Make a Flutter counter app in ~/AndroidProjects/counter, build the debug APK and install it on this phone.",
        "Create a React Native app in ~/AndroidProjects/hello-rn that shows today's date, and build it."
    ).forEach { DevCopyLine(it) }
}

@Composable
private fun SkillCard(state: AndroidDevManager.State, busy: Boolean, cardBg: Color, cardBorder: BorderStroke, onOpenPlugins: () -> Unit) {
    DevInfoCard(
        "Android Development skill for agents", cardBg, cardBorder,
        pill = if (state.pluginInstalled) "v${state.pluginVersion ?: "?"}" to QuotaGreen else "Recommended" to MaterialTheme.colorScheme.primary
    ) {
        Hint("A skills-only plugin from the AntiGem plugins repository. It teaches agents to check the setup, install what is missing (asking you first), create projects with adev-new, build with adev, install the APK and fix the usual errors of building on a phone.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = AndroidDevManager::installSkillPlugin) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (state.pluginInstalled) "Update skill" else "Install skill")
            }
            TextButton(onClick = onOpenPlugins) { Text("Plugins") }
        }
        TaskOutput(state, "plugin")
        if (state.pluginInstalled) {
            Hint("To give an agent the skill, type /plu: in its chat and pick Android Development, or add the plugin in that agent's MCP settings with the Custom Plugin MCP preset.")
        }
    }
}

@Composable
private fun TerminalCard(state: AndroidDevManager.State, cardBg: Color, cardBorder: BorderStroke) {
    var open by remember { mutableStateOf(false) }
    val repo = runCatching { com.example.gemini.ui.browser.PluginRepositories.parse(state.source) }.getOrNull()
    val raw = "https://raw.githubusercontent.com/${repo?.slug ?: "Santoshkurmi/antigem_plugins"}/${repo?.ref?.ifBlank { "main" } ?: "main"}/android-dev/skills/android-dev/scripts"
    val s = "\$PREFIX/share/antigem-android/android-setup.sh"
    val steps = listOf(
        "1. Download the setup files" to
            "mkdir -p \$PREFIX/share/antigem-android && cd \$PREFIX/share/antigem-android && for f in files.json android-setup.sh adev adev-new; do curl -fsSLO $raw/\$f; done",
        "2. See every step" to "bash $s help",
        "3. Kotlin / Java / Compose (container base java sdk launcher)" to "bash $s kotlin",
        "4. Flutter (with release builds) and / or React Native (Node.js and the NDK)" to "bash $s flutter flutter-release node ndk",
        "5. Check it, and build a test app" to "bash $s doctor && bash $s test compose"
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Set up in the terminal", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show", fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint("Prefer the terminal? These are the same steps as commands.")
                return@Column
            }
            steps.forEach { (label, command) ->
                Hint(label)
                DevCopyLine(command, mono = true)
            }
            Hint("Each step can be run again to repair or update it. The skill gives agents these steps too, so you can also ask an agent to set this up.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoveCard(state: AndroidDevManager.State, busy: Boolean, cardBg: Color, cardBorder: BorderStroke, onConfirm: (String) -> Unit) {
    if (state.installed.none { it == Component.SDK || it == Component.FLUTTER || it == Component.LAUNCHER }) return
    var open by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Free space / remove", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show", fontSize = 12.5.sp) }
            }
            if (!open) return@Column
            Hint("Your projects are never deleted. The Ubuntu container itself is removed on the REA page (Delete container), since both use it.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = !busy && state.filesReady, onClick = { onConfirm("clean-cache") }) { Text("Clean Gradle cache") }
                if (Component.FLUTTER in state.installed) OutlinedButton(enabled = !busy && state.filesReady, onClick = { onConfirm("uninstall-flutter") }) { Text("Remove Flutter") }
                OutlinedButton(
                    enabled = !busy && state.filesReady,
                    onClick = { onConfirm("uninstall") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove all") }
            }
            TaskOutput(state, "clean-cache")
            TaskOutput(state, "uninstall-flutter")
            TaskOutput(state, "uninstall")
        }
    }
}

@Composable
private fun DevInfoCard(
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
private fun DevCopyLine(text: String, mono: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (mono) Mono(text) else Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(18.dp)) }
    }
}
