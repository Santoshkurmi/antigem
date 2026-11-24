package ai.antigem.data.homescreen

import ai.antigem.R
import ai.antigem.domain.model.AgentKind
import ai.antigem.i18n.L

/**
 * The things a home-screen shortcut or a button of the Quick actions widget can do. [color] is the icon's background,
 * [glyph] a white 24dp vector drawn on it.
 */
enum class HomeAction(
    val id: String,
    private val labelText: () -> String,
    private val descriptionText: () -> String,
    val color: Long,
    val glyph: Int,
    /** Only for this agent's chats (hidden while the agent is turned off). */
    val agent: AgentKind? = null
) {
    NEW_CHAT("new_chat", { L.homescreen.homeAction.newChat }, { L.homescreen.homeAction.aNewChatWithYourDefault }, 0xFF4F7DF3, R.drawable.ic_home_add_chat),
    NEW_AGY("new_agy", { L.homescreen.homeAction.agyChat }, { L.homescreen.homeAction.aNewAntigravityChat }, 0xFF3B82F6, R.drawable.ic_home_add_chat, AgentKind.AGY),
    NEW_CLAUDE("new_claude", { L.homescreen.homeAction.claudeChat }, { L.homescreen.homeAction.aNewClaudeCodeChat }, 0xFFD97757, R.drawable.ic_home_add_chat, AgentKind.CLAUDE),
    NEW_OPENCODE("new_opencode", { L.homescreen.homeAction.opencodeChat }, { L.homescreen.homeAction.aNewOpencodeChat }, 0xFF10B981, R.drawable.ic_home_add_chat, AgentKind.OPENCODE),
    NEW_CODEX("new_codex", { L.homescreen.homeAction.codexChat }, { L.homescreen.homeAction.aNewCodexChat }, 0xFF8B5CF6, R.drawable.ic_home_add_chat, AgentKind.CODEX),
    TERMINAL("terminal", { L.homescreen.homeAction.terminal }, { L.homescreen.homeAction.theTerminalPage }, 0xFF334155, R.drawable.ic_home_terminal),
    TERMINAL_WINDOW("terminal_window", { "AntiTerm" }, { L.homescreen.homeAction.theTerminalInAWindowOf }, 0xFF1E293B, R.drawable.ic_home_window),
    BROWSER("browser", { L.homescreen.homeAction.browser }, { L.homescreen.homeAction.antigemsWebBrowser }, 0xFF0EA5E9, R.drawable.ic_home_browser),
    IDE("ide", { L.homescreen.homeAction.code }, { L.homescreen.homeAction.theCodeEditor }, 0xFF6366F1, R.drawable.ic_home_code),
    FILES("files", { L.homescreen.homeAction.files }, { L.homescreen.homeAction.theFileManager }, 0xFFF59E0B, R.drawable.ic_home_folder),
    NOTES("notes", { L.homescreen.homeAction.notes }, { L.homescreen.homeAction.yourNotes }, 0xFFEC4899, R.drawable.ic_home_notes),
    RUN("run", { L.homescreen.homeAction.run }, { L.homescreen.homeAction.commandsAndTheirLiveOutput }, 0xFF22C55E, R.drawable.ic_home_run),
    FLOATING_CHAT("float", { L.homescreen.homeAction.floatChat }, { L.homescreen.homeAction.theChatInAFloatingWindow }, 0xFF14B8A6, R.drawable.ic_home_float),
    VOICE_CALL("voice", { L.homescreen.homeAction.voiceCall }, { L.homescreen.homeAction.callAntigravityInTheLastChat }, 0xFF3E6FE0, R.drawable.ic_voice_call),
    JARVIS("jarvis", { "Jarvis" }, { L.homescreen.homeAction.talkToJarvis }, 0xFF0F172A, R.drawable.ic_jarvis_mic),
    MINI_APPS("miniapps", { L.homescreen.homeAction.miniApps }, { L.homescreen.homeAction.yourMiniApps }, 0xFFA855F7, R.drawable.ic_home_apps),
    SETTINGS("settings", { L.homescreen.homeAction.settings }, { L.homescreen.homeAction.antigemsSettings }, 0xFF64748B, R.drawable.ic_home_settings);
    val label: String get() = labelText()
    val description: String get() = descriptionText()

    val target: HomeLinks.Target get() = when (this) {
        NEW_CHAT -> HomeLinks.Target.NewChat(null)
        NEW_AGY, NEW_CLAUDE, NEW_OPENCODE, NEW_CODEX -> HomeLinks.Target.NewChat(agent)
        TERMINAL -> HomeLinks.Target.OpenPage(HomeLinks.Page.TERMINAL)
        TERMINAL_WINDOW -> HomeLinks.Target.TerminalWindow
        BROWSER -> HomeLinks.Target.OpenPage(HomeLinks.Page.BROWSER)
        IDE -> HomeLinks.Target.OpenPage(HomeLinks.Page.IDE)
        FILES -> HomeLinks.Target.OpenPage(HomeLinks.Page.FILES)
        NOTES -> HomeLinks.Target.OpenPage(HomeLinks.Page.NOTES)
        RUN -> HomeLinks.Target.OpenPage(HomeLinks.Page.RUN)
        FLOATING_CHAT -> HomeLinks.Target.FloatingChat
        VOICE_CALL -> HomeLinks.Target.VoiceCall
        JARVIS -> HomeLinks.Target.Jarvis
        MINI_APPS -> HomeLinks.Target.MiniApps
        SETTINGS -> HomeLinks.Target.Settings()
    }

    /** Shown for these enabled agents (the agent-specific new chats only while their agent is on). */
    fun availableFor(enabled: Collection<AgentKind>): Boolean = agent == null || agent in enabled

    companion object {
        fun byId(id: String): HomeAction? = entries.firstOrNull { it.id == id }

        /** The Quick actions widget's buttons until the user picks their own. */
        val DEFAULT_WIDGET = listOf(NEW_CHAT, TERMINAL, BROWSER, IDE, FILES)
    }
}
