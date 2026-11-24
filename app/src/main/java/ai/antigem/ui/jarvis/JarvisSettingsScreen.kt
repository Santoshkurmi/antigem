package ai.antigem.ui.jarvis

import ai.antigem.ui.components.bouncyVerticalScroll
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.claude.ClaudeMcpAddRequest
import ai.antigem.data.jarvis.JarvisController
import ai.antigem.data.jarvis.JarvisPreferences
import ai.antigem.data.jarvis.JarvisSkill
import ai.antigem.data.jarvis.JarvisVoice
import ai.antigem.data.remote.AndroidLocalBridgeServer
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.McpServerSpec
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

private val JarvisCyan = Color(0xFF22D3EE)

/** The sections of Settings → Jarvis: each opens on its own page (Settings → Jarvis → section). */
enum class JarvisSettingsPage { AGENT, PERSONALITY, SETUP, WAKE, LISTEN, VOICE, TASKS, APPROVALS, SCREEN, CHAT }

/**
 * Settings → Jarvis: the on/off switch, then one row per section ([JarvisSettingsPage]); a section opens on its own
 * page ([JarvisSettingsSectionPage]), so this page stays light and opens at once.
 */
@Composable
fun JarvisSettingsScreen(cardBg: Color, cardBorder: BorderStroke, onOpen: (JarvisSettingsPage) -> Unit) {
    val P = JarvisPreferences
    val enabled by P.enabled.state.collectAsState()
    val agentName by P.agent.state.collectAsState()
    val hotword by P.hotword.state.collectAsState()
    val autoSend by P.autoSend.state.collectAsState()
    val engine by P.ttsEngine.state.collectAsState()
    val quiet by P.quietMode.state.collectAsState()
    val policy by P.permissionPolicy.state.collectAsState()
    val voiceSide by P.voiceAssistant.state.collectAsState()

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        JCard(cardBg, cardBorder, L.common.jarvis) {
            JSwitch(
                L.jarvis.settings.turnOnJarvis,
                L.jarvis.settings.aHandsFreeAssistantForThe,
                enabled
            ) { JarvisController.setEnabled(it) }
            if (enabled) {
                JSwitch(
                    L.jarvis.settings.floatingButtonVoice,
                    L.jarvis.settings.offChatOnlyNoFloatingButton,
                    voiceSide
                ) { JarvisController.setVoiceAssistant(it) }
            }
            JHint(
                if (!enabled || voiceSide) L.jarvis.settings.tapTheOrbToTalkLong
                else L.jarvis.settings.chatOnlyOpenTheJarvisTab
            )
        }
        if (enabled && JarvisController.isAttached) {
            JCard(cardBg, cardBorder, L.jarvis.settings.jarvisChatsInTheChatList) {
                val inAll by P.jarvisInAll.state.collectAsState()
                JChips(listOf("none" to L.jarvis.settings.onlyUnderJarvis, "text" to L.jarvis.settings.myJarvisChatsInAllToo, "all" to L.jarvis.settings.allJarvisChatsInAllToo), inAll) { P.jarvisInAll.set(it) }
                JHint(when (inAll) {
                    "text" -> L.jarvis.settings.theJarvisChatsYouOpenedYourself
                    "all" -> L.jarvis.settings.everyJarvisChatTheVoiceChats
                    else -> L.jarvis.settings.jarvisChatsAreListedOnlyUnder
                })
            }
        }
        if (!JarvisController.isAttached) {
            Text(L.jarvis.settings.jarvisIsStarting, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        val c = ai.antigem.theme.AppColors
        val I = androidx.compose.material.icons.Icons.Outlined
        fun row(page: JarvisSettingsPage, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, value: String? = null) =
            ai.antigem.ui.settings.SettingsRowItem(icon, tint, title, value) { onOpen(page) }
        listOf(
            listOfNotNull(
                row(JarvisSettingsPage.AGENT, I.SmartToy, c.info, L.jarvis.settings.voiceAgentModel, when (agentName) {
                    AgentKind.AGY.name -> L.common.antigravity
                    AgentKind.CLAUDE.name -> L.common.claudeCode
                    AgentKind.OPENCODE.name -> "OpenCode"
                    AgentKind.CODEX.name -> L.common.codex
                    else -> L.jarvis.settings.notSet
                }),
                row(JarvisSettingsPage.PERSONALITY, I.Face, c.accentPink, L.jarvis.settings.personalityYou),
                // the setup checklist is only for a Jarvis that is turned on
                if (enabled) row(JarvisSettingsPage.SETUP, I.Checklist, c.success, L.jarvis.settings.setup) else null
            ),
            // "chat only": nothing listens or speaks, so those pages are hidden
            if (!voiceSide) emptyList() else listOf(
                row(JarvisSettingsPage.WAKE, I.Hearing, c.accentTeal, L.jarvis.settings.wakingJarvis, if (hotword == "phrase") L.jarvis.settings.wakePhrase else L.jarvis.settings.button),
                row(JarvisSettingsPage.LISTEN, I.Mic, c.accentIndigo, L.jarvis.settings.listeningSending, if (autoSend) L.jarvis.settings.autoSend else L.jarvis.settings.manualSend),
                row(JarvisSettingsPage.VOICE, I.RecordVoiceOver, c.accentAlt, L.jarvis.settings.voiceOutput, when {
                    quiet -> L.jarvis.settings.quiet
                    engine == "google_cloud" -> L.jarvis.settings.googleCloud
                    engine == "openai" -> "OpenAI"
                    else -> L.jarvis.settings.thisDevice
                })
            ),
            listOf(
                row(JarvisSettingsPage.TASKS, I.TaskAlt, c.warning, L.jarvis.settings.managerTasks),
                row(JarvisSettingsPage.APPROVALS, I.VerifiedUser, c.danger, L.common.approvals, when (policy) {
                    "auto_readonly" -> L.jarvis.settings.autoReadOnly
                    "auto_except_risky" -> L.jarvis.settings.autoNotRisky
                    "auto_all" -> L.jarvis.settings.autoAll
                    else -> L.jarvis.settings.askEachTime
                }),
                row(JarvisSettingsPage.SCREEN, I.Visibility, c.info, L.jarvis.settings.screenLook),
                row(JarvisSettingsPage.CHAT, I.ChatBubbleOutline, JarvisCyan, L.jarvis.settings.jarvisChat)
            )
        ).filter { it.isNotEmpty() }.forEach { ai.antigem.ui.settings.SettingsRowGroup(it, cardBg) }
    }
}

/** One section of Settings → Jarvis on its own page (the page title is in the settings top bar). */
@Composable
fun JarvisSettingsSectionPage(page: JarvisSettingsPage, cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val P = JarvisPreferences
    val enabled by P.enabled.state.collectAsState()
    val agentName by P.agent.state.collectAsState()
    // null until the user picks the agent Jarvis talks to
    val agent: AgentKind? = agentName.takeIf { it.isNotBlank() }?.let { runCatching { AgentKind.valueOf(it) }.getOrNull() }
    val agentTitle = when (agent) {
        AgentKind.CLAUDE -> L.common.claudeCode
        AgentKind.AGY -> L.common.antigravity
        AgentKind.OPENCODE -> "OpenCode"
        AgentKind.CODEX -> L.common.codex
        null -> L.jarvis.settings.theAgent
    }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!JarvisController.isAttached) {
            Text(L.jarvis.settings.jarvisIsStarting, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        val vm = JarvisController.vm
        when (page) {
            JarvisSettingsPage.AGENT -> {
                JCard(cardBg, cardBorder, "") {
                    val enabledAgents by vm.enabledAgents.collectAsState()
                    JChips(
                        listOf(AgentKind.CLAUDE.name to L.common.claudeCode, AgentKind.AGY.name to L.common.antigravity, AgentKind.OPENCODE.name to "OpenCode", AgentKind.CODEX.name to L.common.codex),
                        agentName
                    ) { P.agent.set(it); P.modelId.set("") }
                    if (agent == null) {
                        Text(L.jarvis.settings.pickTheAgentJarvisTalksTo, fontSize = 12.5.sp, color = Color(0xFFF59E0B))
                    } else if (agent !in enabledAgents) {
                        Text(L.jarvis.settings.agenttitleIsTurnedOffInSettings(agentTitle), fontSize = 12.5.sp, color = Color(0xFFF59E0B))
                    }
                    val models by (when (agent) {
                        AgentKind.CLAUDE -> vm.claudeBackend.availableModels
                        AgentKind.OPENCODE -> vm.openCodeBackend.availableModels
                        AgentKind.CODEX -> vm.codexBackend.availableModels
                        else -> vm.agyBackend.enabledModels
                    }).collectAsState()
                    val modelId by P.modelId.state.collectAsState()
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { menu = true }) {
                            val label = models.firstOrNull { it.id == modelId }?.let { it.displayName.ifBlank { it.id } } ?: L.jarvis.settings.defaultModel
                            Text(L.jarvis.settings.modelLabel(label))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(L.jarvis.settings.defaultModel) }, onClick = { P.modelId.set(""); menu = false })
                            models.forEach { m ->
                                DropdownMenuItem(text = { Text(m.displayName.ifBlank { m.id }) }, onClick = { P.modelId.set(m.id); menu = false })
                            }
                        }
                    }
                    JHint(L.jarvis.settings.jarvisTalksToANormalHidden)
                }
            }
            JarvisSettingsPage.PERSONALITY -> {
                JCard(cardBg, cardBorder, "") {
                    val persona by P.persona.state.collectAsState()
                    Text(L.jarvis.settings.jarvisSpeaksAs, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("" to L.jarvis.settings.notSet, "female" to L.jarvis.settings.female, "male" to L.jarvis.settings.male, "neutral" to L.jarvis.settings.neutral), persona) { P.persona.set(it) }
                    JHint(L.jarvis.settings.theGrammaticalGenderJarvisUsesFor)
                    val about by P.aboutUser.state.collectAsState()
                    OutlinedTextField(
                        value = about, onValueChange = { P.aboutUser.set(it.take(2000)) },
                        label = { Text(L.jarvis.settings.aboutYouAndExtraInstructions) },
                        placeholder = { Text(L.jarvis.settings.eGCallMeSantoshReply, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8
                    )
                    JHint(L.jarvis.settings.sentWithTheFirstMessageOf)
                }
            }
            JarvisSettingsPage.SETUP -> {
                if (!enabled) {
                    JHint(L.jarvis.settings.turnOnJarvisFirstSettingsJarvis)
                } else {
                    if (agent == null) {
                        JHint(L.jarvis.settings.pickTheAgentJarvisTalksTo2)
                    } else JCard(cardBg, cardBorder, L.jarvis.settings.setupForAgenttitle(agentTitle)) {
                        val deviceId = remember(context) {
                            runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "" }.getOrDefault("")
                        }
                        val headers = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
                        val url = "http://127.0.0.1:${AndroidLocalBridgeServer.DEFAULT_PORT}/jarvis"
                        val name = AndroidLocalBridgeServer.JARVIS_MCP_NAME
                        val mcpProblem by JarvisController.mcpProblem.collectAsState()
                        if (mcpProblem) {
                            Text(
                                L.jarvis.settings.jarvisReportedThatItsAppTools(agentTitle),
                                fontSize = 12.5.sp, color = Color(0xFFF59E0B)
                            )
                        }

                        // 1. the MCP server
                        val claudeServers by vm.claudeConfig.mcpServers.collectAsState()
                        val agyServers by vm.mcpServers.collectAsState()
                        // OpenCode keeps the servers the app adds in the bridge's OpenCode config
                        var openCodeServers by remember { mutableStateOf<Set<String>>(emptySet()) }
                        val reloadOpenCodeMcp: () -> Unit = { scope.launch { vm.openCodeBackend.client.mcpServers().onSuccess { openCodeServers = it } } }
                        // Codex keeps them in its own config.toml
                        var codexServers by remember { mutableStateOf<Set<String>>(emptySet()) }
                        val reloadCodexMcp: () -> Unit = { scope.launch { vm.codexBackend.mcpServerNames().onSuccess { codexServers = it } } }
                        LaunchedEffect(agent) {
                            when (agent) {
                                AgentKind.CLAUDE -> vm.claudeConfig.loadMcp(null)
                                AgentKind.OPENCODE -> reloadOpenCodeMcp()
                                AgentKind.CODEX -> reloadCodexMcp()
                                else -> vm.loadMcpServers()
                            }
                        }
                        val mcpState = when (agent) {
                            AgentKind.CLAUDE -> claudeServers.firstOrNull { it.name == name }?.status
                            AgentKind.OPENCODE, AgentKind.CODEX -> null
                            else -> agyServers.firstOrNull { it.name == name }?.status
                        }
                        val mcpAdded = when (agent) {
                            AgentKind.CLAUDE -> claudeServers.any { it.name == name }
                            AgentKind.OPENCODE -> name in openCodeServers
                            AgentKind.CODEX -> name in codexServers
                            else -> agyServers.any { it.name == name }
                        }
                        JStep(
                            L.jarvis.settings.n1AddTheJarvisMcpServer,
                            if (mcpAdded) L.jarvis.settings.addedValue((mcpState?.let { " · $it" } ?: "")) else L.jarvis.settings.requiredTheseAreTheToolsJarvis,
                            mcpAdded, if (mcpAdded) L.common.refresh else L.common.add
                        ) {
                            if (agent == AgentKind.CODEX) {
                                if (mcpAdded) reloadCodexMcp()
                                else scope.launch {
                                    vm.codexBackend.addRemoteMcp(name, url, headers)
                                        .onSuccess { reloadCodexMcp(); Toast.makeText(context, L.jarvis.settings.addedToCodex, Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.message ?: L.jarvis.settings.couldNotAddIt, Toast.LENGTH_LONG).show() }
                                }
                            } else if (agent == AgentKind.OPENCODE) {
                                if (mcpAdded) reloadOpenCodeMcp()
                                else scope.launch {
                                    vm.openCodeBackend.client.addRemoteMcp(name, url, headers)
                                        .onSuccess { reloadOpenCodeMcp(); Toast.makeText(context, L.jarvis.settings.addedToOpencode, Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.message ?: L.jarvis.settings.couldNotAddIt, Toast.LENGTH_LONG).show() }
                                }
                            } else if (agent == AgentKind.CLAUDE) {
                                if (mcpAdded) vm.claudeConfig.loadMcp(null)
                                else vm.claudeConfig.addMcp(ClaudeMcpAddRequest(name = name, transport = "http", url = url, headers = headers), null)
                            } else {
                                if (mcpAdded) vm.loadMcpServers()
                                else vm.saveMcpServer(McpServerSpec(serverName = name, serverUrl = url, headers = headers))
                            }
                        }

                        // 2. never ask about Jarvis' own tools (Claude)
                        if (agent == AgentKind.CLAUDE) {
                            val settings by vm.claudeConfig.settings.collectAsState()
                            LaunchedEffect(Unit) { vm.claudeConfig.loadSettings() }
                            val allowed = settings != null && vm.claudeConfig.rules("allow").contains("mcp__$name")
                            JStep(
                                L.jarvis.settings.n2LetJarvisToolsRunWithout,
                                if (allowed) L.common.allowed else L.jarvis.settings.otherwiseEverySpokenAnswerWouldNeed,
                                allowed, L.common.allow
                            ) { vm.claudeConfig.addRule("allow", "mcp__$name") }
                        }

                        // 3. the microphone
                        var hasMic by remember { mutableStateOf(JarvisController.hasMic()) }
                        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
                        JStep(
                            L.jarvis.settings.stepAllowTheMicrophone(if (agent == AgentKind.CLAUDE) "3" else "2"),
                            if (hasMic) L.common.allowed else L.jarvis.settings.neededToHearYou,
                            hasMic, L.common.allow
                        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

                        // 4. the optional skill
                        var skillInstalled by remember(agent) { mutableStateOf<Boolean?>(null) }
                        LaunchedEffect(agent) { skillInstalled = runCatching { JarvisSkill.isInstalled(agent) }.getOrDefault(false) }
                        JStep(
                            L.jarvis.settings.optionalInstallTheJarvisSkill,
                            when (skillInstalled) { true -> L.common.installed; false -> L.jarvis.settings.notNeededTheGuideGoesWith; null -> L.common.checking },
                            skillInstalled == true, if (skillInstalled == true) L.common.reinstall else L.common.install
                        ) {
                            scope.launch {
                                JarvisSkill.install(agent)
                                    .onSuccess { skillInstalled = true; Toast.makeText(context, L.jarvis.settings.installedValue(it), Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, it.message ?: L.jarvis.settings.installFailed, Toast.LENGTH_LONG).show() }
                            }
                        }
                        JHint(L.jarvis.settings.theFirstMessageOfEveryJarvis)
                    }
                }
            }
            JarvisSettingsPage.WAKE -> {
                JCard(cardBg, cardBorder, "") {
                    val hotword by P.hotword.state.collectAsState()
                    Text(L.jarvis.settings.trigger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("push" to L.jarvis.settings.buttonTileHeadset, "phrase" to L.jarvis.settings.wakePhraseToo), hotword) { P.hotword.set(it) }
                    if (hotword == "phrase") {
                        val phrase by P.wakePhrase.state.collectAsState()
                        JField("Wake phrase(s), comma separated", phrase) { P.wakePhrase.set(it.lowercase()) }
                        val match by P.wakeMatch.state.collectAsState()
                        JChips(listOf("phrase" to L.jarvis.settings.wholePhrase, "name" to L.jarvis.settings.theNameAlone), match) { P.wakeMatch.set(it) }
                        JHint(L.jarvis.settings.heyJarvisOpenMyProjectStarts)
                    }
                    val hands by P.handsFree.state.collectAsState()
                    JSwitch(
                        L.jarvis.settings.handsFreeMode,
                        L.jarvis.settings.keepsJarvisMicrophoneServiceRunningSo,
                        hands, enabledSwitch = enabled
                    ) { JarvisController.setHandsFree(it) }
                    val headset by P.headsetButton.state.collectAsState()
                    JSwitch(L.jarvis.settings.headsetButton, L.jarvis.settings.withHandsFreeOnOnePress, headset) { P.headsetButton.set(it) }
                    JHint(L.jarvis.settings.quickSettingsTilePullDownThe)
                    JHint(L.jarvis.settings.alsoLongPressTheAntigemIcon)
                    OutlinedButton(onClick = {
                        if (!ai.antigem.data.jarvis.JarvisLauncher.pinShortcut(context)) {
                            Toast.makeText(context, L.jarvis.settings.thisLauncherCantAddShortcutsLong, Toast.LENGTH_LONG).show()
                        }
                    }) { Text(L.common.addToHomeScreen) }
                }
            }
            JarvisSettingsPage.LISTEN -> {
                JCard(cardBg, cardBorder, "") {
                    JHint(L.jarvis.settings.youCanAlwaysSendWithThe)
                    val auto by P.autoSend.state.collectAsState()
                    JSwitch(
                        L.jarvis.settings.sendAutomaticallyWhenIPause,
                        L.jarvis.settings.offNothingIsSentWhileYou,
                        auto
                    ) { P.autoSend.set(it) }
                    if (auto) {
                        val mode by P.sendMode.state.collectAsState()
                        JChips(listOf("smart" to L.jarvis.settings.smartPause, "pause" to L.jarvis.settings.fixedPause), if (mode == "pause") "pause" else "smart") { P.sendMode.set(it) }
                        JHint(if (mode == "pause") L.jarvis.settings.sentAfterAFixedPauseA
                        else L.jarvis.settings.jarvisWaitsLongerWhenYouSound)
                    }
                    val silence by P.silenceMs.state.collectAsState()
                    JSlider(L.jarvis.settings.pauseBeforeSending, silence.toFloat(), 600f..4000f, "%.1f s".format(silence / 1000f)) { P.silenceMs.set(it.toInt()) }
                    val answers by P.answerSend.state.collectAsState()
                    Text(L.jarvis.settings.answersToJarvisQuestions, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("same" to L.jarvis.settings.likeEverythingElse, "auto" to L.jarvis.settings.sendOnPause), answers) { P.answerSend.set(it) }
                    JHint(if (answers == "auto") L.jarvis.settings.whenJarvisJustAskedYouSomething
                    else L.jarvis.settings.answersAreSentExactlyLikeEverything)

                    // send phrases
                    val sendOn by P.sendPhrasesOn.state.collectAsState()
                    val sendRaw by P.sendPhrases.state.collectAsState()
                    val cancelOn by P.cancelPhrasesOn.state.collectAsState()
                    val cancelRaw by P.cancelPhrases.state.collectAsState()
                    val sendList = remember(sendRaw) { ai.antigem.data.jarvis.JarvisPhrases.parse(sendRaw) }
                    val cancelList = remember(cancelRaw) { ai.antigem.data.jarvis.JarvisPhrases.parse(cancelRaw) }
                    JSwitch(L.jarvis.settings.sendWithAPhrase, L.jarvis.settings.endWithOneOfTheseAnd, sendOn) { P.sendPhrasesOn.set(it) }
                    if (sendOn) {
                        FoldedPhrases(L.jarvis.settings.sendPhrases, sendList.size) {
                            PhraseEditor(sendList, cancelList, "cancel", ai.antigem.data.jarvis.JarvisPhrases.SUGGESTED_SEND) {
                                P.sendPhrases.set(ai.antigem.data.jarvis.JarvisPhrases.format(it))
                            }
                        }
                    }
                    JSwitch(L.jarvis.settings.cancelWithAPhrase, L.jarvis.settings.endWithOneOfTheseAnd2, cancelOn) { P.cancelPhrasesOn.set(it) }
                    if (cancelOn) {
                        FoldedPhrases(L.jarvis.settings.cancelPhrases, cancelList.size) {
                            PhraseEditor(cancelList, sendList, "send", ai.antigem.data.jarvis.JarvisPhrases.SUGGESTED_CANCEL) {
                                P.cancelPhrases.set(ai.antigem.data.jarvis.JarvisPhrases.format(it))
                            }
                        }
                    }
                    if (sendOn || cancelOn) {
                        JHint(L.jarvis.settings.aPhraseOnlyCountsAtThe)
                    }
                    val listenAfter by P.listenAfter.state.collectAsState()
                    Text(L.jarvis.settings.afterJarvisSpeaksListenForYour, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("auto" to L.jarvis.settings.whenItExpectsAnAnswer, "always" to L.common.always, "never" to L.common.never), listenAfter) { P.listenAfter.set(it) }
                    JHint(when (listenAfter) {
                        "always" -> L.jarvis.settings.theMicrophoneOpensAfterEveryAnswer
                        "never" -> L.jarvis.settings.theMicrophoneOnlyOpensWhenYou
                        else -> L.jarvis.settings.jarvisDecidesAfterDoneWantMe
                    })
                    if (listenAfter != "never") {
                        val follow by P.followUpSeconds.state.collectAsState()
                        JSlider(L.jarvis.settings.waitForAReply, follow.toFloat(), 3f..20f, "$follow s") { P.followUpSeconds.set(it.toInt()) }
                        JHint(L.jarvis.settings.ifYouSayNothingAtAll)
                    }
                    val maxListen by P.maxListenSeconds.state.collectAsState()
                    JSlider(L.jarvis.settings.longestSingleMessage, maxListen.toFloat(), 20f..240f, "$maxListen s") { P.maxListenSeconds.set(it.toInt()) }
                    val side by P.sideTalk.state.collectAsState()
                    Text(L.jarvis.settings.talkingToOtherPeopleNearby, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("off" to L.common.off, "smart" to L.jarvis.settings.smart, "strict" to L.jarvis.settings.strict), side) { P.sideTalk.set(it) }
                    JHint(when (side) {
                        "off" -> L.jarvis.settings.everythingHeardIsForJarvis
                        "strict" -> L.jarvis.settings.duringFollowUpListeningJarvisOnly
                        else -> L.jarvis.settings.duringFollowUpListeningJarvisIgnores
                    })
                    val busyB by P.busyBehavior.state.collectAsState()
                    Text(L.jarvis.settings.speakingWhileJarvisIsStillWorking, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("queue" to L.jarvis.settings.waitForIt, "interrupt" to L.jarvis.settings.alwaysInterrupt, "smart" to L.jarvis.settings.myInterruptPhrases), busyB) { P.busyBehavior.set(it) }
                    if (busyB == "smart") JHint(L.jarvis.settings.onlyAMessageThatStartsWith)
                    val earcons by P.earcons.state.collectAsState()
                    JSwitch(L.jarvis.settings.tonesWhenTheMicrophoneOpensAnd, null, earcons) { P.earcons.set(it) }
                    val stt by P.sttEngine.state.collectAsState()
                    Text(L.jarvis.settings.speechRecognition, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("auto" to L.jarvis.settings.sameAsAgent, "claude" to L.common.claude, "agy" to L.common.antigravity, "android" to L.jarvis.settings.thisDevice), stt) { P.sttEngine.set(it) }
                    JHint(L.jarvis.settings.thisDeviceUsesAndroidsRecognizerAnd)
                    val lang by P.sttLanguage.state.collectAsState()
                    JField(L.jarvis.settings.languageEGEnHiEs, lang) { P.sttLanguage.set(it) }
                }
                JCard(cardBg, cardBorder, L.jarvis.settings.voiceShortcuts) {
                    JHint(L.jarvis.settings.yourOwnPhrasesThatActAt)
                    val J = ai.antigem.data.jarvis.JarvisPhrases
                    listOf(
                        Triple(L.jarvis.settings.stopEverything, P.cmdStop, "stop"),
                        Triple(L.jarvis.settings.endTheConversation, P.cmdBye, "bye"),
                        Triple(L.jarvis.settings.repeatTheLastAnswer, P.cmdRepeat, "repeat"),
                        Triple(L.jarvis.settings.speakSlower, P.cmdSlower, "slower"),
                        Triple(L.jarvis.settings.speakFaster, P.cmdFaster, "faster"),
                        Triple(L.jarvis.settings.quietModeOn, P.cmdQuietOn, "quiet_on"),
                        Triple(L.jarvis.settings.quietModeOff, P.cmdQuietOff, "quiet_off"),
                        Triple(L.jarvis.settings.startANewSession, P.cmdNewSession, "new_session"),
                        Triple(L.jarvis.settings.interruptJarvisWorkStartsAMessage, P.interruptPhrases, "interrupt")
                    ).forEach { (title, pref, id) ->
                        val raw by pref.state.collectAsState()
                        val list = remember(raw) { J.parse(raw) }
                        FoldedPhrases(title, list.size) {
                            PhraseEditor(list, emptyList(), "other", J.SUGGESTED_SHORTCUTS[id].orEmpty()) { pref.set(J.format(it)) }
                        }
                    }
                }
            }
            JarvisSettingsPage.VOICE -> {
                JCard(cardBg, cardBorder, "") {
                    val quiet by P.quietMode.state.collectAsState()
                    JSwitch(L.jarvis.settings.quietMode, L.jarvis.settings.jarvisShowsItsAnswersOnThe, quiet) { P.quietMode.set(it) }
                    val engine by P.ttsEngine.state.collectAsState()
                    JChips(listOf("android" to L.jarvis.settings.thisDevice, "google_cloud" to L.jarvis.settings.googleCloud, "openai" to "OpenAI-compatible"), engine) { P.ttsEngine.set(it) }
                    val voice by P.ttsVoice.state.collectAsState()
                    val language by P.ttsLanguage.state.collectAsState()
                    if (engine == "android") {
                        var voices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                        var menu by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { voices = runCatching { JarvisVoice.deviceVoices() }.getOrDefault(emptyList()) }
                        Box {
                            OutlinedButton(onClick = { menu = true }) { Text(L.jarvis.settings.voiceValue((voices.firstOrNull { it.first == voice }?.second ?: L.jarvis.settings.systemDefault))) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(L.jarvis.settings.systemDefault) }, onClick = { P.ttsVoice.set(""); menu = false })
                                voices.take(80).forEach { (id, label) ->
                                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = { P.ttsVoice.set(id); menu = false })
                                }
                            }
                        }
                        JField(L.jarvis.settings.languageOptionalEGEnUs, language) { P.ttsLanguage.set(it) }
                    } else {
                        val key by P.ttsApiKey.state.collectAsState()
                        JField(L.common.apiKey, key, secret = true) { P.ttsApiKey.set(it) }
                        if (engine == "google_cloud") {
                            JField(L.jarvis.settings.voiceNameEGEnUs, voice) { P.ttsVoice.set(it) }
                            JField(L.jarvis.settings.languageCodeEGEnUs, language) { P.ttsLanguage.set(it) }
                        } else {
                            val base by P.ttsBaseUrl.state.collectAsState()
                            val model by P.ttsModel.state.collectAsState()
                            JField("Base URL (default https://api.openai.com/v1)", base) { P.ttsBaseUrl.set(it) }
                            JField(L.jarvis.settings.modelDefaultTts1, model) { P.ttsModel.set(it) }
                            JField(L.jarvis.settings.voiceDefaultAlloy, voice) { P.ttsVoice.set(it) }
                        }
                        JHint(L.jarvis.settings.ifTheOnlineVoiceFailsJarvis)
                    }
                    val rate by P.ttsRate.state.collectAsState()
                    JSlider(L.common.speed, rate, 0.5f..2f, "%.1fx".format(rate)) { P.ttsRate.set(it) }
                    val pitch by P.ttsPitch.state.collectAsState()
                    JSlider(L.jarvis.settings.pitch, pitch, 0.5f..2f, "%.1f".format(pitch)) { P.ttsPitch.set(it) }
                    val err by JarvisVoice.lastError.collectAsState()
                    err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { scope.launch { JarvisVoice.speak(L.jarvis.settings.helloImJarvisThisIsHow, wait = false) } }) { Text(L.jarvis.settings.testVoice) }
                    val progress by P.speakProgress.state.collectAsState()
                    JSwitch(L.jarvis.settings.speakProgressUpdates, L.jarvis.settings.jarvisSaysAShortLineWhile, progress) { P.speakProgress.set(it) }
                    JHint(L.jarvis.settings.youCanAlsoSaySpeakSlower)
                }
            }
            JarvisSettingsPage.TASKS -> {
                JCard(cardBg, cardBorder, "") {
                    JHint(L.jarvis.settings.sayBuildTheReleaseAndTell)
                    val routing by P.eventRouting.state.collectAsState()
                    Text(L.jarvis.settings.whenATaskFinishesFailsOr, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("model" to L.jarvis.settings.jarvisDecides, "speak" to L.jarvis.settings.justTellMe), routing) { P.eventRouting.set(it) }
                    JHint(if (routing == "model") L.jarvis.settings.jarvisReadsTheResultTellsYou
                    else L.jarvis.settings.jarvisJustAnnouncesItReleaseBuild)
                    val taskAppr by P.taskApprovals.state.collectAsState()
                    Text(L.jarvis.settings.approvalsInsideTasks, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("policy" to L.jarvis.settings.useMyApprovalRules, "jarvis" to L.jarvis.settings.jarvisDecides), taskAppr) { P.taskApprovals.set(it) }
                    JHint(if (taskAppr == "jarvis") L.jarvis.settings.jarvisApprovesWhatClearlyServesThe
                    else L.jarvis.settings.approvalsFollowApprovalsByVoiceBelow)
                    val stall by P.stallMinutes.state.collectAsState()
                    Text(L.jarvis.settings.reportATaskWithNoProgress, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to L.common.never, "5" to "5 min", "10" to "10 min", "20" to "20 min", "45" to "45 min"), stall.toString()) { P.stallMinutes.set(it.toInt()) }
                    val done by P.announceDone.state.collectAsState()
                    JSwitch(L.jarvis.settings.sayWhenAnyOtherChatFinishes, L.jarvis.settings.chatTitleIsDonePleaseCheck, done) { P.announceDone.set(it) }
                    if (done) {
                        val off by P.announceOffscreenOnly.state.collectAsState()
                        JSwitch(L.jarvis.settings.notForTheChatImLooking, null, off) { P.announceOffscreenOnly.set(it) }
                    }
                    val sum by P.announceSummary.state.collectAsState()
                    JSwitch(L.jarvis.settings.alsoReadTheStartOfThe, L.jarvis.settings.forAnnouncementsWithoutAModelTurn, sum) { P.announceSummary.set(it) }
                    val low by P.announceUsageLow.state.collectAsState()
                    JSwitch(L.jarvis.settings.warnWhenThe5HourUsage, L.jarvis.settings.oncePerWindowAtAbout15, low) { P.announceUsageLow.set(it) }
                }
                JCard(cardBg, cardBorder, L.jarvis.settings.commandCenter) {
                    JHint(L.jarvis.settings.openTheJarvisTabInThe)
                    val speakDefault by P.managerSpeakDefault.state.collectAsState()
                    JSwitch(L.jarvis.settings.readRepliesOutLoudInNew, L.jarvis.settings.eachChatHasItsOwnVoice, speakDefault) {
                        P.managerSpeakDefault.set(it)
                    }
                    val guard by P.waitGuard.state.collectAsState()
                    Text(L.jarvis.settings.whenJarvisSleepsOrPollsWhile, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("interrupt" to L.jarvis.settings.stopItHandItTheNews, "remind" to L.jarvis.settings.onlyRemindIt, "off" to L.jarvis.settings.leaveIt), guard) { P.waitGuard.set(it) }
                    JHint(when (guard) {
                        "interrupt" -> L.jarvis.settings.aTurnThatWaitsSleepWait
                        "remind" -> L.jarvis.settings.theNextMessageTellsItNot
                        else -> L.jarvis.settings.noGuardNewsWaitsUntilJarvis
                    })
                    val batch by P.eventBatchMs.state.collectAsState()
                    Text(L.jarvis.settings.collectNewsBeforeSendingIt, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to L.jarvis.settings.atOnce, "2000" to "2 s", "5000" to "5 s", "15000" to "15 s"), batch.toString()) { P.eventBatchMs.set(it.toInt()) }
                    JHint(L.jarvis.settings.whateverComesInMeanwhileAndEverything)
                    val nudges by P.autopilotMaxNudges.state.collectAsState()
                    Text(L.jarvis.settings.autopilotGivesUpAfter, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("2" to "2 nudges", "4" to "4 nudges", "8" to "8 nudges", "15" to "15 nudges"), nudges.toString()) { P.autopilotMaxNudges.set(it.toInt()) }
                    JHint(L.jarvis.settings.withAutopilotOnJarvisIsNudged)
                    JHint(L.jarvis.settings.whetherJarvisChatsAlsoShowIn)
                    JHint(L.jarvis.settings.severalJarvisChatsCanWorkAt)
                }
            }
            JarvisSettingsPage.APPROVALS -> {
                JCard(cardBg, cardBorder, "") {
                    val policy by P.permissionPolicy.state.collectAsState()
                    var confirmAll by remember { mutableStateOf(false) }
                    JChips(
                        listOf("ask_each" to L.jarvis.settings.askMeEachTime, "auto_readonly" to L.jarvis.settings.autoReadOnly, "auto_except_risky" to L.jarvis.settings.autoExceptRisky, "auto_all" to L.jarvis.settings.autoEverything),
                        policy
                    ) { if (it == "auto_all") confirmAll = true else P.permissionPolicy.set(it) }
                    JHint(
                        L.jarvis.settings.policyJarvisOwnToolsNeverNeed(when (policy) {
                            "auto_readonly" -> L.jarvis.settings.readingFilesAndSearchingIsApproved
                            "auto_except_risky" -> L.jarvis.settings.everythingIsApprovedExceptRiskyActions
                            "auto_all" -> L.jarvis.settings.nothingIsAskedUseOnlyIf
                            else -> L.jarvis.settings.jarvisReadsEachRequestOutIt
                        })
                    )
                    if (confirmAll) {
                        AlertDialog(
                            onDismissRequest = { confirmAll = false },
                            title = { Text(L.jarvis.settings.approveEverythingAutomatically) },
                            text = { Text(L.jarvis.settings.agentsWillRunCommandsEditFiles) },
                            confirmButton = { TextButton(onClick = { P.permissionPolicy.set("auto_all"); confirmAll = false }) { Text(L.jarvis.settings.yesApproveEverything) } },
                            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(L.common.cancel) } }
                        )
                    }
                    val allow by P.allowSettingChanges.state.collectAsState()
                    JSwitch(
                        L.jarvis.settings.letJarvisChangeAppSettings,
                        L.jarvis.settings.themeChatFontSizeFloatingBubble,
                        allow
                    ) { P.allowSettingChanges.set(it) }
                }
            }
            JarvisSettingsPage.SCREEN -> {
                JCard(cardBg, cardBorder, "") {
                    val screenOn by P.keepScreenOn.state.collectAsState()
                    Text(L.jarvis.settings.keepTheScreenOn, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("always" to L.jarvis.settings.whileJarvisIsOn, "active" to L.jarvis.settings.whileItsActive, "off" to L.common.never), screenOn) { P.keepScreenOn.set(it) }
                    JHint(L.jarvis.settings.whileItsActiveWhileJarvisListens)
                    val size by P.orbSize.state.collectAsState()
                    Text(L.jarvis.settings.orbWhileIdle, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("tiny" to L.jarvis.settings.tiny, "small" to L.jarvis.settings.small, "normal" to L.jarvis.settings.normal, "hidden" to L.jarvis.settings.hidden), size) { P.orbSize.set(it) }
                    JHint(L.jarvis.settings.dragTheOrbAnywhereItRests)
                    val style by P.orbStyle.state.collectAsState()
                    JChips(listOf("floating" to L.jarvis.settings.floatingCard, "fullscreen" to L.jarvis.settings.fullScreen), style) { P.orbStyle.set(it) }
                    // Floating Jarvis: the orb over other apps ("Display over other apps", checked again when the page comes back)
                    val floating by P.floating.state.collectAsState()
                    var canDraw by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
                    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    DisposableEffect(owner) {
                        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) canDraw = android.provider.Settings.canDrawOverlays(context)
                        }
                        owner.lifecycle.addObserver(obs)
                        onDispose { owner.lifecycle.removeObserver(obs) }
                    }
                    val openOverlaySettings = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        Unit
                    }
                    JSwitch(
                        L.jarvis.settings.floatOverOtherApps,
                        L.jarvis.settings.outsideAntigemTheOrbFloatsOver,
                        floating
                    ) { on ->
                        P.floating.set(on)
                        if (on) P.floatingHidden.set(false)
                        if (on && !canDraw) openOverlaySettings()
                    }
                    if (floating && !canDraw) {
                        JHint(L.jarvis.settings.needsDisplayOverOtherAppsFor)
                        OutlinedButton(onClick = openOverlaySettings) { Text(L.common.allow) }
                    }
                    val haptics by P.haptics.state.collectAsState()
                    JSwitch(L.jarvis.settings.vibrateWhenListeningStarts, null, haptics) { P.haptics.set(it) }
                    val activity by P.showActivity.state.collectAsState()
                    JSwitch(L.jarvis.settings.showWhatJarvisIsDoing, L.jarvis.settings.aSlimStatusLineCheckingThe, activity) { P.showActivity.set(it) }
                    val transcript by P.showTranscript.state.collectAsState()
                    JSwitch(L.jarvis.settings.showWhatJarvisHeardAndSaid, null, transcript) { P.showTranscript.set(it) }
                }
            }
            JarvisSettingsPage.CHAT -> {
                JCard(cardBg, cardBorder, "") {
                    val ctx by P.contextInPrompt.state.collectAsState()
                    JSwitch(L.jarvis.settings.sendLiveContextWithEveryMessage, L.jarvis.settings.screenProjectChatOnScreenTasks, ctx) { P.contextInPrompt.set(it) }
                    val idle by P.rotateIdleMinutes.state.collectAsState()
                    Text(L.jarvis.settings.startAFreshChatAfterIdle, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to L.common.never, "5" to "5 min", "15" to "15 min", "30" to "30 min", "60" to "1 h", "180" to "3 h"), idle.toString()) { P.rotateIdleMinutes.set(it.toInt()) }
                    val maxMsgs by P.rotateMaxMessages.state.collectAsState()
                    Text(L.jarvis.settings.startAFreshChatAfterThis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to L.common.never, "10" to "10", "20" to "20", "40" to "40", "80" to "80"), maxMsgs.toString()) { P.rotateMaxMessages.set(it.toInt()) }
                    JHint(L.jarvis.settings.bothAreOffByDefaultSo)
                    val carry by P.carryOver.state.collectAsState()
                    JSwitch(L.jarvis.settings.carryTheConversationOver, L.jarvis.settings.aNewChatStartsWithThe, carry) { P.carryOver.set(it) }
                    val count by P.currentChatMessages.state.collectAsState()
                    Text(L.jarvis.settings.currentChatCountMessageValue(count, if (count == 1) "" else "s"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    OutlinedButton(onClick = { JarvisController.newSession(carry = carry); Toast.makeText(context, L.jarvis.settings.theNextMessageStartsANew, Toast.LENGTH_SHORT).show() }) {
                        Text(L.jarvis.settings.startAFreshChatNow)
                    }
                    val memory by P.memory.state.collectAsState()
                    OutlinedTextField(
                        value = memory, onValueChange = { P.memory.set(it.take(4000)) },
                        label = { Text(L.jarvis.settings.jarvisMemoryJarvisCanEditThis) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A phrase list folded to one line ("Send phrases · 19   Edit"): its chips and field only exist while it is open, so
 * the listening page opens at once.
 */
@Composable
private fun FoldedPhrases(title: String, count: Int, editor: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$title · $count", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
        Text(if (open) L.common.done else L.common.edit, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
    if (open) editor()
}


// ---------------------------------------------------------------------------- small building blocks

/**
 * Editable list of phrases (one chip each, tap × to remove), a box to add a word or a whole sentence, and reset.
 * [other] is the opposite list (send ↔ cancel): the same phrase cannot be in both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhraseEditor(phrases: List<String>, other: List<String>, otherName: String, defaults: List<String>, onChange: (List<String>) -> Unit) {
    val J = ai.antigem.data.jarvis.JarvisPhrases
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    fun add() {
        val p = J.normalize(input)
        if (p.isBlank()) return
        note = when {
            p in phrases -> L.jarvis.settings.pIsAlreadyInTheList(p)
            p in other -> L.jarvis.settings.pIsAlreadyAOthernamePhrase(p, otherName)
            else -> {
                onChange(phrases + p)
                input = ""
                if (J.isRisky(p)) L.jarvis.settings.addedPIsShortOrCommon(p) else null
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // light chips (a rounded outline with the phrase and ×; tap removes it): the lists hold ~20 phrases each, and
        // as Material InputChips they made opening Jarvis' settings stall for about half a second
        val chipShape = RoundedCornerShape(8.dp)
        val chipBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        val xColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        phrases.forEach { p ->
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(chipShape)
                    .border(1.dp, chipBorder, chipShape)
                    .clickable { onChange(phrases - p) }
                    .padding(start = 12.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(p, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text("×", fontSize = 14.sp, color = xColor)
            }
        }
        if (phrases.isEmpty()) Text(L.jarvis.settings.noPhrasesYet, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input, onValueChange = { input = it; note = null },
            label = { Text(L.jarvis.settings.addAWordOrAWhole, fontSize = 12.sp) }, singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() })
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { add() }, enabled = input.isNotBlank()) { Text(L.common.add, fontSize = 12.sp) }
    }
    note?.let { Text(it, fontSize = 12.sp, color = Color(0xFFF59E0B)) }
    // nothing is active until added: suggestions are offered, never applied by themselves
    val missing = defaults.map { J.normalize(it) }.filter { it !in phrases && it !in other }
    Row {
        if (missing.isNotEmpty()) TextButton(onClick = { onChange(phrases + missing); note = null }) { Text("Add suggestions (${missing.size})", fontSize = 12.sp) }
        if (phrases.isNotEmpty()) TextButton(onClick = { onChange(emptyList()); note = null }) { Text(L.jarvis.settings.removeAll, fontSize = 12.sp) }
    }
}

@Composable
private fun JCard(bg: Color, border: BorderStroke, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = border
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title.isNotBlank()) Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = JarvisCyan)
            content()
        }
    }
}

@Composable
private fun JSwitch(title: String, subtitle: String?, checked: Boolean, enabledSwitch: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabledSwitch)
    }
}

@Composable
private fun JChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label, fontSize = 12.sp) })
        }
    }
}

@Composable
private fun JField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@Composable
private fun JSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(shown, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun JHint(text: String) {
    Text(text, fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
}

@Composable
private fun JStep(title: String, subtitle: String, done: Boolean, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = if (done) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.width(8.dp))
        if (done && button == L.common.allow) Text("✓", color = Color(0xFF22C55E), fontSize = 18.sp)
        else OutlinedButton(onClick = onClick) { Text(button, fontSize = 12.sp) }
    }
}
