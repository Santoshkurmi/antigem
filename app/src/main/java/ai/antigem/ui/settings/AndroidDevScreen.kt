package ai.antigem.ui.settings

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
import ai.antigem.data.adb.AdbConnectManager
import ai.antigem.data.androiddev.AndroidDevManager
import ai.antigem.data.androiddev.AndroidDevManager.Component
import ai.antigem.data.androiddev.AndroidDevManager.Framework
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.bouncyVerticalScroll
import kotlinx.coroutines.delay
import ai.antigem.i18n.L

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
                    IconButton(onClick = AndroidDevManager::clearMessage) { Icon(Icons.Default.Close, L.common.dismiss, Modifier.size(18.dp)) }
                }
            }
        }

        if (!state.ready) {
            Hint(L.common.checking)
            return@Column
        }
        if (!state.bootstrapInstalled) {
            DevInfoCard(L.settings.androidDev.installTheTerminalFirst, cardBg, cardBorder) {
                Hint(L.settings.androidDev.theToolsInstallIntoAntigemsTerminal)
            }
            return@Column
        }

        // Step 1: setup files, downloaded on demand (nothing of this ships in the app)
        StepCard(
            1, L.settings.androidDev.setupFiles,
            when {
                state.filesUpdate != null -> L.common.update
                state.filesVersion != null -> "v${state.filesVersion}"
                else -> null
            },
            cardBg, cardBorder,
            badgeColor = if (state.filesUpdate != null) Amber else QuotaGreen
        ) {
            Hint(L.settings.androidDev.theSetupScriptAndroidSetupSh)
            DevSourceRow(state.source, enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.filesVersion == null -> Button(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.androidDev.getSetupFiles)
                    }
                    state.filesUpdate != null -> Button(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.settings.androidDev.updateToVFilesupdate(state.filesUpdate))
                    }
                    else -> OutlinedButton(enabled = !busy, onClick = AndroidDevManager::downloadSetupFiles) { Text(L.settings.androidDev.downloadAgain) }
                }
                if (state.filesVersion != null && state.filesUpdate == null) {
                    TextButton(enabled = !busy, onClick = AndroidDevManager::checkForUpdate) { Text(L.common.checkForUpdate) }
                }
            }
            TaskOutput(state, "files")
        }

        // Step 2: what to build → the parts it needs
        InstallStep(state, cardBg, cardBorder)

        // Step 3: a hello world per framework proves the setup works
        StepCard(3, L.settings.androidDev.buildATestApp, if (state.testApks.isNotEmpty()) "${state.testApks.size} built" else null, cardBg, cardBorder) {
            Hint(L.settings.androidDev.createsAHelloWorldAppIn(AndroidDevManager.PROJECTS_DIR))
            Framework.entries.forEach { f -> TestBuildRow(f, state, busy) }
            if (state.task?.startsWith("test-") == true || state.logTask?.startsWith("test-") == true) {
                TaskOutput(state, state.task ?: state.logTask!!)
            }
        }

        // Step 4: agents use it from the terminal, the same way whatever the agent
        StepCard(4, L.settings.androidDev.useIt, null, cardBg, cardBorder) {
            UseGuide(state)
        }

        SkillCard(state, busy, cardBg, cardBorder, onOpenPlugins)

        DevInfoCard(L.settings.androidDev.installOnThisPhone, cardBg, cardBorder, pill = if (adb.connectedDevice != null) "adb connected" to QuotaGreen else L.common.optional to MaterialTheme.colorScheme.primary) {
            Hint(L.settings.androidDev.theInstallButtonsAboveOpenAndroids)
            val device = adb.connectedDevice
            if (device != null) Mono("adb connected: ${device.serial}") else OutlinedButton(onClick = onOpenAdbConnect) { Text(L.common.setUpAdbConnect) }
        }

        // Check
        DevInfoCard(L.settings.androidDev.checkTheTools, cardBg, cardBorder) {
            Hint(L.settings.androidDev.printsTheVersionOfEveryTool)
            OutlinedButton(enabled = !busy && Component.CONTAINER in state.installed && state.filesReady, onClick = AndroidDevManager::runDoctor) {
                Icon(Icons.Outlined.HealthAndSafety, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.common.check)
            }
            TaskOutput(state, "doctor")
        }

        TerminalCard(state, cardBg, cardBorder)

        RemoveCard(state, busy, cardBg, cardBorder) { confirm = it }
    }

    confirm?.let { what ->
        val (title, text, action) = when (what) {
            "uninstall-flutter" -> Triple(L.settings.androidDev.removeFlutter, L.settings.androidDev.deletesFlutterAndItsPackageCache, { AndroidDevManager.uninstallFlutter() })
            "clean-cache" -> Triple(L.settings.androidDev.deleteGradlesCaches, L.settings.androidDev.freesTheSpaceGradlesDownloadsUse, { AndroidDevManager.cleanCache() })
            else -> Triple(
                L.settings.androidDev.removeTheAndroidTools,
                if (state.chroot) L.settings.androidDev.deletesTheAndroidSdkFlutterGradles
                else L.settings.androidDev.deletesTheAndroidSdkFlutterGradles2,
                { AndroidDevManager.uninstall() }
            )
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { action(); confirm = null }) { Text(L.common.delete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(L.common.cancel) } }
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
                Text(L.settings.androidDev.buildAndroidAppsOnThisPhone, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when {
                    state.anyReady -> Pill(L.common.ready, QuotaGreen)
                    state.installed.isNotEmpty() -> Pill(L.common.partlySetUp, Amber)
                    else -> Pill(L.settings.androidDev.notSetUp, MaterialTheme.colorScheme.primary)
                }
            }
            Hint(if (state.chroot) L.settings.androidDev.letAgentsAndYouCreateBuild else L.settings.androidDev.letAgentsAndYouCreateBuild2)
            Hint(L.settings.androidDev.installOnlyWhatYouNeedKotlin)
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
            Text(L.settings.androidDev.fromSource(source), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
            TextButton(enabled = enabled, onClick = { editing = true }) { Text(L.common.change, fontSize = 12.sp) }
        }
        return
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it; error = null }, singleLine = true,
        label = { Text("owner/repo@branch") }, isError = error != null,
        supportingText = { Text(error ?: L.settings.androidDev.aForkOfTheAntigemPlugins) },
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            runCatching { AndroidDevManager.setSource(text) }
                .onSuccess { editing = false; AndroidDevManager.checkForUpdate() }
                .onFailure { error = it.message }
        }) { Text(L.common.save) }
        TextButton(onClick = { text = AndroidDevManager.DEFAULT_SOURCE }) { Text(L.common.default) }
        TextButton(onClick = { editing = false; text = source }) { Text(L.common.cancel) }
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
        2, L.common.install,
        if (readyCount > 0) L.settings.androidDev.readycountOfEntriescountReady(readyCount, Framework.entries.size) else null,
        cardBg, cardBorder,
        badgeColor = if (readyCount > 0) QuotaGreen else Amber
    ) {
        Hint(L.settings.androidDev.pickWhatYouWantToBuild)
        val facts = listOfNotNull(
            state.containerName?.let { if (state.chroot) L.settings.androidDev.ubuntuChrootValue(it) else L.settings.androidDev.containerValue(it) },
            state.platforms.takeIf { it.isNotEmpty() }?.let { L.settings.androidDev.sdkPlatformsJointostring(it.joinToString()) },
            state.flutterVersion?.let { L.settings.androidDev.flutterValue(it) },
            state.nodeVersion?.let { "Node.js $it" }
        )
        if (facts.isNotEmpty()) Mono(facts.joinToString("\n"))

        Text(L.settings.androidDev.whatWillYouBuild, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Framework.entries.forEach { f ->
            FrameworkRow(f, state, f in frameworks, busy) { on ->
                if (on) frameworks.add(f) else frameworks.remove(f)
            }
        }
        Text(L.settings.androidDev.extras, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        listOf(Component.ADB, Component.NDK).forEach { c ->
            PartRow(c, state, checked = c in extras, busy = busy) { on -> if (on) extras.add(c) else extras.remove(c) }
        }

        if (isInstallTask(state.task) || isInstallTask(state.logTask)) BatchProgress(state)
        if (!busy) {
            if (plan.isNotEmpty()) Hint(L.settings.androidDev.installsPlancountPartValueValue2(plan.size, if (plan.size == 1) "" else "s", plan.joinToString(", ") { it.label }))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = plan.isNotEmpty(), onClick = { AndroidDevManager.installAll(plan) }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (plan.isEmpty()) L.settings.androidDev.nothingToInstall else "Install selected (${plan.size})")
                }
            }
            if (!state.filesReady) Hint(L.settings.androidDev.theSetupFilesAreDownloadedFirst)
        } else if (isInstallTask(state.task)) {
            Hint(L.settings.androidDev.keepAntigemOpenTheScreenStays)
            OutlinedButton(onClick = AndroidDevManager::stop, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.StopCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(L.common.stop)
            }
        }

        var showParts by remember { mutableStateOf(false) }
        TextButton(onClick = { showParts = !showParts }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showParts) L.settings.androidDev.hideTheParts else L.settings.androidDev.allPartsInstallRepairOrUpdate, fontSize = 12.5.sp)
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
            if (ready) Icon(Icons.Outlined.CheckCircle, L.common.ready, tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
            else Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
            Column(Modifier.weight(1f).padding(top = 10.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (ready) L.common.ready else "${f.size} · needs ${missing.joinToString(", ") { it.label }}",
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
                    installed -> Icon(Icons.Outlined.CheckCircle, L.common.installed, tint = QuotaGreen, modifier = Modifier.padding(12.dp).size(22.dp))
                    onCheck != null -> Checkbox(checked = checked, enabled = !busy, onCheckedChange = onCheck)
                    else -> Icon(Icons.Outlined.RadioButtonUnchecked, L.common.notInstalled, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.padding(12.dp).size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(c.label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (state.chroot && c == Component.CONTAINER) L.settings.androidDev.thisPhonesRuntime else c.size + when {
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
                    TextButton(enabled = !busy, onClick = { AndroidDevManager.install(c) }) {
                        Text(if (installed) (if (c == Component.FLUTTER) L.common.update else L.common.reinstall) else L.common.install, fontSize = 12.5.sp)
                    }
                }
            }
            if (expanded || running) {
                Text(c.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                val missing = c.needs.filter { it !in state.installed }
                if (missing.isNotEmpty() && !installed) {
                    Text(L.settings.androidDev.alsoInstallsValue(missing.joinToString(", ") { it.label }), fontSize = 11.5.sp, color = Amber, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
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
                        running -> L.settings.androidDev.building
                        apk != null -> "~/${AndroidDevManager.PROJECTS_DIR}/hello-${f.key} · APK built"
                        !ready -> L.settings.androidDev.installItFirst
                        else -> "~/${AndroidDevManager.PROJECTS_DIR}/hello-${f.key}"
                    },
                    fontSize = 11.5.sp, color = if (apk != null && !running) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (running) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
            } else {
                if (apk != null) TextButton(onClick = { AndroidDevManager.installApk(apk) }) { Text(L.common.install, fontSize = 12.5.sp) }
                TextButton(enabled = !busy && ready, onClick = { AndroidDevManager.testBuild(f) }) { Text(if (apk != null) L.settings.androidDev.rebuild else L.settings.androidDev.build, fontSize = 12.5.sp) }
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
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: L.common.starting)
    }
    if (isInstallTask(state.logTask) && state.log.isNotEmpty()) LogBox(state.log)
}

/** Progress and the log of [task], shown while it runs and after it finishes. */
@Composable
private fun TaskOutput(state: AndroidDevManager.State, task: String) {
    if (state.task == task) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        Hint(state.progress ?: state.log.lastOrNull { !it.startsWith("$") } ?: L.common.starting)
        OutlinedButton(onClick = AndroidDevManager::stop) { Text(L.common.stop) }
    }
    if (state.logTask == task && state.log.isNotEmpty()) LogBox(state.log)
}

/** How agents (any agent) and the user build: the adev commands in the terminal. */
@Composable
private fun UseGuide(state: AndroidDevManager.State) {
    Hint(L.settings.androidDev.anyAgentThatCanRunTerminal)
    if (Component.LAUNCHER !in state.installed) Hint(L.settings.androidDev.installKotlinJavaAboveFirst)
    DevCopyLine(L.settings.androidDev.adevNewComposeProjectsDirMy(AndroidDevManager.PROJECTS_DIR), mono = true)
    DevCopyLine("cd ~/${AndroidDevManager.PROJECTS_DIR}/my-app && adev ./gradlew assembleDebug", mono = true)
    Hint(L.settings.androidDev.adevRunsACommandWithJava)
    Text(L.settings.androidDev.tryInChat, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    listOf(
        L.settings.androidDev.createAJetpackComposeAppCalled,
        L.settings.androidDev.makeAFlutterCounterAppIn,
        L.settings.androidDev.createAReactNativeAppIn
    ).forEach { DevCopyLine(it) }
}

@Composable
private fun SkillCard(state: AndroidDevManager.State, busy: Boolean, cardBg: Color, cardBorder: BorderStroke, onOpenPlugins: () -> Unit) {
    DevInfoCard(
        L.settings.androidDev.androidDevelopmentSkillForAgents, cardBg, cardBorder,
        pill = if (state.pluginInstalled) "v${state.pluginVersion ?: "?"}" to QuotaGreen else L.common.recommended to MaterialTheme.colorScheme.primary
    ) {
        Hint(L.settings.androidDev.aSkillsOnlyPluginFromThe)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = AndroidDevManager::installSkillPlugin) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (state.pluginInstalled) L.settings.androidDev.updateSkill else L.settings.androidDev.installSkill)
            }
            TextButton(onClick = onOpenPlugins) { Text(L.common.plugins) }
        }
        TaskOutput(state, "plugin")
        if (state.pluginInstalled) {
            Hint(L.settings.androidDev.toGiveAnAgentTheSkill)
        }
    }
}

@Composable
private fun TerminalCard(state: AndroidDevManager.State, cardBg: Color, cardBorder: BorderStroke) {
    var open by remember { mutableStateOf(false) }
    val repo = runCatching { ai.antigem.ui.browser.PluginRepositories.parse(state.source) }.getOrNull()
    val raw = "https://raw.githubusercontent.com/${repo?.slug ?: "Santoshkurmi/antigem_plugins"}/${repo?.ref?.ifBlank { "main" } ?: "main"}/android-dev/skills/android-dev/scripts"
    val s = "\$PREFIX/share/antigem-android/android-setup.sh"
    // In the Ubuntu chroot the script runs inside it, as root (AntiGem copies the files to /opt/antigem/share/antigem-android)
    val c = "sudo env ANTIGEM_RUNTIME=chroot bash /opt/antigem/share/antigem-android/android-setup.sh"
    val steps = if (state.chroot) listOf(
        L.settings.androidDev.n1TheSetupFilesAreIn to "$c help",
        L.settings.androidDev.n2KotlinJavaComposeBaseJava to "$c kotlin",
        L.settings.androidDev.n3FlutterWithReleaseBuildsAnd to "$c flutter flutter-release node ndk",
        L.settings.androidDev.n4CheckItAndBuildA to "$c doctor && $c test compose"
    ) else listOf(
        L.settings.androidDev.n1DownloadTheSetupFiles to
            "mkdir -p \$PREFIX/share/antigem-android && cd \$PREFIX/share/antigem-android && for f in files.json android-setup.sh adev adev-new; do curl -fsSLO $raw/\$f; done",
        L.settings.androidDev.n2SeeEveryStep to "bash $s help",
        L.settings.androidDev.n3KotlinJavaComposeContainerBase to "bash $s kotlin",
        L.settings.androidDev.n4FlutterWithReleaseBuildsAnd to "bash $s flutter flutter-release node ndk",
        L.settings.androidDev.n5CheckItAndBuildA to "bash $s doctor && bash $s test compose"
    )
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(L.settings.androidDev.setUpInTheTerminal, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) L.common.hide else L.common.show, fontSize = 12.5.sp) }
            }
            if (!open) {
                Hint(L.settings.androidDev.preferTheTerminalTheseAreThe)
                return@Column
            }
            steps.forEach { (label, command) ->
                Hint(label)
                DevCopyLine(command, mono = true)
            }
            Hint(L.settings.androidDev.eachStepCanBeRunAgain)
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
                Text(L.settings.androidDev.freeSpaceRemove, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) L.common.hide else L.common.show, fontSize = 12.5.sp) }
            }
            if (!open) return@Column
            Hint(if (state.chroot) L.settings.androidDev.yourProjectsAreNeverDeletedThe
                else L.settings.androidDev.yourProjectsAreNeverDeletedThe2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = !busy && state.filesReady, onClick = { onConfirm("clean-cache") }) { Text(L.settings.androidDev.cleanGradleCache) }
                if (Component.FLUTTER in state.installed) OutlinedButton(enabled = !busy && state.filesReady, onClick = { onConfirm("uninstall-flutter") }) { Text(L.settings.androidDev.removeFlutter2) }
                OutlinedButton(
                    enabled = !busy && state.filesReady,
                    onClick = { onConfirm("uninstall") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(L.settings.androidDev.removeAll) }
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
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Outlined.ContentCopy, L.common.copy, Modifier.size(18.dp)) }
    }
}
