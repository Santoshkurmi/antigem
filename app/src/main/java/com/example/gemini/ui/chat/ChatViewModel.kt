package com.example.gemini.ui.chat

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.AntigravityApiService
import com.example.gemini.data.remote.GoogleOAuthManager
import com.example.gemini.data.remote.PromptExpansion
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import android.util.Log
import org.json.JSONObject
import java.util.UUID

data class PendingToolApproval(
    val toolCall: com.example.gemini.domain.model.ToolCall,
    val messageId: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val authPreferences = AuthPreferences(application)
    private val authPrefs get() = authPreferences
    private val apiService = AntigravityApiService()
    private val store = com.example.gemini.data.agent.ChatSessionStore()
    val agyBackend: com.example.gemini.data.agent.agy.AgyChatBackend = com.example.gemini.data.agent.agy.AgyChatBackend(
        application = application,
        backendScope = viewModelScope,
        authPrefs = authPrefs,
        store = store,
        isNetworkConnectedProvider = { isNetworkConnected() },
        onHubOnline = { loadMcpServers() }
    )
    private val mcpManager = com.example.gemini.data.agent.agy.AgyMcpManager(viewModelScope, agyBackend.agyHubClient)
    private val pluginsManager = com.example.gemini.data.agent.agy.AgyPluginsManager(viewModelScope, agyBackend.agyHubClient, store, mcpManager)
    private val securityManager = com.example.gemini.data.agent.agy.AgySecuritySettingsManager(viewModelScope, agyBackend.agyHubClient, authPrefs, store)
    private val agyHubClient get() = agyBackend.agyHubClient
    private val agyBridgeService get() = agyBackend.agyBridgeService

    // ==================== AGENT ROUTING (Antigravity / Claude Code) ====================

    val claudePrefs = com.example.gemini.data.agent.claude.ClaudePreferences(application)
    private val claudeClient = com.example.gemini.data.agent.claude.ClaudeBridgeClient()
    val claudeBackend = com.example.gemini.data.agent.claude.ClaudeChatBackend(application, viewModelScope, store, claudePrefs, claudeClient)
    /** Claude account: sign-in state, plan usage, login / logout. */
    val claudeAccount = com.example.gemini.data.agent.claude.ClaudeAccountManager(viewModelScope, claudeClient)
    /** Claude configuration: settings.json, permission rules, MCP, plugins, memory, CLI install. */
    val claudeConfig = com.example.gemini.data.agent.claude.ClaudeConfigManager(viewModelScope, claudeClient)
    /** Dictation for Claude chats (Claude's speech-to-text through the bridge). */
    val claudeVoice = com.example.gemini.data.agent.claude.ClaudeVoiceTranscriber(claudeClient) { claudePrefs.voiceLanguage.value }

    /** OpenCode: one `opencode serve` on the bridge for all its chats (any provider, OpenAI-compatible ones included). */
    val openCodePrefs = com.example.gemini.data.agent.opencode.OpenCodePreferences(application)
    val openCodeBackend = com.example.gemini.data.agent.opencode.OpenCodeChatBackend(application, viewModelScope, store, openCodePrefs)

    /** Codex: one `codex app-server` on the bridge for all its chats (OpenAI's Codex CLI, ChatGPT sign-in). */
    val codexPrefs = com.example.gemini.data.agent.codex.CodexPreferences(application)
    val codexBackend = com.example.gemini.data.agent.codex.CodexChatBackend(application, viewModelScope, store, codexPrefs)
    /** Dictation for Codex chats: the phone's own speech recognizer, in the device's language (Codex has none of its own). */
    val codexVoice = com.example.gemini.data.jarvis.AndroidSpeechTranscriber(application) { java.util.Locale.getDefault().toLanguageTag() }

    /** Agent of the conversation on screen; chat actions go to that agent's backend. */
    val activeAgent: StateFlow<AgentKind> = store.currentConversation
        .map { it?.agent ?: AgentKind.AGY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentKind.AGY)

    private val isClaudeActive get() = store.currentConversation.value?.agent == AgentKind.CLAUDE
    private val isOpenCodeActive get() = store.currentConversation.value?.agent == AgentKind.OPENCODE
    private val isCodexActive get() = store.currentConversation.value?.agent == AgentKind.CODEX

    /** Agent used for new chats, picked on the empty chat screen (remembered). */
    val newChatAgent: StateFlow<AgentKind> = claudePrefs.newChatAgent

    private val agentPrefs = com.example.gemini.data.agent.AgentPreferences(application)
    /** Agents the app runs (Antigravity only until the user chooses). */
    val enabledAgents: StateFlow<Set<AgentKind>> = agentPrefs.enabled
    /** The first-launch agent choice was made. */
    val hasChosenAgents: StateFlow<Boolean> = agentPrefs.hasChosen

    private val _isApplyingAgents = MutableStateFlow(false)
    /** The bridge is restarting for a new agent selection. */
    val isApplyingAgents: StateFlow<Boolean> = _isApplyingAgents.asStateFlow()

    private val isClaudeEnabled get() = AgentKind.CLAUDE in enabledAgents.value
    private val isOpenCodeEnabled get() = AgentKind.OPENCODE in enabledAgents.value
    private val isCodexEnabled get() = AgentKind.CODEX in enabledAgents.value

    /**
     * Applies an agent selection: saves it, starts / stops Claude Code in the app, leaves a chat of a disabled
     * agent, and restarts the bridge when its flags change (a disabled agent never runs there).
     */
    fun applyAgents(agents: Set<AgentKind>) {
        if (agents.isEmpty()) return
        val before = enabledAgents.value
        val firstChoice = !hasChosenAgents.value
        agentPrefs.setEnabled(agents)
        if (newChatAgent.value !in agents) claudePrefs.setNewChatAgent(if (AgentKind.AGY in agents) AgentKind.AGY else agents.first())
        if (AgentKind.CLAUDE in agents) {
            claudeBackend.start()
            claudeAccount.refreshStatus()
        } else {
            claudeBackend.stop()
        }
        if (AgentKind.OPENCODE in agents) openCodeBackend.start() else openCodeBackend.stop()
        if (AgentKind.CODEX in agents) codexBackend.start() else codexBackend.stop()
        val cur = store.currentConversation.value
        if (cur == null || cur.agent !in agents) startNewChat()
        if (firstChoice) {
            // first launch: the server waited for this choice and now starts with it. If something started it already
            // (with the default agents, i.e. Antigravity only), restart it so the chosen agents run.
            val server = com.example.gemini.data.local.LocalServerManager
            val alreadyUp = server.status.value.let {
                it is com.example.gemini.data.local.LocalServerStatus.Running || it is com.example.gemini.data.local.LocalServerStatus.Starting
            }
            if (alreadyUp && !com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode) {
                server.startServer(getApplication(), forceRestart = true)
            } else {
                server.autoStartOnAppLaunch(getApplication())
            }
        } else if (before != agents) {
            // stop the server completely, then restart the app: it starts the server again with the new agents
            viewModelScope.launch {
                _isApplyingAgents.value = true
                runCatching { com.example.gemini.data.local.LocalServerManager.stopServerCompletely() }
                val app = getApplication<Application>()
                app.packageManager.getLaunchIntentForPackage(app.packageName)?.component?.let { component ->
                    app.startActivity(android.content.Intent.makeRestartActivityTask(component))
                }
                Runtime.getRuntime().exit(0)
            }
        }
    }

    private val noQuotas = MutableStateFlow<List<ModelQuota>>(emptyList())
    private val alwaysFalse = MutableStateFlow(false)

    private fun <T> byAgent(agy: StateFlow<T>, claude: StateFlow<T>, openCode: StateFlow<T>, codex: StateFlow<T>): StateFlow<T> =
        combine(store.currentConversation, agy, claude, openCode, codex) { conv, a, c, o, x ->
            when (conv?.agent) {
                AgentKind.CLAUDE -> c
                AgentKind.OPENCODE -> o
                AgentKind.CODEX -> x
                else -> a
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, agy.value)

    private val noQueued = MutableStateFlow<List<com.example.gemini.domain.model.QueuedMessage>>(emptyList())

    private fun agentOf(conversationId: String): AgentKind =
        allConversations.value.find { it.id == conversationId }?.agent
            ?: store.currentConversation.value?.takeIf { it.id == conversationId }?.agent
            ?: AgentKind.AGY

    private fun claudeNotSupported(action: String) {
        com.example.gemini.ui.components.AppToastHelper.showToast("$action is not available in Claude chats yet", com.example.gemini.ui.components.ChatToastType.INFO)
    }

    private fun openCodeNotSupported(action: String) {
        com.example.gemini.ui.components.AppToastHelper.showToast("$action is not available in OpenCode chats yet", com.example.gemini.ui.components.ChatToastType.INFO)
    }

    private fun codexNotSupported(action: String) {
        com.example.gemini.ui.components.AppToastHelper.showToast("$action is not available in Codex chats yet", com.example.gemini.ui.components.ChatToastType.INFO)
    }

    fun setNewChatAgent(agent: AgentKind) {
        claudePrefs.setNewChatAgent(agent)
        val cur = store.currentConversation.value
        val isFreshChat = cur == null || (cur.title == "New Chat" && messages.value.isEmpty())
        if (isFreshChat && cur?.agent != agent) startNewChat()
    }

    // ==================== ANTIGRAVITY (AGY) — delegated to data/agent/agy ====================

    val systemConnectionState = agyBackend.systemConnectionState
    val hubStatus = agyBackend.hubStatus
    val trajectoryEngine = agyBackend.trajectoryEngine
    val speechManager = agyBackend.speechManager
    val hasReceivedInitialSync = agyBackend.hasReceivedInitialSync
    val isConversationsLoading = agyBackend.isConversationsLoading
    val artifacts = agyBackend.artifacts
    val pendingApprovals = agyBackend.pendingApprovals
    val isStreaming = byAgent(agyBackend.isStreaming, claudeBackend.isStreaming, openCodeBackend.isStreaming, codexBackend.isStreaming)
    val availableModels = byAgent(agyBackend.availableModels, claudeBackend.availableModels, openCodeBackend.availableModels, codexBackend.availableModels)
    val enabledModelIds = agyBackend.enabledModelIds
    val enabledModels = byAgent(agyBackend.enabledModels, claudeBackend.availableModels, openCodeBackend.availableModels, codexBackend.availableModels)
    val isRefreshingModels = byAgent(agyBackend.isRefreshingModels, claudeBackend.isRefreshingModels, openCodeBackend.isRefreshingModels, codexBackend.isRefreshingModels)
    val selectedModelId = byAgent(agyBackend.selectedModelId, claudeBackend.selectedModelId, openCodeBackend.selectedModelId, codexBackend.selectedModelId)
    val preferredModelName = agyBackend.preferredModelName
    /**
     * Claude's plan limits as the model pill's quota: the 5-hour limit, or the weekly one (the user's choice in the
     * model sheet). A model with its own weekly share (Fable) shows the tighter of its share and the all-models limit.
     */
    private val claudeQuotas: StateFlow<List<ModelQuota>> =
        combine(
            claudeBackend.selectedModelId,
            claudeBackend.rateLimits,
            claudeAccount.usage,
            com.example.gemini.domain.model.ModelGroupPrefs.overrides(com.example.gemini.domain.model.QuotaAgent.CLAUDE),
            com.example.gemini.domain.model.ModelGroupPrefs.pillWindow(com.example.gemini.domain.model.QuotaAgent.CLAUDE)
        ) { modelId, live, usage, overrides, pill ->
            // plan limits belong to Claude's own models; a custom provider's model has none
            if (com.example.gemini.data.agent.claude.ClaudeChatBackend.providerOf(modelId).isNotEmpty()) return@combine emptyList()
            val groups = com.example.gemini.data.agent.claude.ClaudeQuotaGroups.groups(usage, live)
            val group = com.example.gemini.data.agent.claude.ClaudeQuotaGroups.groupOf(modelId, claudeBackend.modelLabel(modelId), groups, overrides)
            listOfNotNull(group?.toModelQuota(modelId, pill))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Codex's plan limits as the model pill's quota (the account's limits apply to every model). */
    private val codexQuotas: StateFlow<List<ModelQuota>> = combine(codexBackend.selectedModelId, codexBackend.rateLimits) { modelId, limits ->
        val primary = limits?.primary ?: return@combine emptyList()
        fun countdown(resetsAt: Long?): String? = resetsAt?.let { s ->
            val left = s * 1000 - System.currentTimeMillis()
            when {
                left <= 0 -> "now"
                left >= 48 * 3_600_000L -> "${left / 86_400_000L}d"
                left >= 3_600_000L -> "${left / 3_600_000L}h ${(left % 3_600_000L) / 60_000L}m"
                else -> "${left / 60_000L}m"
            }
        }
        val secondary = limits.secondary
        listOf(
            ModelQuota(
                modelId = modelId,
                remainingFraction = (1f - (primary.usedPercent / 100.0).toFloat()).coerceIn(0f, 1f),
                usedPercentage = "${primary.usedPercent.toInt()}%",
                resetCountdown = countdown(primary.resetsAt),
                weeklyRemainingFraction = secondary?.let { (1f - (it.usedPercent / 100.0).toFloat()).coerceIn(0f, 1f) },
                weeklyUsedPercentage = secondary?.let { "${it.usedPercent.toInt()}%" },
                weeklyResetCountdown = secondary?.let { countdown(it.resetsAt) }
            )
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val quotas = byAgent(agyBackend.quotas, claudeQuotas, noQuotas, codexQuotas)
    val quotaSummary = agyBackend.quotaSummary
    val isLoadingConversation = byAgent(agyBackend.isLoadingConversation, claudeBackend.isLoadingConversation, openCodeBackend.isLoadingConversation, codexBackend.isLoadingConversation)
    fun setModelEnabled(modelId: String, isEnabled: Boolean) = agyBackend.setModelEnabled(modelId, isEnabled)
    fun enableAllModels() = agyBackend.enableAllModels()
    val bridgeStatusMessage = agyBackend.bridgeStatusMessage
    val isServerOnline = agyBackend.isServerOnline
    val isReconnecting = agyBackend.isReconnecting
    val isBridgeOnline = agyBackend.isBridgeOnline
    fun checkBridgeHealth() = agyBackend.checkBridgeHealth()
    val agyAuthInfo = agyBackend.agyAuthInfo
    val isAuthBusy = agyBackend.isAuthBusy
    val authFeedbackMessage = agyBackend.authFeedbackMessage
    val pendingLoginUrl = agyBackend.pendingLoginUrl
    fun clearPendingLoginUrl() = agyBackend.clearPendingLoginUrl()
    val agyLoginIssue = agyBackend.agyLoginIssue
    fun clearAgyLoginIssue() = agyBackend.clearAgyLoginIssue()
    fun onDrawerOpened() = agyBackend.onDrawerOpened()
    fun cancelAgyLogin() = agyBackend.cancelAgyLogin()
    fun checkAgyAuthStatus(userInitiated: Boolean = false) = agyBackend.checkAgyAuthStatus(userInitiated)
    fun loginToAgyHub(force: Boolean = false) = agyBackend.loginToAgyHub(force)
    fun logoutFromAgyHub() = agyBackend.logoutFromAgyHub()
    val activeInstances = agyBackend.activeInstances
    /** An Antigravity voice call is on (it keeps the screen on, minimized too). */
    private val voiceCallActive = com.example.gemini.data.voice.AgyVoiceCall.state.map { it.active }.distinctUntilChanged()
    /**
     * Keeps the screen on: an Antigravity voice call is on, an Antigravity chat or task is working, a Claude Code chat is working (the one on screen, or
     * any other the session list shows as running), a Jarvis task runs, or Jarvis' keep-screen-on setting asks for it.
     */
    val isAnyGenerationOrTaskActive: StateFlow<Boolean> = combine(
        agyBackend.isAnyGenerationOrTaskActive,
        claudeBackend.isCurrentChatActivelyRunning,
        claudeBackend.conversations,
        combine(
            com.example.gemini.data.jarvis.JarvisController.keepScreenAwake, openCodeBackend.isCurrentChatActivelyRunning, openCodeBackend.conversations,
            codexBackend.isCurrentChatActivelyRunning, codexBackend.conversations
        ) { j, ocTurn, ocChats, cxTurn, cxChats ->
            j || ocTurn || ocChats.any { it.isRunning } || cxTurn || cxChats.any { it.isRunning }
        },
        com.example.gemini.data.jarvis.JarvisTasks.anyActive
    ) { agy, claudeTurn, claudeChats, jarvisOrOpenCode, tasks ->
        agy || claudeTurn || claudeChats.any { it.isRunning } || jarvisOrOpenCode || tasks
    }.combine(voiceCallActive) { work, call -> work || call }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Why the screen is kept on, one line per cause (same conditions as [isAnyGenerationOrTaskActive]); shown when
     * the screen-on icon in the chat bar is tapped.
     */
    val screenAwakeReasons: StateFlow<List<String>> = combine(
        combine(agyBackend.conversations, agyBackend.activeInstances, ::Pair),
        combine(claudeBackend.isCurrentChatActivelyRunning, claudeBackend.currentConversation, ::Pair),
        claudeBackend.conversations,
        com.example.gemini.data.jarvis.JarvisController.keepScreenAwake,
        com.example.gemini.data.jarvis.JarvisTasks.anyActive
    ) { (agyChats, instances), (claudeTurn, current), claudeChats, jarvis, tasks ->
        fun names(titles: List<String>): String {
            val shown = titles.map { "“${it.ifBlank { "Untitled chat" }.take(40)}”" }.take(2).joinToString(", ")
            return if (titles.size > 2) "$shown and ${titles.size - 2} more" else shown
        }
        buildList {
            val agyWorking = agyChats.filter { it.isRunning }
            val agyBackground = agyChats.filter { !it.isRunning && (it.notFullyIdle || it.hasActivity) }
            if (agyWorking.isNotEmpty()) add("Antigravity is working in ${names(agyWorking.map { it.title })}")
            if (agyBackground.isNotEmpty()) add("Antigravity has background work in ${names(agyBackground.map { it.title })}")
            val listed = (agyWorking + agyBackground).map { it.id }.toSet()
            val otherInstances = instances.count { it.conversationId !in listed }
            if (otherInstances > 0) add(if (otherInstances == 1) "An Antigravity instance is running" else "$otherInstances Antigravity instances are running")
            val claudeOpen = current?.takeIf { claudeTurn && it.agent == AgentKind.CLAUDE }
            if (claudeOpen != null) add("Claude is replying in ${names(listOf(claudeOpen.title))}")
            val claudeOthers = claudeChats.filter { it.isRunning && it.id != claudeOpen?.id }
            if (claudeOthers.isNotEmpty()) add("Claude is working in ${names(claudeOthers.map { it.title })}")
            if (claudeTurn && claudeOpen == null && claudeOthers.isEmpty()) add("Claude is replying")
            val openCodeChats = openCodeBackend.conversations.value.filter { it.isRunning }
            if (openCodeChats.isNotEmpty()) add("OpenCode is working in ${names(openCodeChats.map { it.title })}")
            else if (openCodeBackend.isCurrentChatActivelyRunning.value) add("OpenCode is replying")
            val codexChats = codexBackend.conversations.value.filter { it.isRunning }
            if (codexChats.isNotEmpty()) add("Codex is working in ${names(codexChats.map { it.title })}")
            else if (codexBackend.isCurrentChatActivelyRunning.value) add("Codex is replying")
            if (tasks) add("A Jarvis task is running")
            if (jarvis) add(
                when (com.example.gemini.data.jarvis.JarvisPreferences.keepScreenOn.value) {
                    "active" -> "Jarvis is active (Jarvis setting: keep the screen on while it's active)"
                    else -> "Jarvis is on (Jarvis setting: keep the screen on while Jarvis is on)"
                }
            )
        }
    }.combine(voiceCallActive) { reasons, call -> if (call) listOf("A voice call with Antigravity is on") + reasons else reasons }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val isCurrentChatActivelyRunning = byAgent(agyBackend.isCurrentChatActivelyRunning, claudeBackend.isCurrentChatActivelyRunning, openCodeBackend.isCurrentChatActivelyRunning, codexBackend.isCurrentChatActivelyRunning)
    val isCurrentChatBackgroundActive = byAgent(agyBackend.isCurrentChatBackgroundActive, alwaysFalse, alwaysFalse, alwaysFalse)
    val connectionState = agyBackend.connectionState
    fun refreshActiveInstances() = agyBackend.refreshActiveInstances()
    fun terminateInstance(conversationId: String) = agyBackend.terminateInstance(conversationId)
    fun syncAgyConversations(force: Boolean = false) = agyBackend.syncAgyConversations(force)
    fun retryConnections() {
        agyBackend.retryConnections()
        if (isClaudeActive) claudeBackend.retryConnections()
        if (isOpenCodeEnabled) openCodeBackend.retryConnections()
        if (isCodexEnabled) codexBackend.retryConnections()
    }
    fun onAppForegrounded() {
        agyBackend.onAppForegrounded()
        if (isClaudeEnabled) {
            claudeBackend.onAppForegrounded()
            // the CLI may have been installed or signed in from a terminal meanwhile
            if (claudeAccount.state.value != com.example.gemini.data.agent.claude.ClaudeStatus.READY) claudeAccount.refreshStatus()
        }
        if (isOpenCodeEnabled) openCodeBackend.onAppForegrounded()
        if (isCodexEnabled) codexBackend.onAppForegrounded()
    }
    fun onProjectChanged(projectPath: String) = agyBackend.onProjectChanged(projectPath)
    fun onUserStartedTyping() = agyBackend.onUserStartedTyping()
    fun startNewChat() = startNewChat(
        newChatAgent.value.takeIf { it in enabledAgents.value }
            ?: if (AgentKind.AGY in enabledAgents.value) AgentKind.AGY else enabledAgents.value.firstOrNull() ?: AgentKind.AGY
    )

    /** New chat with [agent] (e.g. `/clear` in a Claude chat), without changing the new-chat default. */
    fun startNewChat(agent: AgentKind) {
        when (agent) {
            AgentKind.CLAUDE -> {
                agyBackend.detach()
                openCodeBackend.detach()
                codexBackend.detach()
                claudeBackend.startNewChat()
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach()
                claudeBackend.detach()
                codexBackend.detach()
                openCodeBackend.startNewChat()
            }
            AgentKind.CODEX -> {
                agyBackend.detach()
                claudeBackend.detach()
                openCodeBackend.detach()
                codexBackend.startNewChat()
            }
            AgentKind.AGY -> {
                claudeBackend.detach()
                openCodeBackend.detach()
                codexBackend.detach()
                agyBackend.startNewChat()
            }
        }
    }
    fun selectConversation(id: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> {
                agyBackend.detach()
                openCodeBackend.detach()
                codexBackend.detach()
                claudeBackend.selectConversation(id)
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach()
                claudeBackend.detach()
                codexBackend.detach()
                openCodeBackend.selectConversation(id)
            }
            AgentKind.CODEX -> {
                agyBackend.detach()
                claudeBackend.detach()
                openCodeBackend.detach()
                codexBackend.selectConversation(id)
            }
            AgentKind.AGY -> {
                claudeBackend.detach()
                openCodeBackend.detach()
                codexBackend.detach()
                agyBackend.selectConversation(id)
            }
        }
    }
    fun startPersistentStream(conversationId: String) = agyBackend.startPersistentStream(conversationId)
    fun deleteConversation(id: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> claudeBackend.deleteConversation(id)
            AgentKind.OPENCODE -> openCodeBackend.deleteConversation(id)
            AgentKind.CODEX -> codexBackend.deleteConversation(id)
            AgentKind.AGY -> agyBackend.deleteConversation(id)
        }
        // files the app copied for this chat go with it (never the user's own files picked in Files)
        val copies = com.example.gemini.data.files.AttachedFiles.takeCopiesOf(getApplication(), id)
        if (copies.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { com.example.gemini.data.files.FileExplorerApi.delete(copies) }
        // Jarvis forgets it too: a deleted Jarvis chat is replaced by a new one on the next message
        com.example.gemini.data.jarvis.JarvisController.onChatDeleted(id)
    }
    fun forkConversation(id: String) {
        if (agentOf(id) == AgentKind.CODEX) {
            agyBackend.detach()
            claudeBackend.detach()
            openCodeBackend.detach()
            codexBackend.forkConversation(id)
        } else if (agentOf(id) == AgentKind.OPENCODE) {
            agyBackend.detach()
            claudeBackend.detach()
            openCodeBackend.forkConversation(id)
        } else if (agentOf(id) == AgentKind.CLAUDE) {
            agyBackend.detach()
            claudeBackend.forkConversation(id)
        } else {
            agyBackend.forkConversation(id)
        }
    }
    fun selectModel(modelId: String) = when {
        isClaudeActive -> claudeBackend.selectModel(modelId)
        isOpenCodeActive -> openCodeBackend.selectModel(modelId)
        isCodexActive -> codexBackend.selectModel(modelId)
        else -> agyBackend.selectModel(modelId)
    }
    /**
     * `/plu:` plugin skills and custom slash commands are expanded here, for every agent. Attached files go along as a
     * hidden list of their paths at the end (the agent opens them in place). Sends once copies still running are done.
     */
    fun sendMessage(content: String) {
        val pending = uploadJobs.values.toList()
        if (pending.isEmpty() && !_isUploadingAttachment.value) {
            sendNow(content)
            return
        }
        viewModelScope.launch {
            for (job in pending) job.join()
            _isUploadingAttachment.first { !it }
            sendNow(content)
        }
    }

    private fun sendNow(content: String) {
        val refs = com.example.gemini.data.files.AttachedFiles.refsOf(_attachments.value)
        val typed = PromptExpansion.expand(content)
        val expanded = if (refs.isEmpty()) typed
            else listOf(typed, com.example.gemini.data.files.AttachedFiles.block(refs)).filter { it.isNotBlank() }.joinToString("\n\n")
        // a Jarvis chat on screen: its message carries Jarvis' hidden note (or goes to the voice Jarvis chat as a typed turn)
        val jarvis = com.example.gemini.data.jarvis.JarvisController
        val chatId = currentConversation.value?.id
        val intercepted = (if (jarvis.isAttached) jarvis.interceptScreenMessage(chatId, expanded) else expanded) ?: return
        rememberCopies(chatId, refs)
        // a normal chat (a Jarvis chat carries its own guide instead): the app's global context goes in front
        val it = if (intercepted === expanded) withGlobalContext(chatId, expanded) else intercepted
        when {
            isClaudeActive -> claudeBackend.sendMessage(it)
            isOpenCodeActive -> openCodeBackend.sendMessage(it)
            isCodexActive -> codexBackend.sendMessage(it)
            else -> agyBackend.sendMessage(it)
        }
    }

    /** The copies a sent message uses are kept under its chat (a new chat once it has its id), so deleting the chat deletes them. */
    private fun rememberCopies(chatId: String?, refs: List<com.example.gemini.data.files.AttachedFiles.Ref>) {
        // sent: these copies now belong to the chat, not to chips that could still be removed
        uploadFolders.keys.removeAll(_attachments.value.map { it.id }.toSet())
        if (refs.none { com.example.gemini.data.files.AttachedFiles.isUploadCopy(it.path) }) return
        val app = getApplication<Application>()
        if (!chatId.isNullOrBlank()) {
            com.example.gemini.data.files.AttachedFiles.rememberCopies(app, chatId, refs)
            return
        }
        viewModelScope.launch {
            withTimeoutOrNull(120_000) { currentConversation.first { !it?.id.isNullOrBlank() } }
                ?.id?.let { com.example.gemini.data.files.AttachedFiles.rememberCopies(app, it, refs) }
        }
    }
    /** Settings → Agents → Global context, hidden in front of the message; a new chat is remembered once it has its id. */
    private fun withGlobalContext(chatId: String?, text: String): String {
        val hasEarlier = messages.value.any { m -> m.role == MessageRole.USER }
        val (prompt, fingerprint) = com.example.gemini.data.context.GlobalContext.wrap(chatId, hasEarlier, text)
        if (fingerprint != null && chatId.isNullOrBlank()) {
            viewModelScope.launch {
                kotlinx.coroutines.withTimeoutOrNull(120_000) { currentConversation.first { !it?.id.isNullOrBlank() } }
                    ?.id?.let { com.example.gemini.data.context.GlobalContext.markSent(it, fingerprint) }
            }
        }
        return prompt
    }

    /** Editing the first message drops the hidden context with it: send it again with the next message. */
    private fun forgetContextIfFirst(targetId: String) {
        val firstUser = messages.value.firstOrNull { it.role == MessageRole.USER } ?: return
        if (firstUser.id == targetId) com.example.gemini.data.context.GlobalContext.forget(currentConversation.value?.id)
    }

    val isTranscribingAudio = agyBackend.isTranscribingAudio
    fun transcribeAudioFile(file: java.io.File, onDone: (String) -> Unit, onError: (String) -> Unit) = agyBackend.transcribeAudioFile(file, onDone, onError)
    fun retryMessage(messageId: String) = when {
        isClaudeActive -> claudeBackend.regenerate(messageId)
        isOpenCodeActive -> openCodeBackend.regenerate(messageId)
        isCodexActive -> codexBackend.regenerate(messageId)
        else -> agyBackend.retryMessage(messageId)
    }
    fun prepareEditMessage(messageId: String): String? {
        if (isCodexActive) {
            codexNotSupported("Editing a sent message")
            return null
        }
        if (isOpenCodeActive) {
            openCodeNotSupported("Editing a sent message")
            return null
        }
        if (isClaudeActive) {
            claudeNotSupported("Editing a sent message")
            return null
        }
        return agyBackend.prepareEditMessage(messageId)?.let { restoreAttachedFiles(it); editableText(it) }
    }
    fun revertAndEditLastUserMessage(targetMsg: ChatMessage, onRestored: (String) -> Unit) {
        forgetContextIfFirst(targetMsg.id)
        val restored: (String) -> Unit = { restoreAttachedFiles(it); onRestored(editableText(it)) }
        if (isCodexActive) codexBackend.editMessage(targetMsg.id, restored)
        else if (isOpenCodeActive) openCodeBackend.editMessage(targetMsg.id, restoreFiles = false, restored)
        else if (isClaudeActive) claudeBackend.editMessage(targetMsg.id, restoreCode = false, restored)
        else agyBackend.revertAndEditLastUserMessage(targetMsg, restored)
    }

    /** Claude: edit a sent prompt in a copy of the chat that ends before it; optionally restore the files changed since. */
    fun editClaudeMessage(targetMsg: ChatMessage, restoreCode: Boolean, onRestored: (String) -> Unit) {
        val restored: (String) -> Unit = { restoreAttachedFiles(it); onRestored(editableText(it)) }
        if (isCodexActive) codexBackend.editMessage(targetMsg.id, restored)
        else if (isOpenCodeActive) openCodeBackend.editMessage(targetMsg.id, restoreCode, restored)
        else claudeBackend.editMessage(targetMsg.id, restoreCode, restored)
    }

    /** Sidebar export / share of a Claude chat that is not open. */
    suspend fun loadClaudeMessages(conversationId: String): List<ChatMessage> = claudeBackend.loadMessages(conversationId)

    /** Sidebar export / share of an OpenCode chat that is not open. */
    suspend fun loadOpenCodeMessages(conversationId: String): List<ChatMessage> = openCodeBackend.loadMessages(conversationId)

    /** Sidebar export / share of a Codex chat that is not open. */
    suspend fun loadCodexMessages(conversationId: String): List<ChatMessage> = codexBackend.loadMessages(conversationId)

    fun renameConversation(id: String, title: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> claudeBackend.renameConversation(id, title)
            AgentKind.OPENCODE -> openCodeBackend.renameConversation(id, title)
            AgentKind.CODEX -> codexBackend.renameConversation(id, title)
            AgentKind.AGY -> agyBackend.renameConversation(id, title)
        }
    }

    /** Archives / unarchives a chat, each agent the way it stores it (AGY annotations, OpenCode session, Claude bridge list). */
    fun setConversationArchived(id: String, archived: Boolean) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> claudeBackend.setArchived(id, archived)
            AgentKind.OPENCODE -> openCodeBackend.setArchived(id, archived)
            AgentKind.CODEX -> codexBackend.setArchived(id, archived)
            AgentKind.AGY -> agyBackend.setArchived(id, archived)
        }
    }

    /** Sidebar archive view (archived chats only). Kept in memory only: the app always starts on the normal list. */
    private val _showArchivedChats = MutableStateFlow(false)
    val showArchivedChats: StateFlow<Boolean> = _showArchivedChats.asStateFlow()
    fun setShowArchivedChats(on: Boolean) { _showArchivedChats.value = on }
    fun stopStreaming() = when {
        isClaudeActive -> claudeBackend.stopStreaming()
        isOpenCodeActive -> openCodeBackend.stopStreaming()
        isCodexActive -> codexBackend.stopStreaming()
        else -> agyBackend.stopStreaming()
    }

    /** Messages sent while the agent was working, waiting to be delivered (queued-messages card). */
    val queuedMessages = byAgent(agyBackend.queuedMessages, claudeBackend.queuedMessages, noQueued, codexBackend.queuedMessages)
    fun sendQueuedNow(id: String) { if (isCodexActive) codexBackend.sendQueuedNow(id) else if (isClaudeActive) claudeBackend.sendQueuedNow(id) else if (!isOpenCodeActive) agyBackend.sendQueuedNow(id) }
    fun deleteQueued(id: String) { if (isCodexActive) codexBackend.deleteQueued(id) else if (!isClaudeActive && !isOpenCodeActive) agyBackend.deleteQueued(id) }
    fun applyQuotaSummary(summary: com.example.gemini.domain.model.QuotaSummaryResponse) = agyBackend.applyQuotaSummary(summary)
    fun refreshQuotas(force: Boolean = true, showToastFeedback: Boolean = false) {
        if (isCodexActive) {
            codexBackend.refreshModels()
            viewModelScope.launch { codexBackend.refreshRateLimits() }
        } else if (isOpenCodeActive) {
            openCodeBackend.refreshModels()
        } else if (isClaudeActive) {
            claudeBackend.refreshInfo(force)
            agyBackend.refreshQuotas(force, showToastFeedback = false)
        } else {
            agyBackend.refreshQuotas(force, showToastFeedback)
        }
    }
    fun approveAndExecuteTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, scope: String? = null) = if (isCodexActive) codexBackend.approveTool(toolCall) else if (isOpenCodeActive) openCodeBackend.approveTool(toolCall) else if (isClaudeActive) claudeBackend.approveTool(toolCall) else agyBackend.approveAndExecuteTerminalTool(toolCall, messageId, scope)
    fun rejectTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, reason: String? = null) = if (isCodexActive) codexBackend.rejectTool(toolCall, reason) else if (isOpenCodeActive) openCodeBackend.rejectTool(toolCall, reason) else if (isClaudeActive) claudeBackend.rejectTool(toolCall, reason) else agyBackend.rejectTerminalTool(toolCall, messageId, reason)
    fun approveAllPendingTools(list: List<PendingToolApproval>) = if (isCodexActive) list.forEach { codexBackend.approveTool(it.toolCall) } else if (isOpenCodeActive) list.forEach { openCodeBackend.approveTool(it.toolCall) } else if (isClaudeActive) list.forEach { claudeBackend.approveTool(it.toolCall) } else agyBackend.approveAllPendingTools(list)
    fun rejectAllPendingTools(list: List<PendingToolApproval>, reason: String? = null) = if (isCodexActive) list.forEach { codexBackend.rejectTool(it.toolCall, reason) } else if (isOpenCodeActive) list.forEach { openCodeBackend.rejectTool(it.toolCall, reason) } else if (isClaudeActive) list.forEach { claudeBackend.rejectTool(it.toolCall, reason) } else agyBackend.rejectAllPendingTools(list, reason)
    fun terminateRunningTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) = if (isCodexActive) codexBackend.stopStreaming() else if (isOpenCodeActive) openCodeBackend.stopStreaming() else if (isClaudeActive) claudeBackend.stopStreaming() else agyBackend.terminateRunningTerminalTool(toolCall, messageId)
    fun proceedAfterTermination(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, userInstructions: String?) {
        if (isClaudeActive || isOpenCodeActive || isCodexActive) {
            store.terminatedToolDialog.value = null
            return
        }
        agyBackend.proceedAfterTermination(toolCall, messageId, userInstructions)
    }
    fun submitUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>, summaryDisplay: String) = if (isCodexActive) codexBackend.submitChoices(toolCall, responses) else if (isOpenCodeActive) openCodeBackend.submitChoices(toolCall, responses) else if (isClaudeActive) claudeBackend.submitChoices(toolCall, responses, summaryDisplay) else agyBackend.submitUserChoices(toolCall, messageId, responses, summaryDisplay)
    fun skipUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto> = emptyList()) = if (isCodexActive) codexBackend.cancelChoices(toolCall) else if (isOpenCodeActive) openCodeBackend.cancelChoices(toolCall) else if (isClaudeActive) claudeBackend.skipChoices(toolCall) else agyBackend.skipUserChoices(toolCall, messageId, responses)
    fun cancelUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) = if (isCodexActive) codexBackend.cancelChoices(toolCall) else if (isOpenCodeActive) openCodeBackend.cancelChoices(toolCall) else if (isClaudeActive) claudeBackend.cancelChoices(toolCall) else agyBackend.cancelUserChoices(toolCall, messageId)

    val mcpServers = mcpManager.mcpServers
    val isMcpLoading = mcpManager.isMcpLoading
    val isMcpRefreshing = mcpManager.isMcpRefreshing
    val refreshingMcpServer = mcpManager.refreshingMcpServer
    val mcpErrorMessage = mcpManager.mcpErrorMessage
    val mcpStatusMessage = mcpManager.mcpStatusMessage
    fun clearMcpStatus() = mcpManager.clearMcpStatus()
    val availableCascadePlugins = mcpManager.availableCascadePlugins
    val isCascadePluginsLoading = mcpManager.isCascadePluginsLoading
    val installingCascadePluginId = mcpManager.installingCascadePluginId
    fun loadMcpServers() = mcpManager.loadMcpServers()
    fun refreshMcpServers(targetServer: String? = null) = mcpManager.refreshMcpServers(targetServer)
    fun toggleMcpServer(serverName: String, enabled: Boolean) = mcpManager.toggleMcpServer(serverName, enabled)
    fun saveMcpServer(spec: com.example.gemini.domain.model.McpServerSpec, rawJsonString: String? = null) = mcpManager.saveMcpServer(spec, rawJsonString)
    fun deleteMcpServer(serverName: String) = mcpManager.deleteMcpServer(serverName)
    fun loadAvailableCascadePlugins(query: String = "") = mcpManager.loadAvailableCascadePlugins(query)
    fun installCascadeMcpPlugin(plugin: com.example.gemini.data.remote.dto.AvailableCascadePluginDto) = mcpManager.installCascadeMcpPlugin(plugin)

    val allSkills = pluginsManager.allSkills
    val isSkillsLoading = pluginsManager.isSkillsLoading
    val skillsFilterScope = pluginsManager.skillsFilterScope
    val installedPlugins = pluginsManager.installedPlugins
    val isInstalledPluginsLoading = pluginsManager.isInstalledPluginsLoading
    val googlePluginsCatalog = pluginsManager.googlePluginsCatalog
    val isGooglePluginsLoading = pluginsManager.isGooglePluginsLoading
    val installingGooglePluginId = pluginsManager.installingGooglePluginId
    val deletingPluginId = pluginsManager.deletingPluginId
    val pluginActionStatusMessage = pluginsManager.pluginActionStatusMessage
    val pluginActionErrorMessage = pluginsManager.pluginActionErrorMessage
    fun clearPluginActionStatus() = pluginsManager.clearPluginActionStatus()
    fun setSkillsFilterScope(scope: String) = pluginsManager.setSkillsFilterScope(scope)
    fun loadAllSkills(scope: String = skillsFilterScope.value) = pluginsManager.loadAllSkills(scope)
    fun loadAllInstalledPlugins() = pluginsManager.loadAllInstalledPlugins()
    fun loadGooglePluginsCatalog() = pluginsManager.loadGooglePluginsCatalog()
    fun installGooglePlugin(pluginId: String, pluginName: String = pluginId) = pluginsManager.installGooglePlugin(pluginId, pluginName)
    fun deleteInstalledPlugin(pluginId: String, pluginName: String = pluginId) = pluginsManager.deleteInstalledPlugin(pluginId, pluginName)

    val globalSecuritySettings = securityManager.globalSecuritySettings
    val globalSettingsError = securityManager.globalSettingsError
    val isGlobalSettingsLoading = securityManager.isGlobalSettingsLoading
    val projectsList = securityManager.projectsList
    val isProjectsLoading = securityManager.isProjectsLoading
    fun loadSecurityAndProjectSettings() = securityManager.loadSecurityAndProjectSettings()
    fun addGlobalPermissionGrant(action: String, pattern: String, decision: String) = securityManager.addGlobalPermissionGrant(action, pattern, decision)
    fun removeGlobalPermissionGrant(rawRule: String) = securityManager.removeGlobalPermissionGrant(rawRule)
    fun changeGlobalPermissionGrantDecision(rawRule: String, newDecision: String) = securityManager.changeGlobalPermissionGrantDecision(rawRule, newDecision)
    fun updateGlobalArtifactReviewMode(mode: String) = securityManager.updateGlobalArtifactReviewMode(mode)
    fun updateGlobalSecurityPreset(autoExec: String, fileAccess: String) = securityManager.updateGlobalSecurityPreset(autoExec, fileAccess)
    fun updateGlobalCustomTerminalPolicy(policy: String) = securityManager.updateGlobalCustomTerminalPolicy(policy)
    fun updateGlobalCustomFileAccessPolicy(policy: String) = securityManager.updateGlobalCustomFileAccessPolicy(policy)
    fun updateGlobalTerminalSandbox(enabled: Boolean) = securityManager.updateGlobalTerminalSandbox(enabled)
    fun setProjectInheritGlobal(project: com.example.gemini.data.remote.AgyHubClient.ProjectItem) = securityManager.setProjectInheritGlobal(project)
    fun updateProjectPreset(project: com.example.gemini.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String, artifactReview: String? = null) = securityManager.updateProjectPreset(project, autoExec, fileAccess, artifactReview)
    fun setCommandAutoExecutionPolicy(policy: String) = securityManager.setCommandAutoExecutionPolicy(policy)
    fun setCommandSandboxEnabled(enabled: Boolean) = securityManager.setCommandSandboxEnabled(enabled)

    private val oauthManager = GoogleOAuthManager()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _conversations = store.conversations
    /** Every conversation of the enabled agents, Jarvis' own chats included. */
    val allConversations: StateFlow<List<Conversation>> = combine(_conversations, claudeBackend.conversations, openCodeBackend.conversations, codexBackend.conversations, enabledAgents) { agy, claude, openCode, codex, enabled ->
        // a disabled agent's chats stay on disk but are not listed
        ((if (AgentKind.AGY in enabled) agy else emptyList()) + (if (AgentKind.CLAUDE in enabled) claude else emptyList()) +
            (if (AgentKind.OPENCODE in enabled) openCode else emptyList()) + (if (AgentKind.CODEX in enabled) codex else emptyList()))
            .sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Sidebar list: every agent's conversations together, newest first. Jarvis' chats are listed under Jarvis; they also
     * show here when the user chose so (Settings → Jarvis: none, their own Jarvis text chats, or all Jarvis chats).
     */
    val conversations: StateFlow<List<Conversation>> = combine(
        allConversations, com.example.gemini.data.jarvis.JarvisPreferences.chatIds, com.example.gemini.data.jarvis.JarvisPreferences.enabled.state,
        com.example.gemini.data.jarvis.JarvisPreferences.jarvisInAll.state, com.example.gemini.data.jarvis.JarvisManagers.managers
    ) { all, jarvisIds, jarvisOn, inAll, managers ->
        when {
            !jarvisOn || jarvisIds.isEmpty() || inAll == "all" -> all
            inAll == "text" -> all.filter { it.id !in jarvisIds || it.id in managers.keys }
            else -> all.filter { it.id !in jarvisIds }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The Jarvis filter in the sidebar: the chats Jarvis opened. */
    val jarvisConversations: StateFlow<List<Conversation>> = combine(
        allConversations, com.example.gemini.data.jarvis.JarvisPreferences.chatIds, com.example.gemini.data.jarvis.JarvisPreferences.enabled.state
    ) { all, jarvisIds, jarvisOn ->
        if (!jarvisOn) emptyList() else all.filter { it.id in jarvisIds }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentConversation = store.currentConversation
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _sharedConversationPreview = MutableStateFlow<com.example.gemini.ui.components.SharedConversationData?>(null)
    val sharedConversationPreview: StateFlow<com.example.gemini.ui.components.SharedConversationData?> = _sharedConversationPreview.asStateFlow()

    private val _isSharedConversationLoading = MutableStateFlow(false)
    val isSharedConversationLoading: StateFlow<Boolean> = _isSharedConversationLoading.asStateFlow()

    private val _incomingMarkdownPreview = MutableStateFlow<Pair<String, String>?>(null)
    val incomingMarkdownPreview: StateFlow<Pair<String, String>?> = _incomingMarkdownPreview.asStateFlow()

    val requestedViewMode = MutableStateFlow<String?>(null)

    fun requestViewMode(mode: String) {
        requestedViewMode.value = mode
    }

    fun consumeRequestedViewMode() {
        requestedViewMode.value = null
    }

    /** Antigravity's chat state (shared store); Claude Code keeps its own, see [messages]. */
    private val _messages = store.messages
    /** Messages of the chat on screen, from the agent that owns it. */
    val messages: StateFlow<List<ChatMessage>> = byAgent(_messages, claudeBackend.messages, openCodeBackend.messages, codexBackend.messages)

    private val _thinkingPreference = MutableStateFlow(com.example.gemini.domain.model.ThinkingPreference())
    val thinkingPreference: StateFlow<com.example.gemini.domain.model.ThinkingPreference> = _thinkingPreference.asStateFlow()

    private val _userEmail = store.userEmail
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    val isOAuthServerListening: StateFlow<Boolean> = oauthManager.isServerListening.asStateFlow()
    private val _isOAuthServerLoading = MutableStateFlow(false)
    val isOAuthServerLoading: StateFlow<Boolean> = _isOAuthServerLoading.asStateFlow()

    private val _projectId = MutableStateFlow("rising-fact-p41fc")
    val projectId: StateFlow<String> = _projectId.asStateFlow()

    private val _tier = MutableStateFlow("pro")
    val tier: StateFlow<String> = _tier.asStateFlow()

    private val _contextWindowLimit = MutableStateFlow(10)
    val contextWindowLimit: StateFlow<Int> = _contextWindowLimit.asStateFlow()

    private val _summaryModelIdPref = MutableStateFlow("always_ask")
    val summaryModelIdPref: StateFlow<String> = _summaryModelIdPref.asStateFlow()

    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _summarizingModelName = MutableStateFlow("")
    val summarizingModelName: StateFlow<String> = _summarizingModelName.asStateFlow()

    private val _summaryError = MutableStateFlow<String?>(null)
    val summaryError: StateFlow<String?> = _summaryError.asStateFlow()

    private val _pendingQueuedUserMessage = MutableStateFlow<String?>(null)
    val pendingQueuedUserMessage: StateFlow<String?> = _pendingQueuedUserMessage.asStateFlow()

    private val _postponedThreshold = MutableStateFlow<Int?>(null)
    val postponedThreshold: StateFlow<Int?> = _postponedThreshold.asStateFlow()

    private val _attachments = store.attachments
    val attachments: StateFlow<List<com.example.gemini.domain.model.ChatAttachment>> = _attachments.asStateFlow()

    private val _isUploadingAttachment = MutableStateFlow(false)
    /** True while picked images are being prepared (shrunk); sending waits for it. */
    val isUploadingAttachment: StateFlow<Boolean> = _isUploadingAttachment.asStateFlow()

    /** Files being copied to the bridge machine: attachment id → 0..1, shown on their chips. Sending waits for them. */
    private val _attachmentProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val attachmentProgress: StateFlow<Map<String, Float>> = _attachmentProgress.asStateFlow()
    private val uploadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** The copy folder made for each attachment picked here (deleted if the chip is removed before sending). */
    private val uploadFolders = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Android's picker. An image becomes a shrunk copy the model sees directly; any other file is streamed (never read
     * into memory) to the bridge machine and the agent gets its path there.
     */
    fun addAttachmentsFromUris(uris: List<android.net.Uri>, context: android.content.Context) {
        if (uris.isEmpty()) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            _isUploadingAttachment.value = true
            try {
                for (uri in uris) {
                    addFromUri(uri, appContext)
                }
            } finally {
                _isUploadingAttachment.value = false
            }
        }
    }

    fun addAttachmentFromUri(uri: android.net.Uri, context: android.content.Context) {
        addAttachmentsFromUris(listOf(uri), context)
    }

    private fun newAttachmentId() = "att_${System.currentTimeMillis()}_${(0..999).random()}"

    private suspend fun addFromUri(uri: android.net.Uri, context: android.content.Context) {
        var fileName = "attachment_${System.currentTimeMillis()}"
        try {
            val contentResolver = context.contentResolver
            var fileSize = 0L
            withContext(Dispatchers.IO) {
                contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: fileName
                        if (sizeIndex >= 0 && !it.isNull(sizeIndex)) fileSize = it.getLong(sizeIndex)
                    }
                }
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = contentResolver.getType(uri)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"

            if (mimeType.startsWith("image/")) {
                val shrunk = withContext(Dispatchers.IO) { com.example.gemini.data.files.ImageShrinker.fromUri(context, uri, mimeType, fileSize) }
                if (shrunk != null) {
                    _attachments.value = _attachments.value + ChatAttachment(
                        id = newAttachmentId(),
                        name = com.example.gemini.data.files.ImageShrinker.nameFor(fileName, shrunk),
                        path = "",
                        isImage = true,
                        localUri = uri.toString(),
                        size = shrunk.bytes.size.toLong(),
                        mimeType = shrunk.mimeType,
                        base64 = android.util.Base64.encodeToString(shrunk.bytes, android.util.Base64.NO_WRAP)
                    )
                    return
                }
                // an image Android cannot decode (e.g. TIFF) goes to the agent as a file
            }
            startCopy(uri, context, fileName, fileSize, mimeType)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e("ChatViewModel", "Failed to add attachment from uri $uri: ${t.message}")
            com.example.gemini.ui.components.AppToastHelper.showToast(
                "Could not attach $fileName: ${t.message ?: t.javaClass.simpleName}",
                com.example.gemini.ui.components.ChatToastType.ERROR
            )
        }
    }

    /** Streams a picked file to [AttachedFiles.UPLOADS_DIR] on the bridge machine; its chip shows the progress. */
    private fun startCopy(uri: android.net.Uri, context: android.content.Context, fileName: String, fileSize: Long, mimeType: String) {
        val id = newAttachmentId()
        val folder = "${com.example.gemini.data.files.AttachedFiles.UPLOADS_DIR}/${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val safeName = fileName.replace('/', '_').replace('\u0000', '_').trim().ifBlank { "attachment" }.take(200)
        _attachments.value = _attachments.value + ChatAttachment(
            id = id,
            name = fileName,
            path = "",
            localUri = uri.toString(),
            size = fileSize,
            mimeType = mimeType,
            reference = true
        )
        uploadFolders[id] = folder
        _attachmentProgress.update { it + (id to 0f) }
        val job = viewModelScope.launch {
            var sentTotal = 0L
            var lastPercent = -1
            val result = com.example.gemini.data.files.FileExplorerApi.upload(
                input = { context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("the file cannot be read") },
                length = -1,
                targetPath = "$folder/$safeName"
            ) { sent ->
                sentTotal = sent
                if (fileSize > 0) {
                    val percent = (sent * 100 / fileSize).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _attachmentProgress.update { m -> if (id in m) m + (id to percent / 100f) else m }
                    }
                }
            }
            val path = result.path
            if (result.ok && !path.isNullOrBlank()) {
                val size = if (fileSize > 0) fileSize else sentTotal
                _attachments.update { list -> list.map { if (it.id == id) it.copy(path = path, size = size) else it } }
            } else if (isActive) {
                _attachments.update { list -> list.filter { it.id != id } }
                uploadFolders.remove(id)?.let { com.example.gemini.data.files.FileExplorerApi.delete(listOf(it)) }
                com.example.gemini.ui.components.AppToastHelper.showToast(
                    "Could not copy $fileName to the agent's machine: ${result.error ?: "unknown error"}",
                    com.example.gemini.ui.components.ChatToastType.ERROR
                )
            }
        }
        uploadJobs[id] = job
        job.invokeOnCompletion {
            uploadJobs.remove(id)
            _attachmentProgress.update { it - id }
        }
    }

    /**
     * Files picked in the app's Files screen: they are already on the agent's machine, so nothing is copied and the
     * agent opens the originals. An image also goes to the model as a shrunk copy, so the model sees it right away.
     */
    fun addExplorerFiles(entries: List<com.example.gemini.data.files.ExplorerEntry>) {
        val files = entries.filter { !it.isDir }
        if (files.isEmpty()) return
        viewModelScope.launch {
            _isUploadingAttachment.value = true
            try {
                for (e in files) {
                    if (_attachments.value.any { it.reference && it.path == e.path }) continue
                    val mime = com.example.gemini.data.files.AttachedFiles.mimeOf(e.name)
                    var att = ChatAttachment(
                        id = newAttachmentId(),
                        name = e.name,
                        path = e.path,
                        size = e.size,
                        mimeType = mime,
                        url = com.example.gemini.data.files.FileExplorerApi.rawUrl(e.path),
                        reference = true
                    )
                    if (mime.startsWith("image/")) {
                        val shrunk = try {
                            shrinkBridgeImage(e, mime)
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) throw t
                            Log.w("ChatViewModel", "Could not prepare ${e.path} as an image: ${t.message}")
                            null
                        }
                        if (shrunk != null) {
                            att = att.copy(isImage = true, mimeType = shrunk.mimeType, base64 = android.util.Base64.encodeToString(shrunk.bytes, android.util.Base64.NO_WRAP))
                        }
                    }
                    _attachments.value = _attachments.value + att
                }
            } finally {
                _isUploadingAttachment.value = false
            }
        }
    }

    /** The shrunk copy of an image on the bridge machine: read in place when this phone can, else downloaded (streamed) first. */
    private suspend fun shrinkBridgeImage(e: com.example.gemini.data.files.ExplorerEntry, mime: String): com.example.gemini.data.files.ImageShrinker.Shrunk? =
        withContext(Dispatchers.IO) {
            val api = com.example.gemini.data.files.FileExplorerApi
            if (api.isLocallyReadable(e.path)) return@withContext com.example.gemini.data.files.ImageShrinker.fromFile(java.io.File(e.path), mime)
            if (e.size > MAX_IMAGE_DOWNLOAD_BYTES) return@withContext null
            val dir = java.io.File(getApplication<Application>().cacheDir, "attach-images").apply { mkdirs() }
            val tmp = java.io.File(dir, UUID.randomUUID().toString())
            try {
                if (!api.download(e.path, tmp).ok) null else com.example.gemini.data.files.ImageShrinker.fromFile(tmp, mime)
            } finally {
                tmp.delete()
            }
        }

    /** Editing a sent message puts its files back as chips. */
    private fun restoreAttachedFiles(raw: String) {
        val refs = com.example.gemini.data.files.AttachedFiles.parse(raw)
        if (refs.isEmpty()) return
        addExplorerFiles(refs.map { com.example.gemini.data.files.ExplorerEntry(name = it.path.trimEnd('/').substringAfterLast('/'), path = it.path, isDir = false, size = it.size) })
    }

    /** The text of a sent message as the user typed it (no hidden instructions, no file list). */
    private fun editableText(raw: String): String =
        PromptExpansion.collapse(com.example.gemini.data.files.AttachedFiles.extract(raw, "edit").first)

    fun addProjectFileAttachment(filePath: String, fileName: String) {
        viewModelScope.launch {
            val file = java.io.File(filePath)
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val isImg = mimeType.startsWith("image/")
            val isAud = mimeType.startsWith("audio/")
            val b64 = if (file.exists()) {
                android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            } else ""

            val att = com.example.gemini.domain.model.ChatAttachment(
                id = "att_${System.currentTimeMillis()}_${(0..999).random()}",
                name = fileName,
                path = filePath,
                isImage = isImg,
                isAudio = isAud,
                size = file.length(),
                mimeType = mimeType,
                base64 = b64.ifBlank { null }
            )
            _attachments.value = _attachments.value + att
        }
    }

    fun removeAttachment(attachmentId: String) {
        val target = _attachments.value.find { it.id == attachmentId }
        _attachments.value = _attachments.value.filter { it.id != attachmentId }
        if (target != null) discardAttachment(target)
    }

    fun clearAttachments() {
        val atts = _attachments.value
        _attachments.value = emptyList()
        atts.forEach { discardAttachment(it) }
    }

    /**
     * An attachment removed before it was sent: its copy stops and what the app made for it is deleted. A file the user
     * picked in Files is their own and is never touched.
     */
    private fun discardAttachment(target: ChatAttachment) {
        val job = uploadJobs.remove(target.id)
        job?.cancel()
        if (target.reference) {
            val folder = uploadFolders.remove(target.id) ?: return
            val delete: () -> Unit = { viewModelScope.launch(Dispatchers.IO) { com.example.gemini.data.files.FileExplorerApi.delete(listOf(folder)) } }
            // a running copy is removed once it has stopped (the bridge drops the partial file itself)
            if (job != null) job.invokeOnCompletion { delete() } else delete()
            return
        }
        if (target.path.isNotBlank() && !target.path.startsWith("content://") && !target.path.startsWith("/data/")) {
            viewModelScope.launch(Dispatchers.IO) {
                agyHubClient.deleteMediaArtifact(target.path)
            }
        }
    }

    private val _isDevModeEnabled = MutableStateFlow(false)
    val isDevModeEnabled: StateFlow<Boolean> = _isDevModeEnabled.asStateFlow()

    private val _terminatedToolDialog = store.terminatedToolDialog
    val terminatedToolDialog: StateFlow<Pair<com.example.gemini.domain.model.ToolCall, String>?> = _terminatedToolDialog.asStateFlow()

    fun dismissTerminatedToolDialog() {
        _terminatedToolDialog.value = null
    }

    fun setDevModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setDevModeEnabled(enabled)
        }
    }

    fun updateCustomSystemPrompt(prompt: String?) {
        val conv = _currentConversation.value ?: return
        val updated = conv.copy(customSystemPrompt = if (prompt.isNullOrBlank()) null else prompt.trim())
        _currentConversation.value = updated
        _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
    }

    val chatFontScale = authPrefs.chatFontScale
    val themeMode: StateFlow<String> = authPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, authPreferences.getThemeModeSync())
    val agyHubUrl = authPrefs.agyHubUrl
    val agyBridgeHttpUrl = authPrefs.agyBridgeHttpUrl
    val terminalFontSize = authPrefs.terminalFontSize
    val terminalCursorStyle = authPrefs.terminalCursorStyle
    val terminalBufferSize = authPrefs.terminalBufferSize
    val terminalTheme = authPrefs.terminalTheme

    val useSshTerminal = authPrefs.useSshTerminal
    val termuxSshHost = authPrefs.termuxSshHost
    val termuxSshPort = authPrefs.termuxSshPort
    val termuxSshUser = authPrefs.termuxSshUser
    val termuxSshPass = authPrefs.termuxSshPass
    val isLocalToolsEnabled = authPrefs.isLocalToolsEnabled
    val isLocalToolsInstalled = authPrefs.isLocalToolsInstalled

    val commandAutoExecutionPolicy = authPrefs.commandAutoExecutionPolicy
    val commandSandboxEnabled = authPrefs.commandSandboxEnabled
    val requireApprovalForFileEdits = authPrefs.requireApprovalForFileEdits
    val defaultApprovalScope = authPrefs.defaultApprovalScope
    val groupChatsByWorkspace: StateFlow<Boolean> = authPrefs.groupChatsByWorkspace
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isFloatingBubbleEnabled: StateFlow<Boolean> = authPrefs.isFloatingBubbleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingBubbleEnabledSync())

    val autoShowFloatingBubbleOnMinimize: StateFlow<Boolean> = authPrefs.autoShowFloatingBubbleOnMinimize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getAutoShowFloatingBubbleOnMinimizeSync())

    val isFloatingSwitcherEnabled: StateFlow<Boolean> = authPrefs.isFloatingSwitcherEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherEnabledSync())

    val floatingSwitcherOrientation: StateFlow<String> = authPrefs.floatingSwitcherOrientation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherOrientationSync())

    val floatingSwitcherItems: StateFlow<List<String>> = authPrefs.floatingSwitcherItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherItemsSync())

    val chatHeaderItems: StateFlow<List<String>> = authPrefs.chatHeaderItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getChatHeaderItemsSync())

    val floatingSwitcherAutoCollapseSec: StateFlow<Int> = authPrefs.floatingSwitcherAutoCollapseSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherAutoCollapseSecSync())

    val floatingSwitcherPosX: StateFlow<Float> = authPrefs.floatingSwitcherPosX
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherPositionSync().first)

    val floatingSwitcherPosY: StateFlow<Float> = authPrefs.floatingSwitcherPosY
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherPositionSync().second)

    fun setFloatingSwitcherEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherEnabled(enabled)
        }
    }

    fun setFloatingSwitcherOrientation(orientation: String) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherOrientation(orientation)
        }
    }

    fun setFloatingSwitcherItems(items: List<String>) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherItems(items)
        }
    }

    fun setChatHeaderItems(items: List<String>) {
        viewModelScope.launch {
            authPrefs.saveChatHeaderItems(items)
        }
    }

    fun setFloatingSwitcherAutoCollapseSec(sec: Int) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherAutoCollapseSec(sec)
        }
    }

    private var saveFloatingSwitcherJob: kotlinx.coroutines.Job? = null

    fun saveFloatingSwitcherPosition(x: Float, y: Float) {
        saveFloatingSwitcherJob?.cancel()
        saveFloatingSwitcherJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(250)
            authPrefs.saveFloatingSwitcherPosition(x, y)
        }
    }

    fun resetFloatingSwitcherPosition() {
        saveFloatingSwitcherJob?.cancel()
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherPosition(0.95f, 0.50f)
        }
    }

    fun setGroupChatsByWorkspace(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveGroupChatsByWorkspace(enabled)
        }
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingBubbleEnabled(enabled)
        }
    }

    fun setAutoShowFloatingBubbleOnMinimize(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveAutoShowFloatingBubbleOnMinimize(enabled)
        }
    }

    val isFloatingDiagnosticsEnabled: StateFlow<Boolean> = authPrefs.isFloatingDiagnosticsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingDiagnosticsEnabledSync())

    fun setFloatingDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingDiagnosticsEnabled(enabled)
        }
    }

    val isNetworkInspectorEnabled: StateFlow<Boolean> = authPrefs.isNetworkInspectorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getNetworkInspectorEnabledSync())

    fun setNetworkInspectorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveNetworkInspectorEnabled(enabled)
        }
    }

    val isFloatingNetworkInspectorEnabled: StateFlow<Boolean> = authPrefs.isFloatingNetworkInspectorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingNetworkInspectorEnabledSync())

    fun setFloatingNetworkInspectorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingNetworkInspectorEnabled(enabled)
        }
    }


    fun setRequireApprovalForFileEdits(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setRequireApprovalForFileEdits(enabled)
        }
    }

    fun setDefaultApprovalScope(scope: String) {
        viewModelScope.launch {
            authPrefs.setDefaultApprovalScope(scope)
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            authPrefs.saveThemeMode(mode)
        }
    }

    /** Remote hub sign-in: hands the pasted localhost redirect address to the server (see AgyBridgeService.forwardLoginCallback). */
    fun forwardLoginCallback(url: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = com.example.gemini.data.remote.AgyBridgeService.instance.forwardLoginCallback(url)
            if (result.isSuccess) agyBackend.onLoginCallbackForwarded()
            onResult(result)
        }
    }

    fun saveServerUrls(hubUrl: String, bridgeUrl: String) {
        viewModelScope.launch {
            if (hubUrl.isNotBlank()) {
                authPrefs.saveAgyHubUrl(hubUrl.trim())
                agyHubClient.clearCsrfToken()
            }
            if (bridgeUrl.isNotBlank()) {
                authPrefs.saveAgyBridgeHttpUrl(bridgeUrl.trim())
            }
        }
    }

    fun saveTerminalPreferences(fontSize: Int, cursorStyle: String, bufferSize: Int, theme: String) {
        viewModelScope.launch {
            authPrefs.saveTerminalPreferences(fontSize, cursorStyle, bufferSize, theme)
        }
    }

    fun setUseSshTerminal(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setUseSshTerminal(enabled)
            if (enabled) {
                authPrefs.setTerminalToolEnabled(true)
            }
        }
    }

    fun saveSshSettings(host: String, port: Int, user: String, pass: String) {
        viewModelScope.launch {
            authPrefs.saveSshSettings(host, port, user, pass)
        }
    }

    fun setLocalToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setLocalToolsEnabled(enabled)
            if (enabled) {
                authPrefs.setTerminalToolEnabled(true)
            }
        }
    }

    fun setChatFontScale(scale: Float) {
        viewModelScope.launch {
            authPrefs.saveChatFontScale(scale)
        }
    }

    fun setContextWindowLimit(limit: Int) {
        viewModelScope.launch {
            authPrefs.setContextWindowLimit(limit)
        }
    }

    fun setSummaryModelId(modelId: String) {
        viewModelScope.launch {
            authPrefs.setSummaryModelId(modelId)
        }
    }

    fun postponeSummarization(extraCount: Int) {
        val currentCount = _messages.value.size
        _postponedThreshold.value = currentCount + extraCount
    }

    fun updateSummaryMessage(messageId: String, newSummary: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.map {
            if (it.id == messageId) it.copy(content = newSummary) else it
        }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
    }

    fun deleteSummaryMessage(messageId: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.filterNot { it.id == messageId }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _postponedThreshold.value = null
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
    }

    fun updateSummary(newSummary: String) {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            updateSummaryMessage(latestSummaryMsg.id, newSummary)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = if (newSummary.isBlank()) null else newSummary)
            _currentConversation.value = updated
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
    }

    fun deleteSummary() {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            deleteSummaryMessage(latestSummaryMsg.id)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = null)
            _currentConversation.value = updated
            _postponedThreshold.value = null
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
    }

    fun setThinkingPreference(pref: com.example.gemini.domain.model.ThinkingPreference) {
        _thinkingPreference.value = pref
    }


    private val _isNetworkConnectedState = MutableStateFlow(isNetworkConnected())
    val isNetworkConnectedState: StateFlow<Boolean> = _isNetworkConnectedState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isNetworkConnectedState.value = true
        }
        override fun onLost(network: Network) {
            _isNetworkConnectedState.value = isNetworkConnected()
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _isNetworkConnectedState.value = hasInternet
        }
    }

    fun isNetworkConnected(): Boolean {
        return try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Error checking network connectivity: ${e.message}", e)
            false
        }
    }

    private val _conversationError = store.conversationError
    val conversationError: StateFlow<String?> = byAgent(_conversationError, claudeBackend.conversationError, openCodeBackend.conversationError, codexBackend.conversationError)


    private val _conversationDrafts = mutableMapOf<String, androidx.compose.ui.text.input.TextFieldValue>()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchingConversations = MutableStateFlow(false)
    val isSearchingConversations: StateFlow<Boolean> = _isSearchingConversations.asStateFlow()

    private var serverJob: Job? = null
    var pendingPkceVerifier: String? = null

    init {
        try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "Could not register network callback: ${e.message}")
        }

        agyBackend.start()
        if (isClaudeEnabled) claudeBackend.start()
        if (isOpenCodeEnabled) openCodeBackend.start()
        if (isCodexEnabled) codexBackend.start()
        // the first chat follows the new-chat agent (Antigravity's own start-up chat only fills in when it is AGY)
        if ((isClaudeEnabled || isOpenCodeEnabled || isCodexEnabled) && (newChatAgent.value != AgentKind.AGY || AgentKind.AGY !in enabledAgents.value)) startNewChat()
        claudeAccount.onAccountChanged = {
            claudeBackend.refreshInfo(force = true)
            viewModelScope.launch { claudeBackend.refreshSessions() }
        }
        claudeBackend.onTurnFinished = { claudeAccount.refreshUsage(force = false) }
        claudeAccount.onFreshUsage = { claudeBackend.dropLiveRateLimits() }
        claudeConfig.onSettingsChanged = {
            claudeBackend.refreshInfo(force = true)
            claudeBackend.loadChatDefaults()
        }
        claudeConfig.onCliChanged = {
            claudeAccount.refreshStatus()
            claudeBackend.refreshInfo(force = true)
        }
        if (isClaudeEnabled) claudeAccount.refreshStatus()
        // Claude goes through the same bridge as AGY: follow the bridge coming and going
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            systemConnectionState.map { it.isBridgeOnline }.distinctUntilChanged().collect { online ->
                if (isOpenCodeEnabled && online && wasOnline == false) openCodeBackend.retryConnections()
                if (isCodexEnabled && online && wasOnline == false) codexBackend.retryConnections()
                if (isClaudeEnabled) {
                    if (online && wasOnline == false) {
                        claudeAccount.refreshStatus()
                        claudeBackend.retryConnections()
                    }
                    if (!online && wasOnline == true) claudeAccount.onBridgeOffline()
                }
                wasOnline = online
            }
        }
        // a chat hit "not logged in": the cached status is stale
        viewModelScope.launch {
            claudeBackend.needsLogin.filter { it }.collect { claudeAccount.refreshStatus() }
        }
        // models and slash commands could not load before Claude was reachable and signed in
        viewModelScope.launch {
            claudeAccount.state.map { it == com.example.gemini.data.agent.claude.ClaudeStatus.READY }.distinctUntilChanged().filter { it }
                .collect { claudeBackend.refreshInfo(force = false) }
        }

        viewModelScope.launch {
            authPrefs.userEmail.collect { _userEmail.value = it }
        }

        viewModelScope.launch {
            authPrefs.projectId.collect { it?.let { p -> _projectId.value = p } }
        }

        viewModelScope.launch {
            authPrefs.subscriptionTier.collect { it?.let { t -> _tier.value = t } }
        }

        viewModelScope.launch {
            authPrefs.contextWindowLimit.collect { _contextWindowLimit.value = it }
        }

        viewModelScope.launch {
            authPrefs.summaryModelId.collect { _summaryModelIdPref.value = it }
        }

        viewModelScope.launch {
            authPrefs.isDevModeEnabled.collect { _isDevModeEnabled.value = it }
        }

    }

    fun getDraft(conversationId: String): androidx.compose.ui.text.input.TextFieldValue {
        return _conversationDrafts[conversationId] ?: androidx.compose.ui.text.input.TextFieldValue("")
    }

    fun setDraft(conversationId: String, value: androidx.compose.ui.text.input.TextFieldValue) {
        if (value.text.isEmpty()) {
            _conversationDrafts.remove(conversationId)
        } else {
            _conversationDrafts[conversationId] = value
        }
    }

    fun clearDraft(conversationId: String) {
        _conversationDrafts.remove(conversationId)
    }

    fun searchConversations(query: String) {
        _searchQuery.value = query
    }

    fun addAttachment(attachment: ChatAttachment) {
        _attachments.value = _attachments.value + attachment
    }


    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? {
        val currentAccess = authPrefs.accessToken.firstOrNull()
        val currentRefresh = authPrefs.refreshToken.firstOrNull()
        val expiresAt = authPrefs.expiresAt.firstOrNull() ?: 0L

        val isExpired = forceRefresh || (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - 60_000)

        if (!isExpired && !currentAccess.isNullOrBlank()) {
            return currentAccess
        }

        if (!currentRefresh.isNullOrBlank()) {
            android.util.Log.d("GeminiApp", "[Auth] Refreshing Google OAuth access token using refresh_token...")
            val result = oauthManager.refreshToken(currentRefresh)
            if (result.isSuccess) {
                val tokenData = result.getOrThrow()
                android.util.Log.d("GeminiApp", "[Auth] Token refreshed successfully! New access token acquired.")
                authPrefs.saveTokens(
                    accessToken = tokenData.access_token,
                    refreshToken = tokenData.refresh_token ?: currentRefresh,
                    expiresInSeconds = tokenData.expires_in ?: 3600
                )
                return tokenData.access_token
            } else {
                android.util.Log.e("GeminiApp", "[Auth] Failed to refresh token: ${result.exceptionOrNull()?.message}")
            }
        }

        return currentAccess
    }


    fun applyManualInput(input: String) {
        val trimmed = input.trim()
        android.util.Log.d("GeminiApp", "[OAuth] applyManualInput received: ${trimmed.take(30)}...")
        if (trimmed.contains("code=")) {
            val match = Regex("[?&]code=([^&\\s]+)").find(trimmed)
            val code = match?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (code != null) {
                android.util.Log.d("GeminiApp", "[OAuth] Extracted auth code from manual input: ${code.take(15)}...")
                handleOAuthCode(code)
                return
            }
        }

        if (trimmed.startsWith("4/0")) {
            val decoded = try { java.net.URLDecoder.decode(trimmed, "UTF-8") } catch (_: Exception) { trimmed }
            handleOAuthCode(decoded)
            return
        }

        // Direct access token (e.g. ya29...)
        viewModelScope.launch {
            android.util.Log.d("GeminiApp", "[OAuth] Applying direct token...")
            authPrefs.saveTokens(trimmed, null, "Manual Token")
            val assistRes = apiService.loadCodeAssist(trimmed)
            if (assistRes.isSuccess) {
                val (proj, tier) = assistRes.getOrThrow()
                authPrefs.saveProjectInfo(proj, tier)
                _projectId.value = proj
                _tier.value = tier
            }
            val userRes = oauthManager.fetchUserInfo(trimmed)
            if (userRes.isSuccess) {
                userRes.getOrNull()?.email?.let { email ->
                    authPrefs.saveTokens(trimmed, null, email)
                    _userEmail.value = email
                }
            }
            refreshQuotas()
        }
    }

    fun startOAuthServer() {
        if (oauthManager.isServerListening.value && serverJob?.isActive == true) {
            android.util.Log.d("GeminiApp", "[OAuth] Server is already active, ignoring start request")
            return
        }
        serverJob?.cancel()
        serverJob = viewModelScope.launch {
            _isOAuthServerLoading.value = true
            try {
                if (pendingPkceVerifier == null) {
                    val pkce = oauthManager.generatePkce()
                    pendingPkceVerifier = pkce.codeVerifier
                    android.util.Log.d("GeminiApp", "[OAuth] Generated PKCE verifier for server session")
                }
                _isOAuthServerLoading.value = false
                oauthManager.startLocalCallbackServer { code ->
                    handleOAuthCode(code)
                }
            } catch (e: Exception) {
                android.util.Log.e("GeminiApp", "[OAuth] Server start failed: ${e.message}")
                _isOAuthServerLoading.value = false
            }
        }
    }

    fun stopOAuthServer() {
        serverJob?.cancel()
        serverJob = null
        _isOAuthServerLoading.value = false
        oauthManager.stopLocalCallbackServer()
    }

    fun toggleOAuthServer(enable: Boolean) {
        if (enable) {
            startOAuthServer()
        } else {
            stopOAuthServer()
        }
    }

    fun login() {
        loginToAgyHub()
    }

    fun logout() {
        stopOAuthServer()
        pendingPkceVerifier = null
        logoutFromAgyHub()
        viewModelScope.launch {
            authPrefs.clearAuth()
        }
        _userEmail.value = null
        _projectId.value = "rising-fact-p41fc"
        _tier.value = "pro"
        agyBackend.resetQuotaState()
    }

    fun getGoogleOAuthUrl(): String {
        val pkce = oauthManager.generatePkce()
        pendingPkceVerifier = pkce.codeVerifier
        android.util.Log.d("GeminiApp", "[OAuth] getGoogleOAuthUrl generated verifier (len=${pkce.codeVerifier.length})")
        startOAuthServer()
        return pkce.authUrl
    }

    fun handleOAuthCode(code: String) {
        val trimmedCode = code.trim()
        android.util.Log.d("GeminiApp", "[OAuth] handleOAuthCode processing code: ${trimmedCode.take(20)}...")
        val verifier = pendingPkceVerifier
        if (verifier == null) {
            android.util.Log.e("GeminiApp", "[OAuth] ERROR: pendingPkceVerifier is null! Cannot exchange auth code.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Brief delay to let the server socket fully close and free the IO thread pool
            // before making outbound network calls. This prevents DNS resolution failures
            // that occur when the token exchange fires from within the server socket callback.
            delay(400L)

            var tokenRes: Result<GoogleOAuthManager.TokenResponse>? = null
            for (attempt in 1..3) {
                android.util.Log.d("GeminiApp", "[OAuth] exchangeCodeForToken attempt $attempt...")
                tokenRes = oauthManager.exchangeCodeForToken(trimmedCode, verifier)
                if (tokenRes.isSuccess) break
                android.util.Log.w("GeminiApp", "[OAuth] Attempt $attempt failed: ${tokenRes.exceptionOrNull()?.message}")
                if (attempt < 3) delay(1000L * attempt)
            }

            if (tokenRes?.isSuccess == true) {
                val tokenData = tokenRes.getOrThrow()
                android.util.Log.d("GeminiApp", "[OAuth] Token exchange SUCCESS! Fetching user profile...")
                val userRes = oauthManager.fetchUserInfo(tokenData.access_token)
                val email = userRes.getOrNull()?.email ?: "Google Account"
                android.util.Log.d("GeminiApp", "[OAuth] User email resolved: $email")

                authPrefs.saveTokens(
                    accessToken = tokenData.access_token,
                    refreshToken = tokenData.refresh_token,
                    email = email,
                    expiresInSeconds = tokenData.expires_in ?: 3600
                )
                withContext(Dispatchers.Main) { _userEmail.value = email }

                val assistRes = apiService.loadCodeAssist(tokenData.access_token)
                if (assistRes.isSuccess) {
                    val (proj, tier) = assistRes.getOrThrow()
                    android.util.Log.d("GeminiApp", "[OAuth] CodeAssist project: $proj, tier: $tier")
                    authPrefs.saveProjectInfo(proj, tier)
                    withContext(Dispatchers.Main) {
                        _projectId.value = proj
                        _tier.value = tier
                    }
                } else {
                    android.util.Log.w("GeminiApp", "[OAuth] CodeAssist loading warning: ${assistRes.exceptionOrNull()?.message}")
                }
                refreshQuotas()
            } else {
                android.util.Log.e("GeminiApp", "[OAuth] Token exchange FAILED after retries: ${tokenRes?.exceptionOrNull()?.message}")
            }
        }
    }


    private var sharedConversationLoadJob: Job? = null

    fun loadSharedConversationFromUri(context: Context, uri: Uri) {
        sharedConversationLoadJob?.cancel()
        _isSharedConversationLoading.value = true
        sharedConversationLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    // The restore is done by the local server (bridge) alone, but at app launch it may not be up yet:
                    // retry while it is unreachable. An HTTP answer (e.g. not an archive) is final.
                    var restoreRes = agyBridgeService.restoreConversationArchive(bytes)
                    val giveUpAt = System.currentTimeMillis() + 60_000
                    var told = false
                    while (restoreRes.isFailure && restoreRes.exceptionOrNull()?.message?.startsWith("HTTP ") != true &&
                        System.currentTimeMillis() < giveUpAt && isActive
                    ) {
                        if (!told) {
                            told = true
                            withContext(Dispatchers.Main) {
                                com.example.gemini.ui.components.AppToastHelper.showToast("Waiting for the local server to restore the chat…", com.example.gemini.ui.components.ChatToastType.INFO)
                            }
                        }
                        delay(1_500)
                        restoreRes = agyBridgeService.restoreConversationArchive(bytes)
                    }
                    if (restoreRes.isSuccess) {
                        val restored = restoreRes.getOrThrow()
                        if (!isActive) return@launch
                        val newConv = com.example.gemini.domain.model.Conversation(
                            id = restored.id,
                            title = restored.title,
                            updatedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                            stepCount = restored.stepsCount
                        )
                        _conversations.value = listOf(newConv) + _conversations.value.filter { it.id != restored.id }
                        val hubReady = systemConnectionState.value.isReady
                        withContext(Dispatchers.Main) {
                            com.example.gemini.ui.components.AppToastHelper.showToast(
                                if (hubReady) "Chat restored: ${restored.title}"
                                else "Chat restored: ${restored.title}. It opens once Antigravity has started.",
                                com.example.gemini.ui.components.ChatToastType.SUCCESS
                            )
                        }
                        _isSharedConversationLoading.value = false
                        openRestoredWhenAgyReady(restored.id)
                        return@launch
                    }

                    val parsed = com.example.gemini.ui.components.ConversationShareHelper.parseSharedConversation(bytes.inputStream())
                    if (!isActive) return@launch
                    _sharedConversationPreview.value = parsed
                    withContext(Dispatchers.Main) {
                        com.example.gemini.ui.components.AppToastHelper.showToast("Loaded shared chat: ${parsed.title}", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e("ChatViewModel", "Failed to load shared conversation from uri $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to load shared chat file", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            } finally {
                _isSharedConversationLoading.value = false
            }
        }
    }

    /**
     * A restored Antigravity chat can only be shown once the hub is up (its steps come from the hub): open it then.
     * Not if the user opened another existing chat meanwhile (it stays in the list).
     */
    private suspend fun openRestoredWhenAgyReady(id: String) {
        val before = store.currentConversation.value?.id
        val ready = withTimeoutOrNull(180_000) { systemConnectionState.first { it.isReady } } != null
        if (!ready) {
            withContext(Dispatchers.Main) {
                com.example.gemini.ui.components.AppToastHelper.showToast("Antigravity has not started yet: the restored chat is in the chat list.", com.example.gemini.ui.components.ChatToastType.INFO)
            }
            return
        }
        val now = store.currentConversation.value
        val userMovedOn = now != null && now.id != before && now.title != "New Chat"
        if (userMovedOn) return
        withContext(Dispatchers.Main) { selectConversation(id) }
    }

    fun cancelSharedConversationLoading() {
        sharedConversationLoadJob?.cancel()
        sharedConversationLoadJob = null
        _isSharedConversationLoading.value = false
        _sharedConversationPreview.value = null
    }

    fun dismissSharedConversation() {
        _sharedConversationPreview.value = null
    }

    fun dismissIncomingMarkdownPreview() {
        _incomingMarkdownPreview.value = null
    }

    fun loadMarkdownFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName: String? = null
                if (uri.scheme == "content") {
                    try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    fileName = cursor.getString(nameIdx)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val resolvedName = fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.md"
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                _incomingMarkdownPreview.value = resolvedName to content
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Opened $resolvedName", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load markdown from URI $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to load markdown file", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            }
        }
    }

    fun openFileInIdeDirectly(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName: String? = null
                if (uri.scheme == "content") {
                    try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    fileName = cursor.getString(nameIdx)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val resolvedName = fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
                val resolvedPath = if (uri.scheme == "file") uri.path ?: resolvedName else uri.toString()
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                com.example.gemini.data.daemon.TermuxDaemonManager.openOrSelectTab(
                    path = resolvedPath,
                    name = resolvedName,
                    content = content,
                    isExternal = true
                )
                requestViewMode("IDE")
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Opened $resolvedName in IDE", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to open file in IDE from URI $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to open file in IDE", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        agyBackend.dispose()
    }

    private companion object {
        /** An image picked in Files on a remote bridge is downloaded to be shrunk only up to this size (else: path only). */
        const val MAX_IMAGE_DOWNLOAD_BYTES = 50L * 1024 * 1024
    }
}

private data class ExecutedToolResult(
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
    val status: String
)
