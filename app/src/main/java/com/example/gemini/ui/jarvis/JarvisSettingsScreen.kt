package com.example.gemini.ui.jarvis

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/** Settings → Jarvis: everything about the hands-free voice mode in one place. */
@Composable
fun JarvisSettingsScreen(cardBg: Color, cardBorder: BorderStroke) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val P = JarvisPreferences

    val enabled by P.enabled.state.collectAsState()
    val agentName by P.agent.state.collectAsState()
    val agent = runCatching { AgentKind.valueOf(agentName) }.getOrDefault(AgentKind.CLAUDE)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------------------------------------------------------------- master switch
        JCard(cardBg, cardBorder, "Jarvis") {
            JSwitch(
                "Turn on Jarvis",
                "Talk to your agents hands-free: open projects and files, start and stop chats, change models, answer permissions, " +
                    "ask for your usage. Off by default; nothing listens until you turn it on.",
                enabled
            ) { JarvisController.setEnabled(it) }
        }
        if (!JarvisController.isAttached) {
            Text("Jarvis is starting…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            return@Column
        }
        val vm = JarvisController.vm

        // ---------------------------------------------------------------- agent
        JCard(cardBg, cardBorder, "Which agent runs Jarvis") {
            JChips(
                listOf(AgentKind.CLAUDE.name to "Claude Code", AgentKind.AGY.name to "Antigravity"),
                agentName
            ) { P.agent.set(it); P.modelId.set("") }
            val models by (if (agent == AgentKind.CLAUDE) vm.claudeBackend.availableModels else vm.agyBackend.enabledModels).collectAsState()
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
            JHint("Jarvis talks to a normal hidden chat of this agent. A smaller, faster model feels best for voice. " +
                "Find the chat under Jarvis in the chat list.")
        }

        // ---------------------------------------------------------------- setup checklist
        if (enabled) {
            JCard(cardBg, cardBorder, "Setup for ${if (agent == AgentKind.CLAUDE) "Claude Code" else "Antigravity"}") {
                val deviceId = remember(context) {
                    runCatching { android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "" }.getOrDefault("")
                }
                val headers = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
                val url = "http://127.0.0.1:${AndroidLocalBridgeServer.DEFAULT_PORT}/jarvis"
                val name = AndroidLocalBridgeServer.JARVIS_MCP_NAME

                // 1. the MCP server
                val claudeServers by vm.claudeConfig.mcpServers.collectAsState()
                val agyServers by vm.mcpServers.collectAsState()
                LaunchedEffect(agent) { if (agent == AgentKind.CLAUDE) vm.claudeConfig.loadMcp(null) else vm.loadMcpServers() }
                val mcpState = if (agent == AgentKind.CLAUDE) claudeServers.firstOrNull { it.name == name }?.status
                else agyServers.firstOrNull { it.name == name }?.status
                val mcpAdded = if (agent == AgentKind.CLAUDE) claudeServers.any { it.name == name } else agyServers.any { it.name == name }
                JStep(
                    "1. Add the Jarvis MCP server",
                    if (mcpAdded) "Added" + (mcpState?.let { " · $it" } ?: "") else "The agent needs it to control the app.",
                    mcpAdded, if (mcpAdded) "Refresh" else "Add"
                ) {
                    if (agent == AgentKind.CLAUDE) {
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

                // 3. the skill
                var skillInstalled by remember(agent) { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(agent) { skillInstalled = runCatching { JarvisSkill.isInstalled(agent) }.getOrDefault(false) }
                JStep(
                    "${if (agent == AgentKind.CLAUDE) "3" else "2"}. Install the Jarvis skill",
                    when (skillInstalled) { true -> "Installed"; false -> "Teaches the model how to answer by voice."; null -> "Checking…" },
                    skillInstalled == true, if (skillInstalled == true) "Reinstall" else "Install"
                ) {
                    scope.launch {
                        JarvisSkill.install(agent)
                            .onSuccess { skillInstalled = true; Toast.makeText(context, "Installed $it", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, it.message ?: "Install failed", Toast.LENGTH_LONG).show() }
                    }
                }

                // 4. the microphone
                var hasMic by remember { mutableStateOf(JarvisController.hasMic()) }
                val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }
                JStep(
                    "${if (agent == AgentKind.CLAUDE) "4" else "3"}. Allow the microphone",
                    if (hasMic) "Allowed" else "Needed to hear you.",
                    hasMic, "Allow"
                ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                JHint("Every voice message also carries a short hidden note, so Jarvis keeps working even if the agent does not load the skill.")
            }
        }

        // ---------------------------------------------------------------- voice output
        JCard(cardBg, cardBorder, "Voice output (Jarvis speaking)") {
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
            JSwitch("Speak progress updates", "Jarvis says a short line while a long task runs.", progress) { P.speakProgress.set(it) }
        }

        // ---------------------------------------------------------------- voice input
        JCard(cardBg, cardBorder, "Voice input (hearing you)") {
            val stt by P.sttEngine.state.collectAsState()
            JChips(listOf("auto" to "Same as agent", "claude" to "Claude", "agy" to "Antigravity", "android" to "This device"), stt) { P.sttEngine.set(it) }
            JHint("“This device” uses Android's speech recognizer and needs no sign-in. The agent's dictation needs that agent signed in.")
            val lang by P.sttLanguage.state.collectAsState()
            JField("Language (e.g. en, hi, es)", lang) { P.sttLanguage.set(it) }
            val silence by P.silenceMs.state.collectAsState()
            JSlider("Pause that ends your sentence", silence.toFloat(), 800f..3500f, "%.1f s".format(silence / 1000f)) { P.silenceMs.set(it.toInt()) }
            val conv by P.conversationMode.state.collectAsState()
            JSwitch("Keep listening after Jarvis answers", "Opens the microphone again so you can reply without touching anything.", conv) { P.conversationMode.set(it) }
            val hotword by P.hotword.state.collectAsState()
            JChips(listOf("push" to "Button only", "phrase" to "Wake phrase"), hotword) { P.hotword.set(it) }
            if (hotword == "phrase") {
                val phrase by P.wakePhrase.state.collectAsState()
                JField("Wake phrase", phrase) { P.wakePhrase.set(it.lowercase()) }
                JHint("The wake phrase works while hands-free mode is on, using the device's recognizer. It keeps the microphone busy, which uses battery.")
            }
            val hands by P.handsFree.state.collectAsState()
            JSwitch(
                "Hands-free mode",
                "Keeps the microphone service running so you can talk with the screen off or another app open. A notification shows its state.",
                hands, enabledSwitch = enabled
            ) { JarvisController.setHandsFree(it) }
        }

        // ---------------------------------------------------------------- chat rotation
        JCard(cardBg, cardBorder, "Jarvis chat") {
            val idle by P.rotateIdleMinutes.state.collectAsState()
            Text("Start a fresh chat after idle time", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            JChips(listOf("0" to "Never", "5" to "5 min", "15" to "15 min", "30" to "30 min", "60" to "1 h", "180" to "3 h"), idle.toString()) { P.rotateIdleMinutes.set(it.toInt()) }
            val maxMsgs by P.rotateMaxMessages.state.collectAsState()
            Text("Start a fresh chat after this many messages", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            JChips(listOf("0" to "Never", "10" to "10", "20" to "20", "40" to "40", "80" to "80"), maxMsgs.toString()) { P.rotateMaxMessages.set(it.toInt()) }
            JHint("Both are off by default so Jarvis never forgets mid-task. A chat is only replaced when you speak the next message, never while it is working. " +
                "You can always say “start again” or “new session”.")
            val carry by P.carryOver.state.collectAsState()
            JSwitch("Carry the memory over", "A new chat starts with the Jarvis memory note and the last lines of the old conversation.", carry) { P.carryOver.set(it) }
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

        // ---------------------------------------------------------------- announcements
        JCard(cardBg, cardBorder, "Announcements") {
            val done by P.announceDone.state.collectAsState()
            JSwitch("Say when a chat finishes", "“<chat title> is done. Please check it.” for any Claude or Antigravity chat.", done) { P.announceDone.set(it) }
            if (done) {
                val off by P.announceOffscreenOnly.state.collectAsState()
                JSwitch("Not for the chat I'm looking at", null, off) { P.announceOffscreenOnly.set(it) }
                val sum by P.announceSummary.state.collectAsState()
                JSwitch("Also read the start of its last reply", null, sum) { P.announceSummary.set(it) }
            }
            val low by P.announceUsageLow.state.collectAsState()
            JSwitch("Warn when the 5-hour usage is almost gone", "Once per window, at about 15% left (Claude Code).", low) { P.announceUsageLow.set(it) }
            JHint("Jarvis stays quiet while you are speaking to it. A light vibration when any chat finishes is in Settings → Appearance.")
        }

        // ---------------------------------------------------------------- permissions
        JCard(cardBg, cardBorder, "Approvals by voice") {
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
                } + " Jarvis' own tools never need approval. Chats on screen are only handled while hands-free mode is on."
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
            JSwitch("Let Jarvis change app settings", "Theme, font size and Jarvis' voice only. Security and permission settings are never available to it.", allow) { P.allowSettingChanges.set(it) }
        }

        // ---------------------------------------------------------------- look
        JCard(cardBg, cardBorder, "Look and feel") {
            val style by P.orbStyle.state.collectAsState()
            JChips(listOf("floating" to "Floating orb", "fullscreen" to "Full screen"), style) { P.orbStyle.set(it) }
            val haptics by P.haptics.state.collectAsState()
            JSwitch("Vibrate when listening starts", null, haptics) { P.haptics.set(it) }
            val activity by P.showActivity.state.collectAsState()
            JSwitch("Show what Jarvis is doing", "A small card with flowing waves and a live line (“Checking the build…”) while it works.", activity) { P.showActivity.set(it) }
            val transcript by P.showTranscript.state.collectAsState()
            JSwitch("Show what Jarvis heard and said", null, transcript) { P.showTranscript.set(it) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------- small building blocks

@Composable
private fun JCard(bg: Color, border: BorderStroke, title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = border
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = JarvisCyan)
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
