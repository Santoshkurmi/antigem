package com.example.gemini.data.jarvis

/**
 * What Jarvis knows about using AntiGem ("how do I add an MCP server", "where is the theme"), so it can guide the user by
 * voice. The settings pages come from the app's own list, so their names are always the real ones.
 */
object JarvisAppGuide {

    class Entry(val topic: String, val keywords: List<String>, val text: String)

    private fun settingsPages(): String = runCatching {
        com.example.gemini.ui.settings.SettingsSection.values()
            .filter { it != com.example.gemini.ui.settings.SettingsSection.MAIN && it != com.example.gemini.ui.settings.SettingsSection.CLAUDE_PAGE }
            .joinToString("\n") { "- ${it.title} (jarvis_open_settings section=${it.name.lowercase()}): ${it.subtitle}" }
    }.getOrDefault("")

    private val entries: List<Entry> by lazy {
        listOf(
            Entry("overview", listOf("what is", "antigem", "app", "features", "can you", "overview"), """
AntiGem runs two coding agents on the phone: Antigravity (agy, Google) and Claude Code (Anthropic's claude CLI), each in normal chats.
It also has a Linux terminal, a code editor (IDE) with Git, an in-app browser, notes and to-dos, built-in MCP servers, and custom
plugins. Screens: chat, ide, terminal, browser, notes (jarvis_open_screen). Settings: open the chat list (the side drawer of the
chat screen) and tap Settings at its bottom left, or ask Jarvis to open a settings page (jarvis_open_settings).
""".trim()),
            Entry("settings", listOf("settings", "where", "find", "page", "menu", "option", "configure"), "Settings pages:\n" + settingsPages()),
            Entry("agents", listOf("agent", "antigravity", "claude", "both", "turn on", "enable", "switch agent", "new chat agent"), """
Settings → Agents turns Antigravity, Claude Code or both on. A turned-off agent does not run and its chats are hidden.
Which agent new chats use is the new_chat_agent setting (also the agent chip on a new chat). Each chat keeps its own agent.
""".trim()),
            Entry("claude", listOf("claude", "sign in", "login", "install claude", "provider", "openrouter", "kimi", "deepseek", "effort", "thinking", "permission mode", "claude.md", "memory"), """
Claude Code: Settings → Claude Code has the account (install or update the CLI, sign in with a Claude subscription or a Console
account), models and chat defaults (model, effort, thinking), permission rules and the default permission mode, the sandbox,
MCP servers, plugins and marketplaces, CLAUDE.md and memory. Third-party providers (any Anthropic-compatible API such as
OpenRouter, Kimi, DeepSeek) are added there with their own key; their models show in the model picker. In a chat you can change
model, effort, thinking and permission mode any time (jarvis_set_model / set_effort / set_thinking / set_permission_mode).
""".trim()),
            Entry("antigravity", listOf("antigravity", "agy", "google", "quota", "gemini", "sign in google", "hub"), """
Antigravity: the app talks to the agy language server (the hub). Sign in with Google from the account area; the model picker
shows live quota. Settings → Antigravity → Commands & Permissions has the auto-run policy, the sandbox and tool approvals; per-project rules
are there too. MCP servers, skills and Google plugins are in Settings → Antigravity → MCP Servers and Skills & Plugins.
""".trim()),
            Entry("mcp", listOf("mcp", "server", "tool", "jarvis mcp", "browser mcp", "terminal mcp", "android mcp", "notes mcp", "add mcp", "preset"), """
MCP servers give the agents tools. Antigravity: Settings → Antigravity → MCP Servers. Claude Code: Settings → Claude Code → MCP. Both have
one-tap presets for the built-in servers: Browser (open pages, click, type, read, screenshots), Terminal (sessions, commands,
output), Android (see and control the phone; must be allowed in Settings → Android Automation), Notes & To-dos, Custom Plugin,
Plugin Developer, Stitch, and Jarvis (this voice control). The Jarvis server must be added to the agent that runs Jarvis:
Settings → Jarvis → Setup has a one-tap Add button.
""".trim()),
            Entry("plugins", listOf("plugin", "extension", "skill", "install plugin", "/plu"), """
Custom AntiGem plugins (Settings → Custom AntiGem Plugins) add MCP tools and skills. Install from a ZIP or a GitHub repository;
enable, disable, reload and update without restarting. In a chat, type /plu: to use a plugin or one of its skills.
Your own slash commands that expand into full prompts are in Settings → Slash Commands.
""".trim()),
            Entry("terminal", listOf("terminal", "shell", "linux", "ssh", "apt", "pkg", "command line", "font", "theme terminal"), """
The terminal is a full Linux environment (apt/pkg, bash, Python, Node.js, Git, Go). Multiple sessions, themes, font sizes and
quick keys. Settings → Terminal & Shell has SSH options, local tools and styling. Settings → ADB Connect links the terminal's adb
to this phone over Wireless debugging.
""".trim()),
            Entry("ide", listOf("ide", "editor", "code", "file", "project", "open project", "git", "commit", "push", "clone"), """
The IDE screen is a code editor with tabs, find and replace, a file explorer and a terminal sheet. Projects are folders; the
active project is where new chats work. Git: clone, status, diffs, commit, pull and push from the source control view.
Jarvis can open projects and files (jarvis_ide_open_project, jarvis_ide_open_file) and search code (jarvis_ide_search_code).
""".trim()),
            Entry("browser", listOf("browser", "web", "page", "url", "devtools", "eruda", "desktop mode"), """
The in-app browser previews local dev servers and web apps; desktop and mobile modes and Eruda DevTools are built in.
jarvis_open_url opens a page there.
""".trim()),
            Entry("notes", listOf("note", "notes", "todo", "to-do", "list", "reminder"), """
Notes and to-do lists are on the notes screen and in the floating bubble. Agents can read and change them through the Notes MCP
(add the Notes preset in MCP settings).
""".trim()),
            Entry("appearance", listOf("theme", "dark", "light", "font", "size", "appearance", "look", "bubble", "switcher", "floating"), """
Settings → Appearance & Theme: the mode (Light, Dark or Auto), a gallery to pick one light theme and one dark theme (light:
Porcelain (default), Lavender, Sky (these three recommended), Sakura, Mint, Linen, Paper, Solarized Light; dark: Midnight Aurora (default),
Obsidian, Graphite (these three recommended), Midnight, High Contrast, Amber Night (AntiGem's original dark look), Mocha, Neon City,
Aurora, Nord, Dracula, Velvet, Deep Ocean, Ember, Forest, Sunset, Solarized Dark, plus custom ones; the chat, the IDE editor, the terminal and the code blocks all follow it), chat font size, grouping chats by workspace, the floating switcher (always on: its layout, items and their order) and the floating bubble.
Jarvis switches with jarvis_set_setting key=theme (or theme_mode) when the user allowed app settings. New themes are made by any chat
with the Theme MCP (Theme preset in Settings → Antigravity → MCP Servers or Claude Code → MCP): "create a neon cyberpunk dark theme".
""".trim()),
            Entry("backups", listOf("backup", "restore", "rootfs", "uninstall", "clear data", "update", "apk"), """
Settings → Backups & Restore backs up and restores the Linux rootfs, projects and chat history to phone storage. Data Loss
Protection has an uninstall shield and a guard against Clear data. App updates are checked in the app.
""".trim()),
            Entry("server_mode", listOf("server", "vps", "remote", "ssh server", "server mode"), """
Server mode (Settings → Servers): the bridge, the Antigravity hub, Claude Code, projects and the shell run on your own Linux server,
the phone only shows the UI. Turn on Remote server (SSH), enter the bridge and hub addresses, SSH host, user and key, Save, Reopen.
The full guide is docs/remote-server.md in the AntiGem repository.
""".trim()),
            Entry("jarvis", listOf("jarvis", "voice", "hands-free", "hands free", "wake", "trigger", "orb", "listen", "send", "quiet", "headset", "tile"), """
Jarvis (Settings → Jarvis): talk to the app hands-free. Triggers: tap the orb, the notification's Talk button, the Quick Settings
tile "Jarvis", a headset / Bluetooth button (one press talks or sends, two presses stop), or a wake phrase while hands-free is on.
Long-press the orb for the Jarvis panel (tasks, conversation, typing, quick switches).
Sending: by default nothing is sent while you pause to think; end with a send phrase ("send it", "send kar do", "bhej do") or tap
Send / press the headset button. A cancel phrase ("scratch that", "never mind", "rehne do") throws the message away. Both phrase
lists are editable in Settings → Jarvis → Listening & sending (the phrase lists open with Edit), where "Send automatically when I pause" can also be switched on.
After Jarvis speaks it listens for a reply when it expects one (or always / never); with no word at all for a few seconds the
mic closes. Settings → Jarvis → Personality & you: whom Jarvis speaks as (female, male, neutral: matters in Hindi and similar
languages) and notes about you (what to call you, language, context), sent with the first message of every Jarvis chat.
The side-talk filter ignores what you say to other people. Quiet mode shows answers instead of speaking. Keep-screen-on, orb size and look are in the Look section.
Jarvis' own options can be changed by voice (jarvis_set_setting keys starting with jarvis_).
""".trim()),
            Entry("tasks", listOf("task", "background", "monitor", "manager", "running", "progress", "status", "redirect"), """
Big jobs run as Jarvis tasks: each in its own chat that Jarvis watches. Jarvis says when one finishes, fails, gets stuck, needs an
approval or asks a question, and can pass your answer or a new direction on. Ask "how is the build going", "tell the build to also
run the tests", "stop the build". The tasks are listed in the Jarvis panel (long-press the orb) with open and stop buttons.
""".trim()),
            Entry("troubleshooting", listOf("not working", "problem", "error", "broken", "fix", "cannot", "can't", "offline", "doesn't", "mic", "microphone"), """
Jarvis can't control the app: the Jarvis MCP server is not added to the agent running Jarvis (Settings → Jarvis → Setup → Add).
Jarvis doesn't hear: allow the microphone (Setup), or switch Voice input to "This device". Jarvis is silent: check the voice in
Voice output (Test voice) and that quiet mode is off. Wake phrase doesn't work: hands-free must be on, Trigger set to Wake phrase.
Chats don't answer: jarvis_get_health shows the bridge, the hub and Claude Code; restart the server from Settings → Servers.
""".trim())
        )
    }

    fun topics(): List<String> = entries.map { it.topic }

    /** The entries that best match [query] (a topic name or words), best first. */
    fun lookup(query: String, max: Int = 3): List<Entry> {
        val q = query.lowercase().trim()
        entries.firstOrNull { it.topic == q }?.let { return listOf(it) }
        val words = Regex("[a-z0-9.-]+").findAll(q).map { it.value }.filter { it.length > 2 }.toSet()
        return entries.map { e ->
            var score = 0
            e.keywords.forEach { k -> if (q.contains(k)) score += 3 + k.length / 4 else if (k.split(' ').any { it in words }) score += 1 }
            if (e.topic in words) score += 4
            e to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }
}
