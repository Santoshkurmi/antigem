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

    /** Agent of the conversation on screen; chat actions go to that agent's backend. */
    val activeAgent: StateFlow<AgentKind> = store.currentConversation
        .map { it?.agent ?: AgentKind.AGY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentKind.AGY)

    private val isClaudeActive get() = store.currentConversation.value?.agent == AgentKind.CLAUDE
    private val isOpenCodeActive get() = store.currentConversation.value?.agent == AgentKind.OPENCODE

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

    private fun <T> byAgent(agy: StateFlow<T>, claude: StateFlow<T>, openCode: StateFlow<T>): StateFlow<T> =
        combine(store.currentConversation, agy, claude, openCode) { conv, a, c, o ->
            when (conv?.agent) {
                AgentKind.CLAUDE -> c
                AgentKind.OPENCODE -> o
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
    val isStreaming = byAgent(agyBackend.isStreaming, claudeBackend.isStreaming, openCodeBackend.isStreaming)
    val availableModels = byAgent(agyBackend.availableModels, claudeBackend.availableModels, openCodeBackend.availableModels)
    val enabledModelIds = agyBackend.enabledModelIds
    val enabledModels = byAgent(agyBackend.enabledModels, claudeBackend.availableModels, openCodeBackend.availableModels)
    val isRefreshingModels = byAgent(agyBackend.isRefreshingModels, claudeBackend.isRefreshingModels, openCodeBackend.isRefreshingModels)
    val selectedModelId = byAgent(agyBackend.selectedModelId, claudeBackend.selectedModelId, openCodeBackend.selectedModelId)
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
    val quotas = byAgent(agyBackend.quotas, claudeQuotas, noQuotas)
    val quotaSummary = agyBackend.quotaSummary
    val isLoadingConversation = byAgent(agyBackend.isLoadingConversation, claudeBackend.isLoadingConversation, openCodeBackend.isLoadingConversation)
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
    /**
     * Keeps the screen on: an Antigravity chat or task is working, a Claude Code chat is working (the one on screen, or
     * any other the session list shows as running), a Jarvis task runs, or Jarvis' keep-screen-on setting asks for it.
     */
    val isAnyGenerationOrTaskActive: StateFlow<Boolean> = combine(
        agyBackend.isAnyGenerationOrTaskActive,
        claudeBackend.isCurrentChatActivelyRunning,
        claudeBackend.conversations,
        combine(com.example.gemini.data.jarvis.JarvisController.keepScreenAwake, openCodeBackend.isCurrentChatActivelyRunning, openCodeBackend.conversations) { j, ocTurn, ocChats ->
            j || ocTurn || ocChats.any { it.isRunning }
        },
        com.example.gemini.data.jarvis.JarvisTasks.anyActive
    ) { agy, claudeTurn, claudeChats, jarvisOrOpenCode, tasks ->
        agy || claudeTurn || claudeChats.any { it.isRunning } || jarvisOrOpenCode || tasks
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
            if (tasks) add("A Jarvis task is running")
            if (jarvis) add(
                when (com.example.gemini.data.jarvis.JarvisPreferences.keepScreenOn.value) {
                    "active" -> "Jarvis is active (Jarvis setting: keep the screen on while it's active)"
                    else -> "Jarvis is on (Jarvis setting: keep the screen on while Jarvis is on)"
                }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val isCurrentChatActivelyRunning = byAgent(agyBackend.isCurrentChatActivelyRunning, claudeBackend.isCurrentChatActivelyRunning, openCodeBackend.isCurrentChatActivelyRunning)
    val isCurrentChatBackgroundActive = byAgent(agyBackend.isCurrentChatBackgroundActive, alwaysFalse, alwaysFalse)
    val connectionState = agyBackend.connectionState
    fun refreshActiveInstances() = agyBackend.refreshActiveInstances()
    fun terminateInstance(conversationId: String) = agyBackend.terminateInstance(conversationId)
    fun syncAgyConversations(force: Boolean = false) = agyBackend.syncAgyConversations(force)
    fun retryConnections() {
        agyBackend.retryConnections()
        if (isClaudeActive) claudeBackend.retryConnections()
        if (isOpenCodeEnabled) openCodeBackend.retryConnections()
    }
    fun onAppForegrounded() {
        agyBackend.onAppForegrounded()
        if (isClaudeEnabled) {
            claudeBackend.onAppForegrounded()
            // the CLI may have been installed or signed in from a terminal meanwhile
            if (claudeAccount.state.value != com.example.gemini.data.agent.claude.ClaudeStatus.READY) claudeAccount.refreshStatus()
        }
        if (isOpenCodeEnabled) openCodeBackend.onAppForegrounded()
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
                claudeBackend.startNewChat()
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach()
                claudeBackend.detach()
                openCodeBackend.startNewChat()
            }
            AgentKind.AGY -> {
                claudeBackend.detach()
                openCodeBackend.detach()
                agyBackend.startNewChat()
            }
        }
    }
    fun selectConversation(id: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> {
                agyBackend.detach()
                openCodeBackend.detach()
                claudeBackend.selectConversation(id)
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach()
                claudeBackend.detach()
                openCodeBackend.selectConversation(id)
            }
            AgentKind.AGY -> {
                claudeBackend.detach()
                openCodeBackend.detach()
                agyBackend.selectConversation(id)
            }
        }
    }
    fun startPersistentStream(conversationId: String) = agyBackend.startPersistentStream(conversationId)
    fun deleteConversation(id: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> claudeBackend.deleteConversation(id)
            AgentKind.OPENCODE -> openCodeBackend.deleteConversation(id)
            AgentKind.AGY -> agyBackend.deleteConversation(id)
        }
        // Jarvis forgets it too: a deleted Jarvis chat is replaced by a new one on the next message
        com.example.gemini.data.jarvis.JarvisController.onChatDeleted(id)
    }
    fun forkConversation(id: String) {
        if (agentOf(id) == AgentKind.OPENCODE) {
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
        else -> agyBackend.selectModel(modelId)
    }
    /** `/plu:` plugin skills and custom slash commands are expanded here, for both agents. */
    fun sendMessage(content: String): Unit = PromptExpansion.expand(content).let { expanded ->
        // a Jarvis chat on screen: its message carries Jarvis' hidden note (or goes to the voice Jarvis chat as a typed turn)
        val jarvis = com.example.gemini.data.jarvis.JarvisController
        val it = (if (jarvis.isAttached) jarvis.interceptScreenMessage(currentConversation.value?.id, expanded) else expanded) ?: return
        when {
            isClaudeActive -> claudeBackend.sendMessage(it)
            isOpenCodeActive -> openCodeBackend.sendMessage(it)
            else -> agyBackend.sendMessage(it)
        }
    }
    val isTranscribingAudio = agyBackend.isTranscribingAudio
    fun transcribeAudioFile(file: java.io.File, onDone: (String) -> Unit, onError: (String) -> Unit) = agyBackend.transcribeAudioFile(file, onDone, onError)
    fun retryMessage(messageId: String) = when {
        isClaudeActive -> claudeBackend.regenerate(messageId)
        isOpenCodeActive -> openCodeBackend.regenerate(messageId)
        else -> agyBackend.retryMessage(messageId)
    }
    fun prepareEditMessage(messageId: String): String? {
        if (isOpenCodeActive) {
            openCodeNotSupported("Editing a sent message")
            return null
        }
        if (isClaudeActive) {
            claudeNotSupported("Editing a sent message")
            return null
        }
        return agyBackend.prepareEditMessage(messageId)?.let(PromptExpansion::collapse)
    }
    fun revertAndEditLastUserMessage(targetMsg: ChatMessage, onRestored: (String) -> Unit) = if (isOpenCodeActive) openCodeBackend.editMessage(targetMsg.id, restoreFiles = false) { onRestored(PromptExpansion.collapse(it)) } else if (isClaudeActive) claudeBackend.editMessage(targetMsg.id, restoreCode = false) { onRestored(PromptExpansion.collapse(it)) } else agyBackend.revertAndEditLastUserMessage(targetMsg) { onRestored(PromptExpansion.collapse(it)) }

    /** Claude: edit a sent prompt in a copy of the chat that ends before it; optionally restore the files changed since. */
    fun editClaudeMessage(targetMsg: ChatMessage, restoreCode: Boolean, onRestored: (String) -> Unit) =
        if (isOpenCodeActive) openCodeBackend.editMessage(targetMsg.id, restoreCode) { onRestored(PromptExpansion.collapse(it)) }
        else claudeBackend.editMessage(targetMsg.id, restoreCode) { onRestored(PromptExpansion.collapse(it)) }

    /** Sidebar export / share of a Claude chat that is not open. */
    suspend fun loadClaudeMessages(conversationId: String): List<ChatMessage> = claudeBackend.loadMessages(conversationId)

    /** Sidebar export / share of an OpenCode chat that is not open. */
    suspend fun loadOpenCodeMessages(conversationId: String): List<ChatMessage> = openCodeBackend.loadMessages(conversationId)

    fun renameConversation(id: String, title: String) {
        when (agentOf(id)) {
            AgentKind.CLAUDE -> claudeBackend.renameConversation(id, title)
            AgentKind.OPENCODE -> openCodeBackend.renameConversation(id, title)
            AgentKind.AGY -> agyBackend.renameConversation(id, title)
        }
    }
    fun stopStreaming() = when {
        isClaudeActive -> claudeBackend.stopStreaming()
        isOpenCodeActive -> openCodeBackend.stopStreaming()
        else -> agyBackend.stopStreaming()
    }

    /** Messages sent while the agent was working, waiting to be delivered (queued-messages card). */
    val queuedMessages = byAgent(agyBackend.queuedMessages, claudeBackend.queuedMessages, noQueued)
    fun sendQueuedNow(id: String) { if (isClaudeActive) claudeBackend.sendQueuedNow(id) else if (!isOpenCodeActive) agyBackend.sendQueuedNow(id) }
    fun deleteQueued(id: String) { if (!isClaudeActive && !isOpenCodeActive) agyBackend.deleteQueued(id) }
    fun applyQuotaSummary(summary: com.example.gemini.domain.model.QuotaSummaryResponse) = agyBackend.applyQuotaSummary(summary)
    fun refreshQuotas(force: Boolean = true, showToastFeedback: Boolean = false) {
        if (isOpenCodeActive) {
            openCodeBackend.refreshModels()
        } else if (isClaudeActive) {
            claudeBackend.refreshInfo(force)
            agyBackend.refreshQuotas(force, showToastFeedback = false)
        } else {
            agyBackend.refreshQuotas(force, showToastFeedback)
        }
    }
    fun approveAndExecuteTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, scope: String? = null) = if (isOpenCodeActive) openCodeBackend.approveTool(toolCall) else if (isClaudeActive) claudeBackend.approveTool(toolCall) else agyBackend.approveAndExecuteTerminalTool(toolCall, messageId, scope)
    fun rejectTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, reason: String? = null) = if (isOpenCodeActive) openCodeBackend.rejectTool(toolCall, reason) else if (isClaudeActive) claudeBackend.rejectTool(toolCall, reason) else agyBackend.rejectTerminalTool(toolCall, messageId, reason)
    fun approveAllPendingTools(list: List<PendingToolApproval>) = if (isOpenCodeActive) list.forEach { openCodeBackend.approveTool(it.toolCall) } else if (isClaudeActive) list.forEach { claudeBackend.approveTool(it.toolCall) } else agyBackend.approveAllPendingTools(list)
    fun rejectAllPendingTools(list: List<PendingToolApproval>, reason: String? = null) = if (isOpenCodeActive) list.forEach { openCodeBackend.rejectTool(it.toolCall, reason) } else if (isClaudeActive) list.forEach { claudeBackend.rejectTool(it.toolCall, reason) } else agyBackend.rejectAllPendingTools(list, reason)
    fun terminateRunningTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) = if (isOpenCodeActive) openCodeBackend.stopStreaming() else if (isClaudeActive) claudeBackend.stopStreaming() else agyBackend.terminateRunningTerminalTool(toolCall, messageId)
    fun proceedAfterTermination(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, userInstructions: String?) {
        if (isClaudeActive || isOpenCodeActive) {
            store.terminatedToolDialog.value = null
            return
        }
        agyBackend.proceedAfterTermination(toolCall, messageId, userInstructions)
    }
    fun submitUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>, summaryDisplay: String) = if (isOpenCodeActive) openCodeBackend.submitChoices(toolCall, responses) else if (isClaudeActive) claudeBackend.submitChoices(toolCall, responses, summaryDisplay) else agyBackend.submitUserChoices(toolCall, messageId, responses, summaryDisplay)
    fun skipUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto> = emptyList()) = if (isOpenCodeActive) openCodeBackend.cancelChoices(toolCall) else if (isClaudeActive) claudeBackend.skipChoices(toolCall) else agyBackend.skipUserChoices(toolCall, messageId, responses)
    fun cancelUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) = if (isOpenCodeActive) openCodeBackend.cancelChoices(toolCall) else if (isClaudeActive) claudeBackend.cancelChoices(toolCall) else agyBackend.cancelUserChoices(toolCall, messageId)

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
    val allConversations: StateFlow<List<Conversation>> = combine(_conversations, claudeBackend.conversations, openCodeBackend.conversations, enabledAgents) { agy, claude, openCode, enabled ->
        // a disabled agent's chats stay on disk but are not listed
        ((if (AgentKind.AGY in enabled) agy else emptyList()) + (if (AgentKind.CLAUDE in enabled) claude else emptyList()) +
            (if (AgentKind.OPENCODE in enabled) openCode else emptyList()))
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
    val messages: StateFlow<List<ChatMessage>> = byAgent(_messages, claudeBackend.messages, openCodeBackend.messages)

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
    val isUploadingAttachment: StateFlow<Boolean> = _isUploadingAttachment.asStateFlow()

    fun addAttachmentsFromUris(uris: List<android.net.Uri>, context: android.content.Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isUploadingAttachment.value = true
            try {
                for (uri in uris) {
                    processAndUploadUri(uri, context)
                }
            } finally {
                _isUploadingAttachment.value = false
            }
        }
    }

    fun addAttachmentFromUri(uri: android.net.Uri, context: android.content.Context) {
        addAttachmentsFromUris(listOf(uri), context)
    }

    private suspend fun processAndUploadUri(uri: android.net.Uri, context: android.content.Context) {
        try {
            val contentResolver = context.contentResolver
            var fileName = "attachment_${System.currentTimeMillis()}"
            var fileSize = 0L

            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = it.getLong(sizeIndex)
                }
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = contentResolver.getType(uri)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
            val isImg = mimeType.startsWith("image/")
            val isAud = mimeType.startsWith("audio/")

            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }

            if (bytes != null) {
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                // Antigravity keeps attachments as hub artifacts; a Claude chat sends them itself (inline or saved
                // on the bridge when the message is sent), so nothing goes to the AGY hub
                val savedHostUri = if (isClaudeActive || isOpenCodeActive) uri.toString() else {
                    agyHubClient.saveMediaAsArtifact(
                        mimeType = mimeType,
                        base64Data = base64,
                        description = fileName,
                        thumbnailBase64 = if (isImg) base64 else "",
                        hubUrl = AuthPreferences.currentHubUrl
                    ).getOrNull() ?: uri.toString()
                }

                val att = com.example.gemini.domain.model.ChatAttachment(
                    id = "att_${System.currentTimeMillis()}_${(0..999).random()}",
                    name = fileName,
                    path = savedHostUri,
                    isImage = isImg,
                    isAudio = isAud,
                    localUri = uri.toString(),
                    size = if (fileSize > 0) fileSize else bytes.size.toLong(),
                    mimeType = mimeType,
                    base64 = base64
                )
                _attachments.value = _attachments.value + att
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Failed to add attachment from uri $uri: ${e.message}")
        }
    }

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
        if (target != null && target.path.isNotBlank() && !target.path.startsWith("content://") && !target.path.startsWith("/data/")) {
            viewModelScope.launch(Dispatchers.IO) {
                agyHubClient.deleteMediaArtifact(target.path)
            }
        }
        _attachments.value = _attachments.value.filter { it.id != attachmentId }
    }

    fun clearAttachments() {
        val atts = _attachments.value
        viewModelScope.launch(Dispatchers.IO) {
            for (target in atts) {
                if (target.path.isNotBlank() && !target.path.startsWith("content://") && !target.path.startsWith("/data/")) {
                    agyHubClient.deleteMediaArtifact(target.path)
                }
            }
        }
        _attachments.value = emptyList()
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
    val conversationError: StateFlow<String?> = byAgent(_conversationError, claudeBackend.conversationError, openCodeBackend.conversationError)


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
        // the first chat follows the new-chat agent (Antigravity's own start-up chat only fills in when it is AGY)
        if ((isClaudeEnabled || isOpenCodeEnabled) && (newChatAgent.value != AgentKind.AGY || AgentKind.AGY !in enabledAgents.value)) startNewChat()
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
}

private data class ExecutedToolResult(
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
    val status: String
)
