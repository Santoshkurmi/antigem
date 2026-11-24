package ai.antigem.data.homescreen

import ai.antigem.R
import ai.antigem.domain.model.AgentKind

/**
 * The things a home-screen shortcut or a button of the Quick actions widget can do. [color] is the icon's background,
 * [glyph] a white 24dp vector drawn on it.
 */
enum class HomeAction(
    val id: String,
    val label: String,
    val description: String,
    val color: Long,
    val glyph: Int,
    /** Only for this agent's chats (hidden while the agent is turned off). */
    val agent: AgentKind? = null
) {
    NEW_CHAT("new_chat", "New chat", "A new chat with your default agent", 0xFF4F7DF3, R.drawable.ic_home_add_chat),
    NEW_AGY("new_agy", "AGY chat", "A new Antigravity chat", 0xFF3B82F6, R.drawable.ic_home_add_chat, AgentKind.AGY),
    NEW_CLAUDE("new_claude", "Claude chat", "A new Claude Code chat", 0xFFD97757, R.drawable.ic_home_add_chat, AgentKind.CLAUDE),
    NEW_OPENCODE("new_opencode", "OpenCode chat", "A new OpenCode chat", 0xFF10B981, R.drawable.ic_home_add_chat, AgentKind.OPENCODE),
    NEW_CODEX("new_codex", "Codex chat", "A new Codex chat", 0xFF8B5CF6, R.drawable.ic_home_add_chat, AgentKind.CODEX),
    TERMINAL("terminal", "Terminal", "The terminal page", 0xFF334155, R.drawable.ic_home_terminal),
    TERMINAL_WINDOW("terminal_window", "AntiTerm", "The terminal in a window of its own", 0xFF1E293B, R.drawable.ic_home_window),
    BROWSER("browser", "Browser", "AntiGem's web browser", 0xFF0EA5E9, R.drawable.ic_home_browser),
    IDE("ide", "Code", "The code editor", 0xFF6366F1, R.drawable.ic_home_code),
    FILES("files", "Files", "The file manager", 0xFFF59E0B, R.drawable.ic_home_folder),
    NOTES("notes", "Notes", "Your notes", 0xFFEC4899, R.drawable.ic_home_notes),
    RUN("run", "Run", "Commands and their live output", 0xFF22C55E, R.drawable.ic_home_run),
    FLOATING_CHAT("float", "Float chat", "The chat in a floating window over other apps", 0xFF14B8A6, R.drawable.ic_home_float),
    VOICE_CALL("voice", "Voice call", "Call Antigravity in the last chat that had a call", 0xFF3E6FE0, R.drawable.ic_voice_call),
    JARVIS("jarvis", "Jarvis", "Talk to Jarvis", 0xFF0F172A, R.drawable.ic_jarvis_mic),
    MINI_APPS("miniapps", "Mini apps", "Your mini apps", 0xFFA855F7, R.drawable.ic_home_apps),
    SETTINGS("settings", "Settings", "AntiGem's settings", 0xFF64748B, R.drawable.ic_home_settings);

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
