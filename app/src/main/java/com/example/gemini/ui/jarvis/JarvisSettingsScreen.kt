package com.example.gemini.ui.jarvis

import com.example.gemini.ui.components.bouncyVerticalScroll
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
import com.example.gemini.data.agent.claude.ClaudeMcpAddRequest
import com.example.gemini.data.jarvis.JarvisController
import com.example.gemini.data.jarvis.JarvisPreferences
import com.example.gemini.data.jarvis.JarvisSkill
import com.example.gemini.data.jarvis.JarvisVoice
import com.example.gemini.data.remote.AndroidLocalBridgeServer
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.McpServerSpec
import kotlinx.coroutines.launch

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

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        JCard(cardBg, cardBorder, "Jarvis") {
            JSwitch(
                "Turn on Jarvis",
                "A hands-free assistant for the whole app: talk to open projects and files, run and watch tasks, answer approvals, " +
                    "change settings, ask for help or your usage. Off by default; nothing listens until you turn it on.",
                enabled
            ) { JarvisController.setEnabled(it) }
            JHint("Tap the orb to talk, long-press it for the Jarvis panel (tasks, conversation, typing, quick switches).")
        }
        if (!JarvisController.isAttached) {
            Text("Jarvis is starting…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        val c = com.example.gemini.theme.AppColors
        val I = androidx.compose.material.icons.Icons.Outlined
        fun row(page: JarvisSettingsPage, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, value: String? = null) =
            com.example.gemini.ui.settings.SettingsRowItem(icon, tint, title, value) { onOpen(page) }
        listOf(
            listOfNotNull(
                row(JarvisSettingsPage.AGENT, I.SmartToy, c.info, "Agent", if (agentName == AgentKind.AGY.name) "Antigravity" else "Claude Code"),
                row(JarvisSettingsPage.PERSONALITY, I.Face, c.accentPink, "Personality & you"),
                // the setup checklist is only for a Jarvis that is turned on
                if (enabled) row(JarvisSettingsPage.SETUP, I.Checklist, c.success, "Setup") else null
            ),
            listOf(
                row(JarvisSettingsPage.WAKE, I.Hearing, c.accentTeal, "Waking Jarvis", if (hotword == "phrase") "Wake phrase" else "Button"),
                row(JarvisSettingsPage.LISTEN, I.Mic, c.accentIndigo, "Listening & sending", if (autoSend) "Auto-send" else "Manual send"),
                row(JarvisSettingsPage.VOICE, I.RecordVoiceOver, c.accentAlt, "Voice output", when {
                    quiet -> "Quiet"
                    engine == "google_cloud" -> "Google Cloud"
                    engine == "openai" -> "OpenAI"
                    else -> "This device"
                })
            ),
            listOf(
                row(JarvisSettingsPage.TASKS, I.TaskAlt, c.warning, "Manager & tasks"),
                row(JarvisSettingsPage.APPROVALS, I.VerifiedUser, c.danger, "Approvals", when (policy) {
                    "auto_readonly" -> "Auto: read-only"
                    "auto_except_risky" -> "Auto: not risky"
                    "auto_all" -> "Auto: all"
                    else -> "Ask each time"
                }),
                row(JarvisSettingsPage.SCREEN, I.Visibility, c.info, "Screen & look"),
                row(JarvisSettingsPage.CHAT, I.ChatBubbleOutline, JarvisCyan, "Jarvis chat")
            )
        ).filter { it.isNotEmpty() }.forEach { com.example.gemini.ui.settings.SettingsRowGroup(it, cardBg) }
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
        AgentKind.CLAUDE -> "Claude Code"
        AgentKind.AGY -> "Antigravity"
        AgentKind.OPENCODE -> "OpenCode"
        null -> "the agent"
    }

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!JarvisController.isAttached) {
            Text("Jarvis is starting…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        val vm = JarvisController.vm
        when (page) {
            JarvisSettingsPage.AGENT -> {
                JCard(cardBg, cardBorder, "") {
                    val enabledAgents by vm.enabledAgents.collectAsState()
                    JChips(
                        listOf(AgentKind.CLAUDE.name to "Claude Code", AgentKind.AGY.name to "Antigravity", AgentKind.OPENCODE.name to "OpenCode"),
                        agentName
                    ) { P.agent.set(it); P.modelId.set("") }
                    if (agent == null) {
                        Text("Pick the agent Jarvis talks to. Nothing is selected yet, so Jarvis can't answer.", fontSize = 12.5.sp, color = Color(0xFFF59E0B))
                    } else if (agent !in enabledAgents) {
                        Text("$agentTitle is turned off in Settings → Agents: turn it on, or pick another agent here.", fontSize = 12.5.sp, color = Color(0xFFF59E0B))
                    }
                    val models by (when (agent) {
                        AgentKind.CLAUDE -> vm.claudeBackend.availableModels
                        AgentKind.OPENCODE -> vm.openCodeBackend.availableModels
                        else -> vm.agyBackend.enabledModels
                    }).collectAsState()
                    val modelId by P.modelId.state.collectAsState()
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { menu = true }) {
                            val label = models.firstOrNull { it.id == modelId }?.let { it.displayName.ifBlank { it.id } } ?: "Default model"
                            Text("Model: $label")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Default model") }, onClick = { P.modelId.set(""); menu = false })
                            models.forEach { m ->
                                DropdownMenuItem(text = { Text(m.displayName.ifBlank { m.id }) }, onClick = { P.modelId.set(m.id); menu = false })
                            }
                        }
                    }
                    JHint("Jarvis talks to a normal hidden chat of this agent (find it under Jarvis in the chat list). A fast model feels best for voice; " +
                        "big jobs run as tasks in their own chats.")
                }
            }
            JarvisSettingsPage.PERSONALITY -> {
                JCard(cardBg, cardBorder, "") {
                    val persona by P.persona.state.collectAsState()
                    Text("Jarvis speaks as", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("" to "Not set", "female" to "Female", "male" to "Male", "neutral" to "Neutral"), persona) { P.persona.set(it) }
                    JHint("The grammatical gender Jarvis uses for itself. It matters in languages like Hindi (“main kar rahi hoon” / “main kar raha hoon”). " +
                        "Pick a matching voice under Voice output.")
                    val about by P.aboutUser.state.collectAsState()
                    OutlinedTextField(
                        value = about, onValueChange = { P.aboutUser.set(it.take(2000)) },
                        label = { Text("About you and extra instructions") },
                        placeholder = { Text("e.g. Call me Santosh. Reply in Hinglish. I mostly work on the antigem project.", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8
                    )
                    JHint("Sent with the first message of every new Jarvis chat. When you change it (or the persona), the open chat is told with your next message. " +
                        "Jarvis' own memory note (Settings → Jarvis → Jarvis chat) is separate: Jarvis edits that one itself.")
                }
            }
            JarvisSettingsPage.SETUP -> {
                if (!enabled) {
                    JHint("Turn on Jarvis first (Settings → Jarvis).")
                } else {
                    if (agent == null) {
                        JHint("Pick the agent Jarvis talks to first (Settings → Jarvis → Agent).")
                    } else JCard(cardBg, cardBorder, "Setup for $agentTitle") {
                        val deviceId = remember(context) {
                            runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "" }.getOrDefault("")
                        }
                        val headers = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
                        val url = "http://127.0.0.1:${AndroidLocalBridgeServer.DEFAULT_PORT}/jarvis"
                        val name = AndroidLocalBridgeServer.JARVIS_MCP_NAME
                        val mcpProblem by JarvisController.mcpProblem.collectAsState()
                        if (mcpProblem) {
                            Text(
                                "Jarvis reported that its app tools are missing in $agentTitle. Add the Jarvis MCP server below (then start a fresh Jarvis chat).",
                                fontSize = 12.5.sp, color = Color(0xFFF59E0B)
                            )
                        }

                        // 1. the MCP server
                        val claudeServers by vm.claudeConfig.mcpServers.collectAsState()
                        val agyServers by vm.mcpServers.collectAsState()
                        // OpenCode keeps the servers the app adds in the bridge's OpenCode config
                        var openCodeServers by remember { mutableStateOf<Set<String>>(emptySet()) }
                        val reloadOpenCodeMcp: () -> Unit = { scope.launch { vm.openCodeBackend.client.mcpServers().onSuccess { openCodeServers = it } } }
                        LaunchedEffect(agent) {
                            when (agent) {
                                AgentKind.CLAUDE -> vm.claudeConfig.loadMcp(null)
                                AgentKind.OPENCODE -> reloadOpenCodeMcp()
                                else -> vm.loadMcpServers()
                            }
                        }
                        val mcpState = when (agent) {
                            AgentKind.CLAUDE -> claudeServers.firstOrNull { it.name == name }?.status
                            AgentKind.OPENCODE -> null
                            else -> agyServers.firstOrNull { it.name == name }?.status
                        }
                        val mcpAdded = when (agent) {
                            AgentKind.CLAUDE -> claudeServers.any { it.name == name }
                            AgentKind.OPENCODE -> name in openCodeServers
                            else -> agyServers.any { it.name == name }
                        }
                        JStep(
                            "1. Add the Jarvis MCP server",
                            if (mcpAdded) "Added" + (mcpState?.let { " · $it" } ?: "") else "Required: these are the tools Jarvis controls the app with.",
                            mcpAdded, if (mcpAdded) "Refresh" else "Add"
                        ) {
                            if (agent == AgentKind.OPENCODE) {
                                if (mcpAdded) reloadOpenCodeMcp()
                                else scope.launch {
                                    vm.openCodeBackend.client.addRemoteMcp(name, url, headers)
                                        .onSuccess { reloadOpenCodeMcp(); Toast.makeText(context, "Added to OpenCode", Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(context, it.message ?: "Could not add it", Toast.LENGTH_LONG).show() }
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
                                "2. Let Jarvis' tools run without asking",
                                if (allowed) "Allowed" else "Otherwise every spoken answer would need an approval.",
                                allowed, "Allow"
                            ) { vm.claudeConfig.addRule("allow", "mcp__$name") }
                        }

                        // 3. the microphone
                        var hasMic by remember { mutableStateOf(JarvisController.hasMic()) }
                        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
                        JStep(
                            "${if (agent == AgentKind.CLAUDE) "3" else "2"}. Allow the microphone",
                            if (hasMic) "Allowed" else "Needed to hear you.",
                            hasMic, "Allow"
                        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

                        // 4. the optional skill
                        var skillInstalled by remember(agent) { mutableStateOf<Boolean?>(null) }
                        LaunchedEffect(agent) { skillInstalled = runCatching { JarvisSkill.isInstalled(agent) }.getOrDefault(false) }
                        JStep(
                            "Optional: install the Jarvis skill",
                            when (skillInstalled) { true -> "Installed"; false -> "Not needed: the guide goes with the first message."; null -> "Checking…" },
                            skillInstalled == true, if (skillInstalled == true) "Reinstall" else "Install"
                        ) {
                            scope.launch {
                                JarvisSkill.install(agent)
                                    .onSuccess { skillInstalled = true; Toast.makeText(context, "Installed $it", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, it.message ?: "Install failed", Toast.LENGTH_LONG).show() }
                            }
                        }
                        JHint("The first message of every Jarvis chat carries the whole guide and the list of its tools with their parameters, so the " +
                            "model acts at once instead of searching. If the Jarvis MCP server is missing, Jarvis says so and this card shows a warning.")
                    }
                }
            }
            JarvisSettingsPage.WAKE -> {
                JCard(cardBg, cardBorder, "") {
                    val hotword by P.hotword.state.collectAsState()
                    Text("Trigger", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("push" to "Button / tile / headset", "phrase" to "Wake phrase too"), hotword) { P.hotword.set(it) }
                    if (hotword == "phrase") {
                        val phrase by P.wakePhrase.state.collectAsState()
                        JField("Wake phrase(s), comma separated", phrase) { P.wakePhrase.set(it.lowercase()) }
                        val match by P.wakeMatch.state.collectAsState()
                        JChips(listOf("phrase" to "Whole phrase", "name" to "The name alone"), match) { P.wakeMatch.set(it) }
                        JHint("“Hey Jarvis, open my project” starts right away. Common mishearings of “Jarvis” count too. The wake phrase needs " +
                            "hands-free mode and keeps the device recognizer busy, which uses battery.")
                    }
                    val hands by P.handsFree.state.collectAsState()
                    JSwitch(
                        "Hands-free mode",
                        "Keeps Jarvis' microphone service running so the wake phrase and the headset button work with the screen off or another app open. " +
                            "A notification shows its state with Talk / Send / Stop.",
                        hands, enabledSwitch = enabled
                    ) { JarvisController.setHandsFree(it) }
                    val headset by P.headsetButton.state.collectAsState()
                    JSwitch("Headset button", "With hands-free on: one press talks (or sends), two presses stop. Works while no music app holds the button.", headset) { P.headsetButton.set(it) }
                    JHint("Quick Settings tile: pull down the notification shade, edit the tiles and add “Jarvis” to talk from anywhere with one tap.")
                }
            }
            JarvisSettingsPage.LISTEN -> {
                JCard(cardBg, cardBorder, "") {
                    JHint("You can always send with the Send button on the card, the headset button or the notification's Send.")
                    val auto by P.autoSend.state.collectAsState()
                    JSwitch(
                        "Send automatically when I pause",
                        "Off: nothing is sent while you take a break to think. Say a send phrase or tap Send when you're done.",
                        auto
                    ) { P.autoSend.set(it) }
                    if (auto) {
                        val mode by P.sendMode.state.collectAsState()
                        JChips(listOf("smart" to "Smart pause", "pause" to "Fixed pause"), if (mode == "pause") "pause" else "smart") { P.sendMode.set(it) }
                        JHint(if (mode == "pause") "Sent after a fixed pause. A bar on the card fills while it counts down; tap Wait to keep talking."
                        else "Jarvis waits longer when you sound unfinished (“…and”, “…the”) and less for short answers. Tap Wait to keep talking.")
                        val silence by P.silenceMs.state.collectAsState()
                        JSlider("Pause that ends your sentence", silence.toFloat(), 800f..3500f, "%.1f s".format(silence / 1000f)) { P.silenceMs.set(it.toInt()) }
                    } else {
                        val answers by P.autoSendAnswers.state.collectAsState()
                        JSwitch("Answers to Jarvis' questions send by themselves", "“Yes”, “the second one”: sent after a short pause when Jarvis just asked you something.", answers) { P.autoSendAnswers.set(it) }
                    }

                    // send phrases
                    val sendOn by P.sendPhrasesOn.state.collectAsState()
                    val sendRaw by P.sendPhrases.state.collectAsState()
                    val cancelOn by P.cancelPhrasesOn.state.collectAsState()
                    val cancelRaw by P.cancelPhrases.state.collectAsState()
                    val sendList = remember(sendRaw) { com.example.gemini.data.jarvis.JarvisPhrases.parse(sendRaw) }
                    val cancelList = remember(cancelRaw) { com.example.gemini.data.jarvis.JarvisPhrases.parse(cancelRaw) }
                    JSwitch("Send with a phrase", "End with one of these and it is sent at once (the phrase itself is not sent).", sendOn) { P.sendPhrasesOn.set(it) }
                    if (sendOn) {
                        FoldedPhrases("Send phrases", sendList.size) {
                            PhraseEditor(sendList, cancelList, "cancel", com.example.gemini.data.jarvis.JarvisPhrases.DEFAULT_SEND) {
                                P.sendPhrases.set(com.example.gemini.data.jarvis.JarvisPhrases.format(it))
                            }
                        }
                    }
                    JSwitch("Cancel with a phrase", "End with one of these and nothing is sent.", cancelOn) { P.cancelPhrasesOn.set(it) }
                    if (cancelOn) {
                        FoldedPhrases("Cancel phrases", cancelList.size) {
                            PhraseEditor(cancelList, sendList, "send", com.example.gemini.data.jarvis.JarvisPhrases.DEFAULT_CANCEL) {
                                P.cancelPhrases.set(com.example.gemini.data.jarvis.JarvisPhrases.format(it))
                            }
                        }
                    }
                    if (sendOn || cancelOn) {
                        JHint("A phrase only counts at the very end. “…and send it”, “…to send it”, “can you do it” and “don't send it” stay part of your " +
                            "message. Short or everyday phrases (“send”, “over”, “do it”) only count when you say them on their own after a short pause. " +
                            "When a phrase keeps firing by mistake, remove it or add a longer sentence instead (e.g. “jarvis send it now”).")
                    }
                    val listenAfter by P.listenAfter.state.collectAsState()
                    Text("After Jarvis speaks, listen for your reply", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("auto" to "When it expects an answer", "always" to "Always", "never" to "Never"), listenAfter) { P.listenAfter.set(it) }
                    JHint(when (listenAfter) {
                        "always" -> "The microphone opens after every answer while you are talking to Jarvis."
                        "never" -> "The microphone only opens when you tap or wake Jarvis (and when it asks you something it needs right away)."
                        else -> "Jarvis decides: after “Done, want me to run it?” the microphone opens; after “Saved the file.” it does not. Questions always open it."
                    })
                    if (listenAfter != "never") {
                        val follow by P.followUpSeconds.state.collectAsState()
                        JSlider("Wait for a reply", follow.toFloat(), 3f..20f, "$follow s") { P.followUpSeconds.set(it.toInt()) }
                        JHint("If you say nothing at all in this time, the microphone closes with a soft tone.")
                    }
                    val maxListen by P.maxListenSeconds.state.collectAsState()
                    JSlider("Longest single message", maxListen.toFloat(), 20f..240f, "$maxListen s") { P.maxListenSeconds.set(it.toInt()) }
                    val side by P.sideTalk.state.collectAsState()
                    Text("Talking to other people nearby", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("off" to "Off", "smart" to "Smart", "strict" to "Strict"), side) { P.sideTalk.set(it) }
                    JHint(when (side) {
                        "off" -> "Everything heard is for Jarvis."
                        "strict" -> "During follow-up listening Jarvis only reacts when you say its name (or answer its question)."
                        else -> "During follow-up listening Jarvis ignores what was clearly said to someone else, and filler like “um”."
                    })
                    val busyB by P.busyBehavior.state.collectAsState()
                    Text("Speaking while Jarvis is still working", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("smart" to "Corrections interrupt", "queue" to "Wait for it", "interrupt" to "Always interrupt"), busyB) { P.busyBehavior.set(it) }
                    val earcons by P.earcons.state.collectAsState()
                    JSwitch("Tones when the microphone opens and closes", null, earcons) { P.earcons.set(it) }
                    val stt by P.sttEngine.state.collectAsState()
                    Text("Speech recognition", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("auto" to "Same as agent", "claude" to "Claude", "agy" to "Antigravity", "android" to "This device"), stt) { P.sttEngine.set(it) }
                    JHint("“This device” uses Android's recognizer and needs no sign-in. If the agent's dictation fails, Jarvis switches to the device for the rest of the session.")
                    val lang by P.sttLanguage.state.collectAsState()
                    JField("Language (e.g. en, hi, es)", lang) { P.sttLanguage.set(it) }
                }
            }
            JarvisSettingsPage.VOICE -> {
                JCard(cardBg, cardBorder, "") {
                    val quiet by P.quietMode.state.collectAsState()
                    JSwitch("Quiet mode", "Jarvis shows its answers on the card instead of saying them (meetings, night). Say “quiet mode off” to undo.", quiet) { P.quietMode.set(it) }
                    val engine by P.ttsEngine.state.collectAsState()
                    JChips(listOf("android" to "This device", "google_cloud" to "Google Cloud", "openai" to "OpenAI-compatible"), engine) { P.ttsEngine.set(it) }
                    val voice by P.ttsVoice.state.collectAsState()
                    val language by P.ttsLanguage.state.collectAsState()
                    if (engine == "android") {
                        var voices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                        var menu by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { voices = runCatching { JarvisVoice.deviceVoices() }.getOrDefault(emptyList()) }
                        Box {
                            OutlinedButton(onClick = { menu = true }) { Text("Voice: " + (voices.firstOrNull { it.first == voice }?.second ?: "System default")) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("System default") }, onClick = { P.ttsVoice.set(""); menu = false })
                                voices.take(80).forEach { (id, label) ->
                                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) }, onClick = { P.ttsVoice.set(id); menu = false })
                                }
                            }
                        }
                        JField("Language (optional, e.g. en-US)", language) { P.ttsLanguage.set(it) }
                    } else {
                        val key by P.ttsApiKey.state.collectAsState()
                        JField("API key", key, secret = true) { P.ttsApiKey.set(it) }
                        if (engine == "google_cloud") {
                            JField("Voice name (e.g. en-US-Neural2-F)", voice) { P.ttsVoice.set(it) }
                            JField("Language code (e.g. en-US)", language) { P.ttsLanguage.set(it) }
                        } else {
                            val base by P.ttsBaseUrl.state.collectAsState()
                            val model by P.ttsModel.state.collectAsState()
                            JField("Base URL (default https://api.openai.com/v1)", base) { P.ttsBaseUrl.set(it) }
                            JField("Model (default tts-1)", model) { P.ttsModel.set(it) }
                            JField("Voice (default alloy)", voice) { P.ttsVoice.set(it) }
                        }
                        JHint("If the online voice fails, Jarvis falls back to this device's voice.")
                    }
                    val rate by P.ttsRate.state.collectAsState()
                    JSlider("Speed", rate, 0.5f..2f, "%.1fx".format(rate)) { P.ttsRate.set(it) }
                    val pitch by P.ttsPitch.state.collectAsState()
                    JSlider("Pitch", pitch, 0.5f..2f, "%.1f".format(pitch)) { P.ttsPitch.set(it) }
                    val err by JarvisVoice.lastError.collectAsState()
                    err?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { scope.launch { JarvisVoice.speak("Hello, I'm Jarvis. This is how I sound.", wait = false) } }) { Text("Test voice") }
                    val progress by P.speakProgress.state.collectAsState()
                    JSwitch("Speak progress updates", "Jarvis says a short line while a long step runs.", progress) { P.speakProgress.set(it) }
                    JHint("You can also say “speak slower”, “faster” or “repeat that”: Jarvis handles those at once, without asking the model.")
                }
            }
            JarvisSettingsPage.TASKS -> {
                JCard(cardBg, cardBorder, "") {
                    JHint("Say “build the release and tell me when it's done”: Jarvis starts a task in its own chat and watches it. You can ask how it is going, " +
                        "redirect it (“also run the tests”), stop it, or answer its questions by voice. Tasks are listed in the Jarvis panel.")
                    val routing by P.eventRouting.state.collectAsState()
                    Text("When a task finishes, fails or gets stuck", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("model" to "Jarvis decides", "speak" to "Just tell me"), routing) { P.eventRouting.set(it) }
                    JHint(if (routing == "model") "Jarvis reads the result, tells you the gist and follows up (asks you, passes answers on). Uses a model turn per event."
                    else "Jarvis just announces it (“Release build is done”), without a model turn.")
                    val taskAppr by P.taskApprovals.state.collectAsState()
                    Text("Approvals inside tasks", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("policy" to "Use my approval rules", "jarvis" to "Jarvis decides"), taskAppr) { P.taskApprovals.set(it) }
                    JHint(if (taskAppr == "jarvis") "Jarvis approves what clearly serves the task's goal and asks you about anything risky or unclear (needs “Jarvis decides” above)."
                    else "Approvals follow “Approvals by voice” below: approved automatically or read out to you.")
                    val stall by P.stallMinutes.state.collectAsState()
                    Text("Report a task with no progress after", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "Never", "5" to "5 min", "10" to "10 min", "20" to "20 min", "45" to "45 min"), stall.toString()) { P.stallMinutes.set(it.toInt()) }
                    val done by P.announceDone.state.collectAsState()
                    JSwitch("Say when any other chat finishes", "“<chat title> is done. Please check it.” for chats that are not Jarvis tasks.", done) { P.announceDone.set(it) }
                    if (done) {
                        val off by P.announceOffscreenOnly.state.collectAsState()
                        JSwitch("Not for the chat I'm looking at", null, off) { P.announceOffscreenOnly.set(it) }
                    }
                    val sum by P.announceSummary.state.collectAsState()
                    JSwitch("Also read the start of the reply", "For announcements without a model turn.", sum) { P.announceSummary.set(it) }
                    val low by P.announceUsageLow.state.collectAsState()
                    JSwitch("Warn when the 5-hour usage is almost gone", "Once per window, at about 15% left (Claude Code).", low) { P.announceUsageLow.set(it) }
                }
            }
            JarvisSettingsPage.APPROVALS -> {
                JCard(cardBg, cardBorder, "") {
                    val policy by P.permissionPolicy.state.collectAsState()
                    var confirmAll by remember { mutableStateOf(false) }
                    JChips(
                        listOf("ask_each" to "Ask me each time", "auto_readonly" to "Auto: read-only", "auto_except_risky" to "Auto: except risky", "auto_all" to "Auto: everything"),
                        policy
                    ) { if (it == "auto_all") confirmAll = true else P.permissionPolicy.set(it) }
                    JHint(
                        when (policy) {
                            "auto_readonly" -> "Reading files and searching is approved; anything else is read out and needs a yes."
                            "auto_except_risky" -> "Everything is approved except risky actions (deleting, pushing, installing from the web), which are read out first."
                            "auto_all" -> "Nothing is asked. Use only if you fully trust what the agents do."
                            else -> "Jarvis reads each request out (“It wants to run the command…”) and waits for your yes or no."
                        } + " Jarvis' own tools never need approval. The chat on screen is only handled while hands-free mode is on (tasks always are)."
                    )
                    if (confirmAll) {
                        AlertDialog(
                            onDismissRequest = { confirmAll = false },
                            title = { Text("Approve everything automatically?") },
                            text = { Text("Agents will run commands, edit files and delete things without asking, including by mistake. Only choose this if you accept that.") },
                            confirmButton = { TextButton(onClick = { P.permissionPolicy.set("auto_all"); confirmAll = false }) { Text("Yes, approve everything") } },
                            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } }
                        )
                    }
                    val allow by P.allowSettingChanges.state.collectAsState()
                    JSwitch(
                        "Let Jarvis change app settings",
                        "Theme, chat font size, floating bubble and switcher, new-chat agent. Jarvis' own voice and listening options can always be changed by voice; " +
                            "approval and security settings never.",
                        allow
                    ) { P.allowSettingChanges.set(it) }
                }
            }
            JarvisSettingsPage.SCREEN -> {
                JCard(cardBg, cardBorder, "") {
                    val screenOn by P.keepScreenOn.state.collectAsState()
                    Text("Keep the screen on", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("always" to "While Jarvis is on", "active" to "While it's active", "off" to "Never"), screenOn) { P.keepScreenOn.set(it) }
                    JHint("“While it's active”: while Jarvis listens, works or speaks, or a task runs. Running chats of both agents keep the screen on anyway.")
                    val size by P.orbSize.state.collectAsState()
                    Text("Orb while idle", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("tiny" to "Tiny", "small" to "Small", "normal" to "Normal", "hidden" to "Hidden"), size) { P.orbSize.set(it) }
                    JHint("Drag the orb anywhere: it rests on the nearest edge. “Hidden” shows it only while Jarvis is active (use the tile, headset or wake phrase).")
                    val style by P.orbStyle.state.collectAsState()
                    JChips(listOf("floating" to "Floating card", "fullscreen" to "Full screen"), style) { P.orbStyle.set(it) }
                    val haptics by P.haptics.state.collectAsState()
                    JSwitch("Vibrate when listening starts", null, haptics) { P.haptics.set(it) }
                    val activity by P.showActivity.state.collectAsState()
                    JSwitch("Show what Jarvis is doing", "A slim status line (“Checking the build…”) while it works.", activity) { P.showActivity.set(it) }
                    val transcript by P.showTranscript.state.collectAsState()
                    JSwitch("Show what Jarvis heard and said", null, transcript) { P.showTranscript.set(it) }
                }
            }
            JarvisSettingsPage.CHAT -> {
                JCard(cardBg, cardBorder, "") {
                    val ctx by P.contextInPrompt.state.collectAsState()
                    JSwitch("Send live context with every message", "Screen, project, chat on screen, tasks, approvals and timers, so Jarvis rarely has to look them up.", ctx) { P.contextInPrompt.set(it) }
                    val idle by P.rotateIdleMinutes.state.collectAsState()
                    Text("Start a fresh chat after idle time", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "Never", "5" to "5 min", "15" to "15 min", "30" to "30 min", "60" to "1 h", "180" to "3 h"), idle.toString()) { P.rotateIdleMinutes.set(it.toInt()) }
                    val maxMsgs by P.rotateMaxMessages.state.collectAsState()
                    Text("Start a fresh chat after this many messages", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    JChips(listOf("0" to "Never", "10" to "10", "20" to "20", "40" to "40", "80" to "80"), maxMsgs.toString()) { P.rotateMaxMessages.set(it.toInt()) }
                    JHint("Both are off by default so Jarvis never forgets mid-task. A chat is only replaced when you speak the next message, never while it is working. " +
                        "You can always say “start again” or “new session”.")
                    val carry by P.carryOver.state.collectAsState()
                    JSwitch("Carry the conversation over", "A new chat starts with the Jarvis memory note and the last lines of the old conversation.", carry) { P.carryOver.set(it) }
                    val count by P.currentChatMessages.state.collectAsState()
                    Text("Current chat: $count message${if (count == 1) "" else "s"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    OutlinedButton(onClick = { JarvisController.newSession(carry = carry); Toast.makeText(context, "The next message starts a new Jarvis chat", Toast.LENGTH_SHORT).show() }) {
                        Text("Start a fresh chat now")
                    }
                    val memory by P.memory.state.collectAsState()
                    OutlinedTextField(
                        value = memory, onValueChange = { P.memory.set(it.take(4000)) },
                        label = { Text("Jarvis memory (Jarvis can edit this too)") },
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
        Text(if (open) "Done" else "Edit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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
    val J = com.example.gemini.data.jarvis.JarvisPhrases
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    fun add() {
        val p = J.normalize(input)
        if (p.isBlank()) return
        note = when {
            p in phrases -> "“$p” is already in the list."
            p in other -> "“$p” is already a $otherName phrase. Remove it there first."
            else -> {
                onChange(phrases + p)
                input = ""
                if (J.isRisky(p)) "Added. “$p” is short or common, so it only counts when said on its own after a short pause." else null
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
        if (phrases.isEmpty()) Text("No phrases yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = input, onValueChange = { input = it; note = null },
            label = { Text("Add a word or a whole sentence", fontSize = 12.sp) }, singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() })
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { add() }, enabled = input.isNotBlank()) { Text("Add", fontSize = 12.sp) }
    }
    note?.let { Text(it, fontSize = 12.sp, color = Color(0xFFF59E0B)) }
    if (phrases != defaults.map { J.normalize(it) }) {
        TextButton(onClick = { onChange(defaults.filter { J.normalize(it) !in other }); note = null }) { Text("Reset to defaults", fontSize = 12.sp) }
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
        if (done && button == "Allow") Text("✓", color = Color(0xFF22C55E), fontSize = 18.sp)
        else OutlinedButton(onClick = onClick) { Text(button, fontSize = 12.sp) }
    }
}
