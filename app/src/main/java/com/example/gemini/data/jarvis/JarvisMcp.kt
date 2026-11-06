package com.example.gemini.data.jarvis

import android.content.Context
import com.example.gemini.data.agent.ChatCompletionMonitor
import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.FileNode
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * jarvis_mcp: lets the model drive the AntiGem app directly (no tapping or scrolling): its voice, tasks it manages in their
 * own chats, chats of both agents, models and chat settings, approvals, screens and settings pages, the IDE's projects and
 * files, app help and configuration, usage, and Jarvis' own memory. Served by AndroidLocalBridgeServer at /jarvis. All
 * state is read and changed through the app's own objects.
 */
object JarvisMcp {

    const val INSTRUCTIONS = "Jarvis controls the AntiGem app for a user who is talking hands-free. The first message of a Jarvis chat " +
        "has the full guide and tool list; jarvis_help repeats it. Call these tools directly by name (no searching, no config reading). " +
        "Everything the user should hear goes through jarvis_speak (short, plain sentences, no markdown); jarvis_ask asks and returns the " +
        "spoken answer. Big jobs go to jarvis_task_start (its own watched chat); the phone reports back with [Phone event] messages. " +
        "Before longer steps call jarvis_status with 2-6 words. App help: jarvis_app_guide, jarvis_get_config, jarvis_open_settings. " +
        "These tools change the app directly; never use tap/scroll tools for what they can do. Ask before deleting anything."

    private const val DEFAULT_LIMIT = 15
    private const val MAX_LIMIT = 50

    // ---------------- schema helpers

    private fun prop(type: String, description: String, enum: List<String>? = null) = JSONObject().apply {
        put("type", type); put("description", description)
        enum?.let { put("enum", JSONArray(it)) }
    }

    private fun tool(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    private val chatId = "id" to prop("string", "Chat id from jarvis_list_chats (default: the chat on screen)")
    private val taskId = "id" to prop("string", "Task id (from the context note, jarvis_task_list or jarvis_task_start; the first 8 characters are enough)")
    private val limit = "limit" to prop("integer", "Entries per page, 1-$MAX_LIMIT (default $DEFAULT_LIMIT)")
    private val offset = "offset" to prop("integer", "Entries to skip (default 0)")

    fun tools(): JSONArray = JSONArray().apply {
        // ---- voice
        put(tool("jarvis_speak",
            "Say something to the user out loud (in quiet mode it is shown instead). This is the only way the user hears you. One to three short sentences, " +
                "no markdown, code or paths. Returns at once; later calls are spoken in order.",
            listOf("text"),
            "text" to prop("string", "What to say"),
            "listen_after" to prop("boolean", "true whenever your words invite a reply (a question, an offer like \"Want me to run it?\", a choice, a confirmation): " +
                "the microphone opens and the answer comes as the user's next message. false for a final statement. Omitted: listens only if the text ends with a question mark."),
            "wait" to prop("boolean", "Return only after it has been said (default false)")))
        put(tool("jarvis_ask",
            "Ask the user a question out loud and wait for the spoken answer (up to about 45 seconds). Returns the answer text, or \"no answer\". " +
                "Use it before destructive or ambiguous actions, and whenever you are not sure what the user wants.",
            listOf("question"),
            "question" to prop("string", "The question, short and answerable with a few words"),
            "timeout_seconds" to prop("integer", "How long to wait, 5-90 (default 45)")))
        put(tool("jarvis_status",
            "Show a short live status on the Jarvis card (2 to 6 words, e.g. \"Checking the build\"). Call it BEFORE every longer step so the user " +
                "sees progress. Silent unless speak=true. Returns at once.",
            listOf("text"),
            "text" to prop("string", "What you are doing now, present tense, 2-6 words"),
            "speak" to prop("boolean", "Also say it out loud (only if the user enabled spoken progress)")))
        put(tool("jarvis_ignore",
            "The words were not meant for you (the user talking to someone else, the TV, background speech): stay silent and stop listening. Call nothing else.",
            emptyList(), "reason" to prop("string", "A few words why (shown to the user)")))
        put(tool("jarvis_help",
            "Repeat the Jarvis guide and the full tool list (when your first message was compacted away or you are unsure how to do something).",
            emptyList()))
        // ---- tasks (Jarvis as a manager)
        put(tool("jarvis_task_start",
            "Start a big, independent job as a TASK in its own new chat that the phone watches for you (a build, a refactor, a bug fix, a long " +
                "investigation). The phone tells you with a [Phone event] message when it finishes, fails, gets stuck, needs an approval or asks " +
                "something. Do quick things yourself instead. Returns the task id.",
            listOf("goal"),
            "goal" to prop("string", "Full instructions for the task's agent, as clear and complete as the user's intent allows"),
            "title" to prop("string", "Short name, 2-5 words (e.g. \"Release build\")"),
            "agent" to prop("string", "claude or agy (default: the app's new-chat agent)", listOf("claude", "agy")),
            "model" to prop("string", "Model id or part of its name (default: the agent's default)"),
            "project" to prop("string", "Project name or path to work in (default: the active project)"),
            "open" to prop("boolean", "Also show the chat on screen (default false)")))
        put(tool("jarvis_task_list", "The tasks Jarvis manages: state (starting, running, waiting for approval, asking a question, no progress, done, failed, stopped), age and last note.",
            emptyList(), "include_finished" to prop("boolean", "Also list finished tasks (default true)"), limit))
        put(tool("jarvis_task_get", "One task in detail: goal, state, last note, and its latest reply or progress. Use it for \"how is X going?\".",
            listOf("id"), taskId, "messages" to prop("integer", "Also include this many of its newest messages (default 3, 0-10)")))
        put(tool("jarvis_task_reply",
            "Send the task a follow-up, a correction or a new direction (\"also run the tests\", \"use the other API\"). With interrupt=true a task " +
                "that is still working is stopped first and then gets this message.",
            listOf("id", "message"), taskId, "message" to prop("string", "What to tell the task's agent"),
            "interrupt" to prop("boolean", "Stop the running task first (default false)")))
        put(tool("jarvis_task_answer", "Answer the multiple-choice question a task is asking (option number, option name, or free text; \"skip\" lets it decide).",
            listOf("id", "answer"), taskId, "answer" to prop("string", "The answer")))
        put(tool("jarvis_task_stop", "Stop what a task is doing right now.", listOf("id"), taskId))
        put(tool("jarvis_task_open", "Show a task's chat on screen.", listOf("id"), taskId))
        put(tool("jarvis_task_close", "Stop watching a task and remove it from the list (the chat itself stays).", listOf("id"), taskId))
        // ---- everyday
        put(tool("jarvis_set_timer",
            "Set a timer that Jarvis announces out loud when it ends (cooking, laundry, breaks). Give seconds or minutes.",
            emptyList(),
            "minutes" to prop("number", "Length in minutes (can be fractional)"),
            "seconds" to prop("integer", "Length in seconds (added to minutes)"),
            "label" to prop("string", "What it is for, e.g. pasta")))
        put(tool("jarvis_list_timers", "The running timers and how long each has left.", emptyList()))
        put(tool("jarvis_cancel_timer", "Cancel a timer by id (from jarvis_list_timers), or all of them.", emptyList(),
            "id" to prop("integer", "Timer id"), "all" to prop("boolean", "Cancel every timer")))
        put(tool("jarvis_get_health",
            "Is everything up? The IDE bridge, the Antigravity hub, Claude Code (installed, signed in), the file server and the Jarvis voice.",
            emptyList()))
        put(tool("jarvis_open_url", "Open a web page in the in-app browser and show it. Use the browser tools afterwards to read or click on it.",
            listOf("url"), "url" to prop("string", "Address, e.g. https://example.com"),
            "new_tab" to prop("boolean", "Open in a new tab (default true)")))
        put(tool("jarvis_stop_all",
            "Emergency stop: silence Jarvis, drop queued messages, and stop the Jarvis chat's current work (tasks keep running). Use when the user says stop or cancel.", emptyList()))
        // ---- state and app help
        put(tool("jarvis_get_state",
            "Where the user is and what is going on: the screen, the IDE project and open files, the chat on screen, tasks, running chats, " +
                "approvals waiting, and Jarvis' own mode. The context note of each message already has most of this."))
        put(tool("jarvis_app_guide",
            "How to use AntiGem: where a setting is, how to add an MCP server, sign in, use the terminal, IDE, git, browser, notes, plugins, " +
                "server mode, Jarvis' own features, troubleshooting. Give a topic or the user's words.",
            listOf("topic"), "topic" to prop("string", "A topic (overview, settings, agents, claude, antigravity, mcp, plugins, terminal, ide, browser, notes, appearance, backups, server_mode, jarvis, tasks, troubleshooting) or words")))
        put(tool("jarvis_get_config",
            "The user's configuration: agents, Claude Code and Antigravity accounts and policies, MCP servers of both agents, Jarvis settings, look, projects.",
            emptyList(), "area" to prop("string", "What to read (default overview)", listOf("overview", "agents", "claude", "antigravity", "mcp", "jarvis", "appearance", "projects"))))
        put(tool("jarvis_open_settings", "Show a settings page (e.g. jarvis, appearance, mcp, claude, agents, servers, terminal, commands).",
            listOf("section"), "section" to prop("string", "Settings page name (jarvis_app_guide topic settings lists them)")))
        put(tool("jarvis_get_usage",
            "Plan usage: the 5-hour window and the 7-day window with percent used, percent left and when each resets (Claude Code and Antigravity), " +
                "plus context use and cost of the Claude chat on screen. Always name both windows when answering.",
            emptyList(),
            "agent" to prop("string", "Which agent (default both)", listOf("all", "claude", "agy")),
            "refresh" to prop("boolean", "Fetch fresh numbers first (default true)")))
        // ---- chats
        put(tool("jarvis_list_chats",
            "List chats of Claude Code and Antigravity, newest first, with paging. Jarvis' own hidden chats are left out unless asked for.",
            emptyList(),
            "agent" to prop("string", "Only this agent", listOf("all", "claude", "agy")),
            "running" to prop("boolean", "Only chats that are working right now"),
            "project" to prop("string", "Only chats of a project (part of its name or path)"),
            "query" to prop("string", "Words in the title"),
            "sort" to prop("string", "Order (default updated_desc)", listOf("updated_desc", "updated_asc", "created_desc", "title_asc")),
            "include_jarvis" to prop("boolean", "Also list Jarvis' own chats (default false)"),
            limit, offset,
            "page" to prop("integer", "1-based page number, instead of offset")))
        put(tool("jarvis_get_chat",
            "Status of one chat: running or idle, agent, model, project, waiting approvals and a preview of the last messages.",
            emptyList(), chatId))
        put(tool("jarvis_read_chat",
            "Read what is inside a chat, in pages. By default the newest messages first (from_end=true). Summarize for the user; do not read it out word for word.",
            emptyList(), chatId, limit, offset,
            "from_end" to prop("boolean", "Count from the newest message (default true)"),
            "role" to prop("string", "Only these messages", listOf("all", "user", "assistant")),
            "include_tools" to prop("boolean", "Include the tool calls each reply made (default false)"),
            "max_chars" to prop("integer", "Cut each message to this length (default 800)")))
        put(tool("jarvis_search_chats",
            "Find chats by title; with deep=true also look inside the most recent chats.",
            listOf("query"),
            "query" to prop("string", "Words to find"),
            "deep" to prop("boolean", "Also search message text of the 12 newest chats (slower)"),
            limit))
        put(tool("jarvis_new_chat",
            "Start a new chat. With a message it runs as a watched task (same as jarvis_task_start); without one an empty chat opens on screen. " +
                "Use it only when the user asked for a new chat; prefer jarvis_task_start for delegated work.",
            emptyList(),
            "agent" to prop("string", "claude or agy (default: the app's current new-chat agent)", listOf("claude", "agy")),
            "message" to prop("string", "The first message / task"),
            "model" to prop("string", "Model id or name (default: the agent's default)"),
            "project" to prop("string", "Project name or path to work in (default: the active project)"),
            "open" to prop("boolean", "Also show the chat on screen (default false)")))
        put(tool("jarvis_send_to_chat",
            "Send a message to ANOTHER existing chat (it then becomes a watched task). Use it ONLY when the user told you to (\"tell the build chat to run the tests\") " +
                "or the request clearly continues exactly what that chat is working on. NEVER for general questions or anything you can do yourself, " +
                "and never for a chat that is running.",
            listOf("id", "message", "reason"),
            chatId, "message" to prop("string", "The message"),
            "reason" to prop("string", "Why this goes to that chat", listOf("user_named_this_chat", "continues_that_chats_work"))))
        put(tool("jarvis_stop_chat", "Stop what a chat is doing right now.", emptyList(), chatId))
        put(tool("jarvis_open_chat", "Show a chat on screen (switches to the chat screen).", listOf("id"), chatId))
        put(tool("jarvis_rename_chat", "Rename a Claude Code chat.", listOf("id", "title"), chatId, "title" to prop("string", "New title")))
        put(tool("jarvis_fork_chat", "Copy a chat into a new one that continues from the same point.", listOf("id"), chatId))
        put(tool("jarvis_delete_chat",
            "Delete a chat for good. Ask the user with jarvis_ask first and pass confirm=true only after they said yes.",
            listOf("id", "confirm"), chatId, "confirm" to prop("boolean", "Must be true")))
        // ---- models and chat settings
        put(tool("jarvis_list_models",
            "Models available to each agent, marking the selected one.",
            emptyList(), "agent" to prop("string", "Which agent (default the chat on screen, or both)", listOf("all", "claude", "agy"))))
        put(tool("jarvis_set_model",
            "Change the model. With id it changes that chat or task (also one that is not on screen); without id scope=screen changes the chat on screen, " +
                "scope=jarvis the model of the Jarvis chat itself. The model can be a part of its name. Applies to the chat's next message.",
            listOf("model"),
            "id" to prop("string", "Chat or task id to change"),
            "model" to prop("string", "Model id or part of its name, e.g. opus, sonnet, flash"),
            "scope" to prop("string", "screen (default) or jarvis", listOf("screen", "jarvis"))))
        put(tool("jarvis_set_effort",
            "Claude Code chat on screen: set the reasoning effort.",
            listOf("level"), "level" to prop("string", "low, medium, high, xhigh, max or default")))
        put(tool("jarvis_set_thinking", "Claude Code chat on screen: turn extended thinking on or off.", listOf("enabled"),
            "enabled" to prop("boolean", "true or false")))
        put(tool("jarvis_set_permission_mode",
            "Claude Code chat on screen: how it asks for approval. default = ask for everything, acceptEdits = file edits are allowed, plan = plan only, " +
                "auto = decides itself, dontAsk = no prompts.",
            listOf("mode"), "mode" to prop("string", "The mode", listOf("default", "acceptEdits", "plan", "auto", "dontAsk"))))
        put(tool("jarvis_get_setting", "Read app and Jarvis settings (theme, font size, floating bubble, Jarvis' voice, listening, triggers, look…) with their allowed values.",
            emptyList(), "key" to prop("string", "One key, or omit for all")))
        put(tool("jarvis_set_setting",
            "Change a setting (keys from jarvis_get_setting). Jarvis' own options (keys starting with jarvis_) can always be changed; app settings " +
                "(theme_mode, chat_font_scale, floating_bubble…) only when the user allowed it. Approval and security policies cannot be changed here.",
            listOf("key", "value"),
            "key" to prop("string", "Setting key"), "value" to prop("string", "New value")))
        // ---- permissions
        put(tool("jarvis_list_permissions",
            "Requests waiting for approval in the chats Jarvis can reach (the Jarvis chat, tasks, chats it drives, and the chat on screen), with the exact action and a risk level.",
            emptyList()))
        put(tool("jarvis_resolve_permission",
            "Approve or reject waiting requests: one by key, or all. Read risky actions back to the user first.",
            listOf("approve"),
            "key" to prop("string", "Key from jarvis_list_permissions or the event (omit with all=true)"),
            "approve" to prop("boolean", "true = approve, false = reject"),
            "all" to prop("boolean", "Apply to every waiting request"),
            "reason" to prop("string", "When rejecting: what to tell the agent")))
        // ---- screens and IDE
        put(tool("jarvis_open_screen", "Switch the app to a screen.", listOf("screen"),
            "screen" to prop("string", "Where to go", listOf("chat", "ide", "terminal", "browser", "notes"))))
        put(tool("jarvis_ide_list_projects", "List the IDE's projects and which one is active.", emptyList(),
            "query" to prop("string", "Part of a name or path"), limit, offset))
        put(tool("jarvis_ide_open_project",
            "Make a project the active one and show the IDE. Give the name (or part of it) or the full path.",
            listOf("project"), "project" to prop("string", "Project name or path"),
            "show_ide" to prop("boolean", "Switch to the IDE screen (default true)")))
        put(tool("jarvis_ide_open_file",
            "Open a file in the IDE editor and show the IDE. Give a path (absolute, or relative to the project) or just the file name.",
            listOf("file"), "file" to prop("string", "Path or file name"),
            "project" to prop("string", "Open this project first (name or path)"),
            "show_ide" to prop("boolean", "Switch to the IDE screen (default true)")))
        put(tool("jarvis_ide_close_file", "Close an editor tab (default: the active one). Refuses when it has unsaved changes unless discard=true.",
            emptyList(), "file" to prop("string", "Path or file name"), "discard" to prop("boolean", "Close even with unsaved changes")))
        put(tool("jarvis_ide_get_open_files", "The IDE's open tabs, the active file and unsaved changes.", emptyList()))
        put(tool("jarvis_ide_search_code",
            "Search text in the files of the active project (or another project). Returns file, line number and the line, paged.",
            listOf("query"), "query" to prop("string", "Text to find"),
            "project" to prop("string", "Project name or path (default: the active project)"), limit, offset))
        // ---- session and memory
        put(tool("jarvis_new_session",
            "Start a fresh Jarvis chat on the next message (the user asked to start again, or the chat got long). Never do this by yourself while work is running.",
            emptyList(), "carry_over" to prop("boolean", "Bring the Jarvis memory and a short recap along (default true)")))
        put(tool("jarvis_memory_read", "Read the Jarvis memory note that survives new sessions.", emptyList()))
        put(tool("jarvis_memory_write",
            "Save to the Jarvis memory note (preferences, names, what is in progress). Keep it short.",
            listOf("text"), "text" to prop("string", "Text to save"),
            "mode" to prop("string", "replace (default) or append", listOf("replace", "append"))))
    }

    // ---------------- dispatcher

    suspend fun call(context: Context, name: String, args: JSONObject): JSONObject {
        if (!JarvisController.isAttached) return error("The app is still starting. Try again in a moment.")
        if (!JarvisPreferences.enabled.value) return error("Jarvis is turned off. The user can turn it on in Settings → Jarvis.")
        JarvisController.onToolCall(name)
        friendlyStatus(name, args)?.let { JarvisController.postStatus(it, explicit = false) }
        return try {
            when (name) {
                "jarvis_speak" -> {
                    val t = args.optString("text").trim()
                    if (t.isEmpty()) return error("text is empty")
                    val listenAfter = if (args.has("listen_after") && !args.isNull("listen_after")) args.optBoolean("listen_after") else null
                    JarvisController.say(t, listenAfter, args.optBoolean("wait"))
                    text("Said.")
                }
                "jarvis_ask" -> {
                    val q = args.optString("question").trim()
                    if (q.isEmpty()) return error("question is empty")
                    val secs = args.optInt("timeout_seconds", 45).coerceIn(5, 90)
                    text(JarvisController.ask(q, secs * 1000L) ?: "no answer")
                }
                "jarvis_status" -> {
                    val t = args.optString("text").trim()
                    if (t.isEmpty()) return error("text is empty")
                    JarvisController.postStatus(t, speak = args.optBoolean("speak"), explicit = true)
                    text("ok")
                }
                "jarvis_ignore" -> { JarvisController.onIgnored(args.optString("reason")); text("Ignored. Say nothing.") }
                "jarvis_help" -> text(JarvisSkill.guide() + "\n\n## jarvis_mcp tools\n" + JarvisSkill.catalog())
                "jarvis_task_start" -> taskStart(args)
                "jarvis_task_list" -> taskList(args)
                "jarvis_task_get" -> taskGet(args)
                "jarvis_task_reply" -> {
                    val t = JarvisTasks.find(args.optString("id").trim()) ?: return error("No task with that id. Use jarvis_task_list.")
                    text(JarvisTasks.reply(t.id, args.optString("message"), args.optBoolean("interrupt")))
                }
                "jarvis_task_answer" -> {
                    val t = JarvisTasks.find(args.optString("id").trim()) ?: return error("No task with that id.")
                    text(JarvisController.answerTaskQuestion(t.id, args.optString("answer")))
                }
                "jarvis_task_stop" -> {
                    val t = JarvisTasks.find(args.optString("id").trim()) ?: return error("No task with that id.")
                    text(JarvisTasks.stop(t.id))
                }
                "jarvis_task_open" -> {
                    val t = JarvisTasks.find(args.optString("id").trim()) ?: return error("No task with that id.")
                    vm.selectConversation(t.id)
                    vm.requestViewMode("CHAT")
                    text("Showing ${t.title}.")
                }
                "jarvis_task_close" -> {
                    val t = JarvisTasks.find(args.optString("id").trim()) ?: return error("No task with that id.")
                    JarvisTasks.close(t.id)
                    text("No longer watching ${t.title}.")
                }
                "jarvis_set_timer" -> {
                    val total = Math.round(args.optDouble("minutes", 0.0) * 60).toInt() + args.optInt("seconds", 0)
                    if (total !in 1..(24 * 3600)) return error("Give a length between 1 second and 24 hours.")
                    val t = JarvisController.addTimer(total, args.optString("label"))
                    text("Timer ${t.id} set for ${if (total >= 60) "${total / 60} min ${total % 60} s" else "$total s"}${if (t.label.isNotBlank()) " (${t.label})" else ""}.")
                }
                "jarvis_list_timers" -> json(JSONObject().put("timers", JSONArray().apply {
                    JarvisController.timers.value.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("left", inText(it.endsAt))) }
                }))
                "jarvis_cancel_timer" -> {
                    if (args.optBoolean("all")) { JarvisController.timers.value.forEach { JarvisController.cancelTimer(it.id) }; text("All timers cancelled.") }
                    else text(if (JarvisController.cancelTimer(args.optInt("id", -1))) "Cancelled." else "No such timer.")
                }
                "jarvis_get_health" -> health()
                "jarvis_open_url" -> {
                    var u = args.optString("url").trim()
                    if (u.isEmpty()) return error("url is required")
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                    withContext(Dispatchers.Main) {
                        com.example.gemini.ui.browser.BrowserSessionManager.instance.openUrl(u, newTab = args.optBoolean("new_tab", true))
                    }
                    vm.requestViewMode("BROWSER")
                    text("Opened $u.")
                }
                "jarvis_ide_search_code" -> ideSearchCode(args)
                "jarvis_stop_all" -> { JarvisController.stopEverything(); text("Stopped.") }
                "jarvis_get_state" -> getState()
                "jarvis_app_guide" -> appGuide(args)
                "jarvis_get_config" -> getConfig(args)
                "jarvis_open_settings" -> openSettings(args)
                "jarvis_get_usage" -> getUsage(args)
                "jarvis_list_chats" -> listChats(args)
                "jarvis_get_chat" -> getChat(args)
                "jarvis_read_chat" -> readChat(args)
                "jarvis_search_chats" -> searchChats(args)
                "jarvis_new_chat" -> newChat(args)
                "jarvis_send_to_chat" -> {
                    val id = resolveId(args) ?: return error("No chat. Give an id.")
                    val m = args.optString("message").trim()
                    if (m.isEmpty()) return error("message is empty")
                    if (args.optString("reason") !in listOf("user_named_this_chat", "continues_that_chats_work")) {
                        return error("Do it yourself unless the user named that chat or the request continues its work. Pass reason if one of those is true.")
                    }
                    text(JarvisController.sendToChat(id, m))
                }
                "jarvis_stop_chat" -> {
                    val id = resolveId(args) ?: return error("No chat. Give an id.")
                    text(JarvisController.stopChat(id))
                }
                "jarvis_open_chat" -> {
                    val id = args.optString("id").ifBlank { return error("id is required") }
                    JarvisController.vm.selectConversation(id)
                    JarvisController.vm.requestViewMode("CHAT")
                    text("Opened the chat.")
                }
                "jarvis_rename_chat" -> {
                    val id = resolveId(args) ?: return error("No chat. Give an id.")
                    if (JarvisController.agentOfChat(id) != AgentKind.CLAUDE) return error("Only Claude Code chats can be renamed.")
                    JarvisController.vm.renameConversation(id, args.optString("title"))
                    text("Renamed.")
                }
                "jarvis_fork_chat" -> {
                    val id = args.optString("id").ifBlank { return error("id is required") }
                    JarvisController.vm.forkConversation(id)
                    text("Forked. The copy is now on screen.")
                }
                "jarvis_delete_chat" -> {
                    val id = args.optString("id").ifBlank { return error("id is required") }
                    if (!args.optBoolean("confirm")) return error("Ask the user first, then call again with confirm=true.")
                    if (JarvisPreferences.isJarvisChat(id) && id == JarvisPreferences.currentChatId.value) return error("That is the Jarvis chat itself. Use jarvis_new_session instead.")
                    JarvisTasks.close(id)
                    JarvisController.vm.deleteConversation(id)
                    text("Deleted.")
                }
                "jarvis_list_models" -> listModels(args)
                "jarvis_set_model" -> setModel(args)
                "jarvis_set_effort" -> {
                    val b = claudeOnScreen() ?: return error("The chat on screen is not a Claude Code chat.")
                    val level = args.optString("level").lowercase()
                    b.setEffort(if (level == "default" || level.isBlank()) null else level)
                    text("Effort set to ${level.ifBlank { "default" }}.")
                }
                "jarvis_set_thinking" -> {
                    val b = claudeOnScreen() ?: return error("The chat on screen is not a Claude Code chat.")
                    b.setThinking(args.optBoolean("enabled"))
                    text("Thinking is now ${if (args.optBoolean("enabled")) "on" else "off"}.")
                }
                "jarvis_set_permission_mode" -> {
                    val b = claudeOnScreen() ?: return error("The chat on screen is not a Claude Code chat.")
                    val mode = args.optString("mode")
                    if (mode !in listOf("default", "acceptEdits", "plan", "auto", "dontAsk")) return error("Unknown mode $mode")
                    b.setPermissionMode(mode)
                    text("Permission mode is now $mode.")
                }
                "jarvis_get_setting" -> getSetting(args)
                "jarvis_set_setting" -> setSetting(args)
                "jarvis_list_permissions" -> listPermissions()
                "jarvis_resolve_permission" -> text(
                    JarvisController.resolve(
                        args.optString("key").ifBlank { null }, args.optBoolean("approve"), args.optBoolean("all"), args.optString("reason").ifBlank { null }
                    )
                )
                "jarvis_open_screen" -> {
                    val s = args.optString("screen").lowercase()
                    if (s !in listOf("chat", "ide", "terminal", "browser", "notes")) return error("Unknown screen $s")
                    JarvisController.vm.requestViewMode(s.uppercase())
                    text("Opened $s.")
                }
                "jarvis_ide_list_projects" -> ideListProjects(args)
                "jarvis_ide_open_project" -> ideOpenProject(args)
                "jarvis_ide_open_file" -> ideOpenFile(args)
                "jarvis_ide_close_file" -> ideCloseFile(args)
                "jarvis_ide_get_open_files" -> json(openFilesJson())
                "jarvis_new_session" -> {
                    JarvisController.newSession(args.optBoolean("carry_over", true))
                    text("The next message starts a fresh Jarvis chat.")
                }
                "jarvis_memory_read" -> text(JarvisPreferences.memory.value.ifBlank { "(empty)" })
                "jarvis_memory_write" -> {
                    val t = args.optString("text").trim()
                    val next = if (args.optString("mode") == "append" && JarvisPreferences.memory.value.isNotBlank()) JarvisPreferences.memory.value + "\n" + t else t
                    JarvisPreferences.memory.set(next.take(4000))
                    text("Saved.")
                }
                else -> error("Unknown tool $name")
            }
        } catch (e: Exception) {
            error("$name failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------- helpers

    private val vm get() = JarvisController.vm

    /** The card line for a tool, so the user sees progress even when the model forgets jarvis_status. */
    private fun friendlyStatus(name: String, args: JSONObject): String? = when (name) {
        "jarvis_speak", "jarvis_ask", "jarvis_status", "jarvis_stop_all", "jarvis_list_timers", "jarvis_ignore", "jarvis_help" -> null
        "jarvis_get_state" -> "Checking what's on screen"
        "jarvis_get_usage" -> "Checking your usage"
        "jarvis_task_start" -> "Starting ${args.optString("title").take(30).ifBlank { "a task" }}"
        "jarvis_task_list", "jarvis_task_get" -> "Checking your tasks"
        "jarvis_task_reply" -> "Updating the task"
        "jarvis_task_answer" -> "Answering the task"
        "jarvis_task_stop" -> "Stopping the task"
        "jarvis_task_open" -> "Opening the task"
        "jarvis_task_close" -> "Closing the task"
        "jarvis_app_guide" -> "Looking it up"
        "jarvis_get_config" -> "Reading your setup"
        "jarvis_open_settings" -> "Opening settings"
        "jarvis_list_chats", "jarvis_search_chats" -> "Looking through your chats"
        "jarvis_get_chat", "jarvis_read_chat" -> "Reading a chat"
        "jarvis_new_chat" -> "Starting a new chat"
        "jarvis_send_to_chat" -> "Sending your message"
        "jarvis_stop_chat" -> "Stopping the chat"
        "jarvis_open_chat" -> "Opening the chat"
        "jarvis_rename_chat", "jarvis_fork_chat", "jarvis_delete_chat" -> "Updating the chat"
        "jarvis_list_models" -> "Checking models"
        "jarvis_set_model" -> "Switching model"
        "jarvis_set_effort", "jarvis_set_thinking", "jarvis_set_permission_mode" -> "Changing chat settings"
        "jarvis_get_setting", "jarvis_set_setting" -> "Updating settings"
        "jarvis_list_permissions" -> "Checking approvals"
        "jarvis_resolve_permission" -> "Handling an approval"
        "jarvis_open_screen" -> "Opening ${args.optString("screen")}"
        "jarvis_ide_list_projects" -> "Looking at your projects"
        "jarvis_ide_open_project" -> "Opening ${args.optString("project").take(30)}"
        "jarvis_ide_open_file" -> "Opening ${args.optString("file").substringAfterLast('/').take(30)}"
        "jarvis_ide_close_file", "jarvis_ide_get_open_files" -> "Checking the editor"
        "jarvis_ide_search_code" -> "Searching the code"
        "jarvis_open_url" -> "Opening the page"
        "jarvis_set_timer", "jarvis_cancel_timer" -> "Setting a timer"
        "jarvis_get_health" -> "Checking the system"
        "jarvis_new_session" -> "Starting fresh"
        "jarvis_memory_read", "jarvis_memory_write" -> "Updating memory"
        else -> null
    }

    private fun health(): JSONObject {
        val sys = vm.agyBackend.systemConnectionState.value
        val claude = vm.claudeAccount.status.value
        return json(JSONObject().apply {
            put("ide_bridge", if (sys.isBridgeOnline) "online" else "offline")
            put("antigravity_hub", if (sys.isHubOnline) "online" else "offline")
            put("file_server", TermuxDaemonManager.status.value.name.lowercase())
            put("claude_code", JSONObject().apply {
                if (claude == null) put("state", "unknown")
                else {
                    put("installed", claude.installed)
                    claude.version?.let { put("version", it) }
                    (claude.error ?: claude.auth_error)?.let { put("problem", it) }
                }
                put("status", vm.claudeAccount.state.value.label)
            })
            put("jarvis", JSONObject().put("agent", JarvisPreferences.agent.value.lowercase())
                .put("voice_engine", JarvisPreferences.ttsEngine.value).put("mic_allowed", JarvisController.hasMic())
                .put("quiet_mode", JarvisPreferences.quietMode.value))
            JarvisVoice.lastError.value?.let { put("voice_problem", it) }
        })
    }

    private suspend fun ideSearchCode(args: JSONObject): JSONObject {
        val q = args.optString("query").trim()
        if (q.isEmpty()) return error("query is empty")
        val dirArg = args.optString("project").trim()
        val dir = if (dirArg.isEmpty()) TermuxDaemonManager.activeProject.value?.path
        else (findProject(dirArg) ?: return error("No project matches \"$dirArg\".")).path
        if (dir == null) return error("No project is open. Use jarvis_ide_open_project first.")
        val hits = IdeApiClient.searchCode(q, dir)
        return json(pagedJson("matches", hits.map {
            JSONObject().put("file", it.path.removePrefix(dir).trimStart('/')).put("line", it.lineNumber).put("text", it.lineText.trim().take(160))
        }, args))
    }

    private fun json(o: JSONObject) = text(o.toString(2))
    private fun text(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    private fun error(message: String) = text(message).put("isError", true)

    private fun ago(ms: Long): String {
        if (ms <= 0) return "unknown"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} days ago"
        }
    }

    private fun inText(ms: Long): String {
        val m = (ms - System.currentTimeMillis()) / 60000
        return when {
            m <= 0 -> "now"
            m < 60 -> "$m min"
            m < 24 * 60 -> "${m / 60} h ${m % 60} min"
            else -> "${m / (24 * 60)} days ${(m % (24 * 60)) / 60} h"
        }
    }

    private fun parseIso(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))?.time
        }.getOrNull()
    }

    private fun resolveId(args: JSONObject): String? =
        args.optString("id").trim().ifBlank { null }?.let { JarvisTasks.find(it)?.id ?: it } ?: vm.currentConversation.value?.id

    private fun claudeOnScreen() =
        if (vm.currentConversation.value?.agent == AgentKind.CLAUDE) vm.claudeBackend else null

    private fun convJson(c: Conversation) = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("agent", c.agent.name.lowercase())
        put("running", c.isRunning)
        put("project", c.workspaceUri.removePrefix("file://").trimEnd('/').substringAfterLast('/'))
        if (c.modelId.isNotBlank()) put("model", c.modelId)
        put("updated", ago(c.updatedAt))
        if (JarvisPreferences.isJarvisChat(c.id)) put("jarvis_chat", true)
        if (JarvisTasks.isTask(c.id)) put("task", true)
        if (vm.currentConversation.value?.id == c.id) put("on_screen", true)
    }

    private fun pageArgs(args: JSONObject): Triple<Int, Int, Int> {
        val l = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val off = if (args.has("page")) (args.optInt("page", 1).coerceAtLeast(1) - 1) * l else args.optInt("offset", 0).coerceAtLeast(0)
        return Triple(l, off, off / l + 1)
    }

    private fun pagedJson(key: String, items: List<JSONObject>, args: JSONObject, extra: JSONObject.() -> Unit = {}): JSONObject {
        val (l, off, page) = pageArgs(args)
        val slice = items.drop(off).take(l)
        val more = off + slice.size < items.size
        return JSONObject().apply {
            put("total", items.size); put("offset", off); put("limit", l); put("page", page)
            put("pages", if (items.isEmpty()) 0 else (items.size + l - 1) / l)
            put("has_more", more)
            if (more) put("next_offset", off + slice.size)
            put(key, JSONArray().apply { slice.forEach { put(it) } })
            extra()
        }
    }

    // ---------------- tasks

    private fun agentArg(raw: String): AgentKind = when (raw.lowercase()) {
        "claude" -> AgentKind.CLAUDE
        "agy", "antigravity" -> AgentKind.AGY
        else -> vm.newChatAgent.value.takeIf { it in vm.enabledAgents.value } ?: vm.enabledAgents.value.firstOrNull() ?: AgentKind.CLAUDE
    }

    private suspend fun taskStart(args: JSONObject): JSONObject {
        val goal = args.optString("goal").trim()
        if (goal.isEmpty()) return error("goal is required")
        val agent = agentArg(args.optString("agent"))
        if (agent !in vm.enabledAgents.value) return error("${if (agent == AgentKind.CLAUDE) "Claude Code" else "Antigravity"} is turned off (Settings → Agents).")
        val projectArg = args.optString("project").trim()
        val project = if (projectArg.isBlank()) null else findProject(projectArg) ?: return error("No project matches \"$projectArg\". Use jarvis_ide_list_projects.")
        val model = args.optString("model").trim().takeIf { it.isNotEmpty() }?.let { findModel(it, modelsFor(agent))?.id ?: return error("No ${agent.name.lowercase()} model matches \"$it\".") }
        val t = JarvisTasks.start(agent, goal, args.optString("title").ifBlank { null }, model, project?.path)
        if (args.optBoolean("open")) {
            delay(2500)
            vm.selectConversation(t.id)
            vm.requestViewMode("CHAT")
        }
        return json(JSONObject().put("task_id", t.id).put("title", t.title).put("agent", agent.name.lowercase()).put("status", "started")
            .put("note", "The phone will tell you when it finishes or needs something. Tell the user it started, then stop."))
    }

    private fun taskJson(t: JarvisTasks.Task) = JSONObject().apply {
        put("id", t.id); put("title", t.title); put("agent", t.agent.name.lowercase()); put("state", t.state.label)
        put("started", ago(t.createdAt)); put("last_change", ago(t.updatedAt))
        if (t.note.isNotBlank()) put("note", t.note)
        if (t.project.isNotBlank()) put("project", t.project.substringAfterLast('/'))
        if (t.followUps > 0) put("follow_ups", t.followUps)
        if (vm.currentConversation.value?.id == t.id) put("on_screen", true)
    }

    private fun taskList(args: JSONObject): JSONObject {
        val all = JarvisTasks.tasks.value
        val list = if (args.optBoolean("include_finished", true)) all else all.filter { it.active }
        return json(pagedJson("tasks", list.map { taskJson(it) }, args) { put("active", all.count { it.active }) })
    }

    private suspend fun taskGet(args: JSONObject): JSONObject {
        val t = JarvisTasks.find(args.optString("id").trim()) ?: return error("No task with that id. Use jarvis_task_list.")
        val n = args.optInt("messages", 3).coerceIn(0, 10)
        val o = taskJson(t).put("goal", t.goal)
        if (t.result.isNotBlank()) o.put("result", t.result)
        val pending = JarvisController.allPending().filter { it.chatId == t.id }
        if (pending.isNotEmpty()) o.put("waiting_approvals", JSONArray().apply { pending.forEach { put(JSONObject().put("key", it.key).put("what", it.summary).put("risk", it.risk.name.lowercase())) } })
        JarvisTasks.pendingQuestion(t.id)?.second?.questionnaire?.questions?.firstOrNull()?.let { q ->
            o.put("question", JSONObject().put("text", q.prompt).put("options", JSONArray(q.options.filter { !it.isCustomInput }.map { it.label })))
        }
        if (n > 0) {
            val msgs = (JarvisTasks.backendOf(t.id)?.messages?.value?.takeIf { it.isNotEmpty() } ?: runCatching { JarvisController.readChat(t.id) }.getOrDefault(emptyList()))
                .filter { it.content.isNotBlank() || it.toolCalls.isNotEmpty() }.takeLast(n)
            o.put("latest", JSONArray().apply {
                msgs.forEach { m ->
                    put(JSONObject().put("role", roleName(m)).put("text", cleanText(m.content, 500)).apply {
                        m.toolCalls.lastOrNull()?.let { tc -> put("last_tool", "${tc.name} ${tc.command.take(80)} (${tc.status.lowercase()})".trim()) }
                    })
                }
            })
        }
        return json(o)
    }

    // ---------------- state, config and help

    private fun getState(): JSONObject {
        val conv = vm.currentConversation.value
        val tabs = TermuxDaemonManager.openTabs.value
        val active = TermuxDaemonManager.activeProject.value
        val pending = JarvisController.allPending()
        return json(JSONObject().apply {
            put("screen", JarvisController.screen.value)
            put("ide", JSONObject().apply {
                put("project", active?.let { JSONObject().put("name", it.name).put("path", it.path) } ?: JSONObject.NULL)
                put("active_file", TermuxDaemonManager.activeTabPath.value ?: JSONObject.NULL)
                put("open_files", JSONArray().apply { tabs.forEach { put(it.name + if (it.isModified) " (unsaved)" else "") } })
            })
            put("chat_on_screen", conv?.let {
                convJson(it).put("busy", vm.isStreaming.value)
                    .put("model", (if (it.agent == AgentKind.CLAUDE) vm.claudeBackend.selectedModelId.value else vm.agyBackend.selectedModelId.value))
            } ?: JSONObject.NULL)
            put("tasks", JSONArray().apply { JarvisTasks.tasks.value.take(8).forEach { put(taskJson(it)) } })
            put("running_chats", JSONArray().apply {
                vm.allConversations.value.filter { it.isRunning && !JarvisPreferences.isJarvisChat(it.id) }.take(10).forEach { put(convJson(it)) }
            })
            put("pending_permissions", JSONObject().apply {
                put("count", pending.size)
                put("items", JSONArray().apply {
                    pending.take(5).forEach { put(JSONObject().put("key", it.key).put("what", it.summary).put("risk", it.risk.name.lowercase()).put("from", it.source)) }
                })
            })
            put("jarvis", JSONObject().apply {
                put("agent", JarvisPreferences.agent.value.lowercase())
                put("phase", JarvisController.phase.value.name.lowercase())
                put("hands_free", JarvisPreferences.handsFree.value)
                put("quiet_mode", JarvisPreferences.quietMode.value)
                put("auto_send", JarvisPreferences.autoSend.value)
                put("permission_policy", JarvisPreferences.permissionPolicy.value)
                put("chat_messages", JarvisPreferences.currentChatMessages.value)
            })
        })
    }

    private fun appGuide(args: JSONObject): JSONObject {
        val topic = args.optString("topic").trim()
        val hits = JarvisAppGuide.lookup(topic.ifBlank { "overview" })
        if (hits.isEmpty()) return text("Nothing specific about \"$topic\". Topics: ${JarvisAppGuide.topics().joinToString()}. Explain from what you know, and say where in Settings it most likely is.")
        return text(hits.joinToString("\n\n") { "## ${it.topic}\n${it.text}" } + "\n\nExplain it in one or two spoken sentences and offer to open the page.")
    }

    private suspend fun getConfig(args: JSONObject): JSONObject {
        val area = args.optString("area", "overview").lowercase().ifBlank { "overview" }
        val out = JSONObject()
        fun agents() = JSONObject().apply {
            put("enabled", JSONArray(vm.enabledAgents.value.map { it.name.lowercase() }))
            put("new_chat_agent", vm.newChatAgent.value.name.lowercase())
        }
        fun claude() = JSONObject().apply {
            val st = vm.claudeAccount.status.value
            put("status", vm.claudeAccount.state.value.label)
            if (st != null) { put("installed", st.installed); st.version?.let { put("version", it) } }
            put("models", JSONArray(vm.claudeBackend.availableModels.value.take(12).map { it.displayName.ifBlank { it.id } }))
            put("default_permission_mode", vm.claudePrefs.cachedDefaultMode())
            vm.claudeAccount.usage.value?.subscription_type?.let { put("plan", it) }
        }
        suspend fun agy(): JSONObject = JSONObject().apply {
            val a = vm.agyBackend.agyAuthInfo.value
            put("signed_in", a.isLoggedIn)
            if (a.email.isNotBlank()) put("account", a.email)
            if (a.userTier.isNotBlank()) put("tier", a.userTier)
            put("hub", if (vm.systemConnectionState.value.isHubOnline) "online" else "offline")
            put("selected_model", vm.agyBackend.selectedModelId.value)
            runCatching { put("command_auto_run", vm.commandAutoExecutionPolicy.first().removePrefix("CASCADE_COMMANDS_AUTO_EXECUTION_").lowercase()) }
            runCatching { put("command_sandbox", vm.commandSandboxEnabled.first()) }
        }
        when (area) {
            "agents" -> out.put("agents", agents())
            "claude" -> out.put("claude_code", claude())
            "antigravity", "agy" -> out.put("antigravity", agy())
            "mcp" -> {
                runCatching { vm.claudeConfig.loadMcp(null) }
                runCatching { vm.loadMcpServers() }
                delay(2500)
                if (AgentKind.CLAUDE in vm.enabledAgents.value) out.put("claude_code_mcp", JSONArray().apply {
                    vm.claudeConfig.mcpServers.value.forEach { s -> put(JSONObject().put("name", s.name).put("status", s.status).apply { s.error?.let { put("error", it.take(120)) } }) }
                })
                if (AgentKind.AGY in vm.enabledAgents.value) out.put("antigravity_mcp", JSONArray().apply {
                    vm.mcpServers.value.forEach { s -> put(JSONObject().put("name", s.name).put("status", s.status.removePrefix("MCP_SERVER_STATUS_").lowercase()).put("enabled", s.isEnabled).put("tools", s.tools.size)) }
                })
                out.put("where", "Claude Code: Settings → Claude Code → MCP. Antigravity: Settings → MCP Servers.")
            }
            "jarvis" -> out.put("jarvis", JSONObject().apply { settings.filter { it.own || it.readOnly }.forEach { put(it.key, it.get() ?: JSONObject.NULL) } })
            "appearance" -> out.put("appearance", JSONObject().apply { settings.filter { !it.own && !it.readOnly }.forEach { put(it.key, it.get() ?: JSONObject.NULL) } })
            "projects" -> out.put("projects", JSONObject().apply {
                put("active", TermuxDaemonManager.activeProject.value?.name ?: JSONObject.NULL)
                put("list", JSONArray(projects().take(15).map { it.name }))
            })
            else -> {
                out.put("agents", agents())
                if (AgentKind.CLAUDE in vm.enabledAgents.value) out.put("claude_code", claude())
                if (AgentKind.AGY in vm.enabledAgents.value) out.put("antigravity", agy())
                out.put("jarvis", JSONObject().put("agent", JarvisPreferences.agent.value.lowercase()).put("model", JarvisPreferences.modelId.value.ifBlank { "default" })
                    .put("permission_policy", JarvisPreferences.permissionPolicy.value).put("auto_send", JarvisPreferences.autoSend.value)
                    .put("trigger", if (JarvisPreferences.hotword.value == "phrase") "wake phrase \"${JarvisPreferences.wakePhrase.value}\"" else "button")
                    .put("hands_free", JarvisPreferences.handsFree.value).put("app_settings_allowed", JarvisPreferences.allowSettingChanges.value))
                out.put("theme", vm.themeMode.value)
            }
        }
        return json(out)
    }

    private fun openSettings(args: JSONObject): JSONObject {
        val want = args.optString("section").trim().lowercase().replace(' ', '_')
        val all = com.example.gemini.ui.settings.SettingsSection.values()
            .filter { it != com.example.gemini.ui.settings.SettingsSection.CLAUDE_PAGE }
        val s = all.firstOrNull { it.name.lowercase() == want }
            ?: all.firstOrNull { it.title.lowercase().contains(want.replace('_', ' ')) }
            ?: all.firstOrNull { it.name.lowercase().contains(want) || want.contains(it.name.lowercase()) }
            ?: when (want) {
                "theme", "dark_mode", "font" -> com.example.gemini.ui.settings.SettingsSection.APPEARANCE
                "voice", "hands_free" -> com.example.gemini.ui.settings.SettingsSection.JARVIS
                "plugins" -> com.example.gemini.ui.settings.SettingsSection.WEBVIEW_EXTENSIONS
                "permissions", "approvals" -> com.example.gemini.ui.settings.SettingsSection.COMMANDS
                "server", "remote" -> com.example.gemini.ui.settings.SettingsSection.SERVERS
                else -> null
            }
            ?: return error("No settings page \"$want\". Pages: ${all.filter { it != com.example.gemini.ui.settings.SettingsSection.MAIN }.joinToString { it.name.lowercase() }}")
        JarvisController.requestSettings(s.name)
        return text("Opened Settings → ${s.title}.")
    }

    private fun windowJson(usedFraction: Double, resetMs: Long?, label: String): JSONObject = JSONObject().apply {
        val used = (usedFraction * 100).coerceIn(0.0, 100.0)
        put("window", label)
        put("percent_used", Math.round(used).toInt())
        put("percent_left", Math.round(100 - used).toInt())
        if (resetMs != null) { put("resets_in", inText(resetMs)); put("resets_at_epoch_ms", resetMs) }
    }

    private suspend fun getUsage(args: JSONObject): JSONObject {
        val which = args.optString("agent", "all").lowercase()
        val refresh = args.optBoolean("refresh", true)
        val out = JSONObject()
        if (which == "all" || which == "claude") {
            if (refresh) {
                val before = vm.claudeAccount.usageUpdatedAt.value
                vm.claudeAccount.refreshUsage(force = true)
                withTimeoutOrNull(5000) { while (vm.claudeAccount.usageUpdatedAt.value == before && vm.claudeAccount.usageError.value == null) delay(200) }
            }
            val usage = vm.claudeAccount.usage.value
            val live = vm.claudeBackend.rateLimits.value
            fun win(key: String, label: String): JSONObject? {
                live[key]?.let { return windowJson(it.utilization, it.resetsAt?.times(1000), label) }
                val c = if (key == "five_hour") usage?.rate_limits?.five_hour else usage?.rate_limits?.seven_day
                return c?.let { windowJson((it.utilization ?: 0.0) / 100.0, parseIso(it.resets_at), label) }
            }
            out.put("claude", JSONObject().apply {
                usage?.subscription_type?.let { put("plan", it) }
                put("five_hour", win("five_hour", "5-hour") ?: JSONObject.NULL)
                put("seven_day", win("seven_day", "7-day") ?: JSONObject.NULL)
                vm.claudeAccount.usageError.value?.let { put("note", it) }
                if (vm.currentConversation.value?.agent == AgentKind.CLAUDE) {
                    vm.claudeBackend.contextUsage.value?.let { put("context_percent_used", Math.round(it.percentage).toInt()) }
                    put("session_cost_usd", vm.claudeBackend.sessionCost.value)
                }
            })
        }
        if (which == "all" || which == "agy") {
            if (refresh) {
                vm.agyBackend.refreshQuotas(force = true, showToastFeedback = false)
                delay(1500)
            }
            val groups = vm.agyBackend.quotaSummary.value?.groups.orEmpty()
            out.put("antigravity", JSONObject().apply {
                put("groups", JSONArray().apply {
                    groups.forEach { g ->
                        put(JSONObject().apply {
                            put("name", g.groupName.ifBlank { g.groupId })
                            g.fiveHour?.let { put("five_hour", JSONObject().put("percent_left", it.remainingPct).put("percent_used", it.usedPct).put("resets_in", it.countdown)) }
                            g.weekly?.let { put("seven_day", JSONObject().put("percent_left", it.remainingPct).put("percent_used", it.usedPct).put("resets_in", it.countdown)) }
                        })
                    }
                })
                if (groups.isEmpty()) put("note", "No quota information yet. Is Antigravity signed in and running?")
            })
        }
        return json(out)
    }

    // ---------------- chats

    private fun listChats(args: JSONObject): JSONObject {
        val agent = args.optString("agent", "all").lowercase()
        val running = args.has("running") && args.optBoolean("running")
        val project = args.optString("project").lowercase()
        val query = args.optString("query").lowercase()
        val includeJarvis = args.optBoolean("include_jarvis")
        var list = vm.allConversations.value.filter { c ->
            (includeJarvis || !JarvisPreferences.isJarvisChat(c.id)) &&
                (agent == "all" || c.agent.name.lowercase() == agent) &&
                (!running || c.isRunning) &&
                (project.isEmpty() || c.workspaceUri.lowercase().contains(project)) &&
                (query.isEmpty() || c.title.lowercase().contains(query))
        }
        list = when (args.optString("sort", "updated_desc")) {
            "updated_asc" -> list.sortedBy { it.updatedAt }
            "created_desc" -> list.sortedByDescending { it.createdAt }
            "title_asc" -> list.sortedBy { it.title.lowercase() }
            else -> list.sortedByDescending { it.updatedAt }
        }
        return json(pagedJson("chats", list.map { convJson(it) }, args))
    }

    private fun roleName(m: ChatMessage) = when (m.role) { MessageRole.USER -> "user"; MessageRole.ASSISTANT -> "assistant"; else -> m.role.name.lowercase() }

    private fun cleanText(s: String, max: Int): String {
        val collapsed = com.example.gemini.data.remote.PromptExpansion.collapse(s).trim()
        return if (collapsed.length > max) collapsed.take(max) + "…" else collapsed
    }

    private suspend fun getChat(args: JSONObject): JSONObject {
        val id = resolveId(args) ?: return error("No chat on screen. Give an id.")
        val c = vm.allConversations.value.find { it.id == id } ?: vm.currentConversation.value?.takeIf { it.id == id }
            ?: return error("No chat with that id.")
        val msgs = if (vm.currentConversation.value?.id == id) vm.messages.value else JarvisController.readChat(id)
        val pending = JarvisController.allPending().filter { it.chatId == id }
        return json(convJson(c).apply {
            put("message_count", msgs.size)
            put("waiting_permissions", JSONArray().apply { pending.forEach { put(JSONObject().put("key", it.key).put("what", it.summary).put("risk", it.risk.name.lowercase())) } })
            put("last_messages", JSONArray().apply {
                msgs.takeLast(3).forEach { put(JSONObject().put("role", roleName(it)).put("text", cleanText(it.content, 300))) }
            })
        })
    }

    private suspend fun readChat(args: JSONObject): JSONObject {
        val id = resolveId(args) ?: return error("No chat on screen. Give an id.")
        var msgs = if (vm.currentConversation.value?.id == id) vm.messages.value else JarvisController.readChat(id)
        val role = args.optString("role", "all")
        if (role != "all") msgs = msgs.filter { roleName(it) == role }
        msgs = msgs.filter { it.content.isNotBlank() || it.toolCalls.isNotEmpty() }
        val fromEnd = args.optBoolean("from_end", true)
        val ordered = if (fromEnd) msgs.reversed() else msgs
        val max = args.optInt("max_chars", 800).coerceIn(100, 6000)
        val tools = args.optBoolean("include_tools")
        val items = ordered.map { m ->
            JSONObject().apply {
                put("role", roleName(m))
                put("text", cleanText(m.content, max))
                if (tools && m.toolCalls.isNotEmpty()) put("tools", JSONArray().apply {
                    m.toolCalls.take(12).forEach { t -> put(JSONObject().put("name", t.name).put("command", t.command.take(160)).put("status", t.status)) }
                })
                put("time", ago(m.createdAt))
            }
        }
        return json(pagedJson("messages", items, args) { put("order", if (fromEnd) "newest_first" else "oldest_first"); put("chat_id", id) })
    }

    private suspend fun searchChats(args: JSONObject): JSONObject {
        val q = args.optString("query").lowercase().trim()
        if (q.isEmpty()) return error("query is empty")
        val all = vm.allConversations.value.filter { !JarvisPreferences.isJarvisChat(it.id) }.sortedByDescending { it.updatedAt }
        val hits = LinkedHashMap<String, JSONObject>()
        all.filter { it.title.lowercase().contains(q) }.forEach { hits[it.id] = convJson(it).put("matched", "title") }
        if (args.optBoolean("deep")) {
            for (c in all.take(12)) {
                if (c.id in hits) continue
                val found = runCatching { JarvisController.readChat(c.id).takeLast(60).firstOrNull { it.content.lowercase().contains(q) } }.getOrNull()
                if (found != null) hits[c.id] = convJson(c).put("matched", "content").put("snippet", cleanText(found.content, 200))
            }
        }
        return json(pagedJson("chats", hits.values.toList(), args))
    }

    private suspend fun newChat(args: JSONObject): JSONObject {
        val agent = agentArg(args.optString("agent"))
        val message = args.optString("message").trim()
        if (message.isNotEmpty()) {
            return taskStart(JSONObject(args.toString()).put("goal", message).put("agent", agent.name.lowercase()))
        }
        val projectArg = args.optString("project").trim()
        val project = if (projectArg.isBlank()) null else findProject(projectArg) ?: return error("No project matches \"$projectArg\". Use jarvis_ide_list_projects.")
        val model = args.optString("model").trim().takeIf { it.isNotEmpty() }?.let { findModel(it, modelsFor(agent))?.id ?: return error("No $agent model matches \"$it\".") }
        if (project != null) { TermuxDaemonManager.setActiveProject(project); vm.onProjectChanged(project.path) }
        vm.startNewChat(agent)
        model?.let { vm.selectModel(it) }
        vm.requestViewMode("CHAT")
        return text("Started an empty ${agent.name.lowercase()} chat on screen.")
    }

    // ---------------- models

    private fun modelsFor(agent: AgentKind): List<AiModel> =
        if (agent == AgentKind.CLAUDE) vm.claudeBackend.availableModels.value else vm.agyBackend.enabledModels.value

    private fun findModel(text: String, models: List<AiModel>): AiModel? {
        val t = text.lowercase().trim()
        models.firstOrNull { it.id.equals(text, true) || it.displayName.equals(text, true) }?.let { return it }
        val tokens = t.split(Regex("[\\s-_]+")).filter { it.isNotEmpty() }
        return models.firstOrNull { m -> val hay = (m.id + " " + m.displayName).lowercase(); tokens.all { hay.contains(it) } }
    }

    private fun listModels(args: JSONObject): JSONObject {
        val which = args.optString("agent", "").lowercase().ifBlank { vm.currentConversation.value?.agent?.name?.lowercase() ?: "all" }
        return json(JSONObject().apply {
            fun section(key: String, kind: AgentKind, selected: String) {
                put(key, JSONArray().apply {
                    modelsFor(kind).forEach { m -> put(JSONObject().put("id", m.id).put("name", m.displayName.ifBlank { m.id }).put("selected", m.id == selected)) }
                })
            }
            if (which == "all" || which == "claude") section("claude", AgentKind.CLAUDE, vm.claudeBackend.selectedModelId.value)
            if (which == "all" || which == "agy") section("antigravity", AgentKind.AGY, vm.agyBackend.selectedModelId.value)
            put("jarvis_chat_model", JarvisPreferences.modelId.value.ifBlank { "default" })
        })
    }

    private suspend fun setModel(args: JSONObject): JSONObject {
        val wanted = args.optString("model").trim()
        if (wanted.isEmpty()) return error("model is required")
        val chatId = args.optString("id").trim().let { JarvisTasks.find(it)?.id ?: it }
        if (chatId.isNotEmpty() && chatId != vm.currentConversation.value?.id) {
            val kind = JarvisController.agentOfChat(chatId)
            val m = findModel(wanted, modelsFor(kind)) ?: return error("No ${kind.name.lowercase()} model matches \"$wanted\".")
            JarvisController.setChatModel(chatId, kind, m.id)
            return text("Chat model set to ${m.displayName.ifBlank { m.id }}.")
        }
        if (args.optString("scope", "screen") == "jarvis") {
            val kind = JarvisPreferences.agentKind
            val m = findModel(wanted, modelsFor(kind)) ?: return error("No ${kind.name.lowercase()} model matches \"$wanted\".")
            JarvisPreferences.modelId.set(m.id)
            if (kind == AgentKind.CLAUDE && JarvisPreferences.currentChatId.value.isNotBlank()) JarvisController.jarvisClaude.selectModel(m.id)
            return text("The Jarvis chat now uses ${m.displayName.ifBlank { m.id }}.")
        }
        val conv = vm.currentConversation.value ?: return error("No chat on screen. Pass id to change another chat.")
        val m = findModel(wanted, modelsFor(conv.agent)) ?: return error("No ${conv.agent.name.lowercase()} model matches \"$wanted\".")
        vm.selectModel(m.id)
        return text("Model set to ${m.displayName.ifBlank { m.id }}.")
    }

    // ---------------- settings

    /** One setting Jarvis can read (and maybe change). [own] = Jarvis' own option (always changeable). */
    private class Setting(
        val key: String,
        val about: String,
        val values: String,
        val own: Boolean,
        val readOnly: Boolean = false,
        val get: suspend () -> Any?,
        val set: (suspend (String) -> String?)? = null
    )

    private fun parseBool(v: String): Boolean? = when (v.trim().lowercase()) {
        "true", "on", "yes", "1", "enable", "enabled" -> true
        "false", "off", "no", "0", "disable", "disabled" -> false
        else -> null
    }

    private fun bool(key: String, about: String, own: Boolean, get: () -> Boolean, set: (Boolean) -> Unit) =
        Setting(key, about, "true|false", own, get = { get() }, set = { v -> parseBool(v)?.let { set(it); null } ?: "Use true or false." })

    private fun choice(key: String, about: String, own: Boolean, choices: List<String>, get: () -> String, set: (String) -> Unit) =
        Setting(key, about, choices.joinToString("|"), own, get = { get() }, set = { v ->
            choices.firstOrNull { it.equals(v.trim(), true) }?.let { set(it); null } ?: "Use one of ${choices.joinToString()}."
        })

    private fun num(key: String, about: String, own: Boolean, range: ClosedFloatingPointRange<Float>, get: () -> Float, set: (Float) -> Unit) =
        Setting(key, about, "${range.start}-${range.endInclusive}", own, get = { get() }, set = { v ->
            v.trim().removeSuffix("x").toFloatOrNull()?.let { set(it.coerceIn(range)); null } ?: "Not a number."
        })

    private fun int(key: String, about: String, own: Boolean, range: IntRange, get: () -> Int, set: (Int) -> Unit) =
        Setting(key, about, "${range.first}-${range.last}", own, get = { get() }, set = { v ->
            v.trim().toFloatOrNull()?.let { set(it.toInt().coerceIn(range)); null } ?: "Not a number."
        })

    private fun textS(key: String, about: String, own: Boolean, get: () -> String, set: (String) -> Unit) =
        Setting(key, about, "text", own, get = { get() }, set = { v -> if (v.isBlank()) "The value is empty." else { set(v.trim()); null } })

    private fun ro(key: String, about: String, get: () -> Any?) = Setting(key, about, "read-only", own = true, readOnly = true, get = { get() })

    private val P = JarvisPreferences

    private val settings: List<Setting> by lazy {
        listOf(
            // ---- app (only when the user allowed it)
            choice("theme_mode", "Light, dark or follow the system", false, listOf("LIGHT", "DARK", "SYSTEM"), { vm.themeMode.value }, { vm.setThemeMode(it) }),
            Setting("theme", "Color theme by id or name (switches the app to its light or dark side). Creating themes: the theme_mcp tools.",
                com.example.gemini.theme.BuiltInThemes.all.joinToString("|") { it.id } + "|or a custom theme's name", false,
                get = { com.example.gemini.theme.ThemeManager.current.let { "${it.id} (${it.name}, ${if (it.dark) "dark" else "light"})" } },
                set = { v ->
                    com.example.gemini.theme.ThemeManager.find(v)?.let { com.example.gemini.theme.ThemeManager.select(it); null }
                        ?: "No theme \"$v\". Themes: ${com.example.gemini.theme.ThemeManager.all().joinToString { "${it.name} (${it.id})" }}"
                }),
            Setting("chat_font_scale", "Chat text size", "0.8-1.6", false, get = { vm.chatFontScale.first() }, set = { v ->
                v.trim().removeSuffix("x").toFloatOrNull()?.let { vm.setChatFontScale(it.coerceIn(0.8f, 1.6f)); null } ?: "Not a number."
            }),
            bool("group_chats_by_workspace", "Group the chat list by project", false, { vm.groupChatsByWorkspace.value }, { vm.setGroupChatsByWorkspace(it) }),
            bool("floating_switcher", "Floating app switcher over all screens", false, { vm.isFloatingSwitcherEnabled.value }, { vm.setFloatingSwitcherEnabled(it) }),
            bool("floating_bubble", "Floating chat bubble over other apps", false, { vm.isFloatingBubbleEnabled.value }, { vm.setFloatingBubbleEnabled(it) }),
            bool("vibrate_when_chat_done", "A light buzz when a chat finishes", false, { ChatCompletionMonitor.vibrate.value }, { ChatCompletionMonitor.setVibrate(it) }),
            Setting("new_chat_agent", "Agent of new chats", "claude|agy", false, get = { vm.newChatAgent.value.name.lowercase() }, set = { v ->
                val a = when (v.trim().lowercase()) { "claude" -> AgentKind.CLAUDE; "agy", "antigravity" -> AgentKind.AGY; else -> null }
                when {
                    a == null -> "Use claude or agy."
                    a !in vm.enabledAgents.value -> "That agent is turned off (Settings → Agents)."
                    else -> { vm.setNewChatAgent(a); null }
                }
            }),
            // ---- Jarvis' own options (always allowed)
            bool("jarvis_quiet_mode", "Show answers instead of speaking them", true, { P.quietMode.value }, { P.quietMode.set(it) }),
            num("jarvis_voice_speed", "Speaking speed", true, 0.5f..2f, { P.ttsRate.value }, { P.ttsRate.set(it) }),
            num("jarvis_voice_pitch", "Voice pitch", true, 0.5f..2f, { P.ttsPitch.value }, { P.ttsPitch.set(it) }),
            choice("jarvis_voice_engine", "Voice: device, Google Cloud or OpenAI-compatible (online ones need a key in settings)", true, listOf("android", "google_cloud", "openai"), { P.ttsEngine.value }, { P.ttsEngine.set(it) }),
            bool("jarvis_speak_progress", "Say short progress lines during long work", true, { P.speakProgress.value }, { P.speakProgress.set(it) }),
            bool("jarvis_auto_send", "Send by itself when the user pauses (off: only a send phrase, Send or the headset button sends)", true, { P.autoSend.value }, { P.autoSend.set(it) }),
            choice("jarvis_auto_send_style", "With auto send: smart pause (longer when unfinished) or a fixed pause", true, listOf("smart", "pause"), { if (P.sendMode.value == "pause") "pause" else "smart" }, { P.sendMode.set(it) }),
            num("jarvis_pause_seconds", "Pause that ends a sentence (auto send)", true, 0.8f..3.5f, { P.silenceMs.value / 1000f }, { P.silenceMs.set((it * 1000).toInt()) }),
            bool("jarvis_auto_send_answers", "Short answers to Jarvis' own questions send by themselves", true, { P.autoSendAnswers.value }, { P.autoSendAnswers.set(it) }),
            bool("jarvis_send_phrases_on", "Ending with a send phrase sends at once", true, { P.sendPhrasesOn.value }, { P.sendPhrasesOn.set(it) }),
            textS("jarvis_send_phrases", "Send phrases, separated by | (replaces the whole list; read it first and keep what the user did not ask to remove)", true,
                { JarvisPhrases.parse(P.sendPhrases.value).joinToString(" | ") }, { P.sendPhrases.set(JarvisPhrases.format(JarvisPhrases.parse(it))) }),
            bool("jarvis_cancel_phrases_on", "Ending with a cancel phrase throws the message away", true, { P.cancelPhrasesOn.value }, { P.cancelPhrasesOn.set(it) }),
            textS("jarvis_cancel_phrases", "Cancel phrases, separated by | (replaces the whole list; read it first and keep what the user did not ask to remove)", true,
                { JarvisPhrases.parse(P.cancelPhrases.value).joinToString(" | ") }, { P.cancelPhrases.set(JarvisPhrases.format(JarvisPhrases.parse(it))) }),
            choice("jarvis_listen_after", "Open the mic after Jarvis speaks: auto (when it expects an answer), always, or never", true, listOf("auto", "always", "never"), { P.listenAfter.value }, { P.listenAfter.set(it) }),
            int("jarvis_follow_up_seconds", "How long the mic waits for a reply after Jarvis spoke (no word at all closes it)", true, 2..30, { P.followUpSeconds.value }, { P.followUpSeconds.set(it) }),
            Setting("jarvis_persona", "Whom Jarvis speaks as (grammatical gender for itself, e.g. Hindi kar rahi / kar raha hoon)", "female|male|neutral|unset", true,
                get = { P.persona.value.ifBlank { "unset" } },
                set = { v -> when (val x = v.trim().lowercase()) { "female", "male", "neutral" -> { P.persona.set(x); null }; "unset", "none", "" -> { P.persona.set(""); null }; else -> "Use female, male, neutral or unset." } }),
            int("jarvis_max_listen_seconds", "Longest single capture", true, 15..300, { P.maxListenSeconds.value }, { P.maxListenSeconds.set(it) }),
            choice("jarvis_side_talk", "Ignore what is said to other people", true, listOf("off", "smart", "strict"), { P.sideTalk.value }, { P.sideTalk.set(it) }),
            Setting("jarvis_trigger", "How Jarvis wakes: button only or wake phrase (needs hands-free)", "button|wake_phrase", true,
                get = { if (P.hotword.value == "phrase") "wake_phrase" else "button" },
                set = { v -> when (v.trim().lowercase()) { "button", "push" -> { P.hotword.set("push"); null }; "wake_phrase", "phrase", "wake" -> { P.hotword.set("phrase"); null }; else -> "Use button or wake_phrase." } }),
            textS("jarvis_wake_phrase", "Wake phrase(s), comma separated", true, { P.wakePhrase.value }, { P.wakePhrase.set(it.lowercase()) }),
            choice("jarvis_wake_match", "Whole phrase, or the name alone wakes Jarvis", true, listOf("phrase", "name"), { P.wakeMatch.value }, { P.wakeMatch.set(it) }),
            bool("jarvis_headset_button", "Headset button talks and stops (hands-free)", true, { P.headsetButton.value }, { P.headsetButton.set(it) }),
            bool("jarvis_hands_free", "Hands-free mode (mic service, works with the screen off)", true, { P.handsFree.value }, { JarvisController.setHandsFree(it) }),
            choice("jarvis_busy_behavior", "Speaking while Jarvis works: wait, interrupt, or interrupt on corrections", true, listOf("queue", "interrupt", "smart"), { P.busyBehavior.value }, { P.busyBehavior.set(it) }),
            choice("jarvis_keep_screen_on", "Keep the screen on", true, listOf("off", "active", "always"), { P.keepScreenOn.value }, { P.keepScreenOn.set(it) }),
            choice("jarvis_idle_orb", "Orb size while idle", true, listOf("tiny", "small", "normal", "hidden"), { P.orbSize.value }, { P.orbSize.set(it) }),
            choice("jarvis_orb_style", "Floating card or full screen while active", true, listOf("floating", "fullscreen"), { P.orbStyle.value }, { P.orbStyle.set(it) }),
            bool("jarvis_show_transcript", "Show what Jarvis heard and said", true, { P.showTranscript.value }, { P.showTranscript.set(it) }),
            bool("jarvis_show_activity", "Show what Jarvis is doing", true, { P.showActivity.value }, { P.showActivity.set(it) }),
            bool("jarvis_haptics", "Vibrate when listening starts", true, { P.haptics.value }, { P.haptics.set(it) }),
            bool("jarvis_earcons", "Tones when the mic opens and closes", true, { P.earcons.value }, { P.earcons.set(it) }),
            bool("jarvis_announce_done", "Say when any chat finishes", true, { P.announceDone.value }, { P.announceDone.set(it) }),
            bool("jarvis_announce_summary", "Also read the start of its reply", true, { P.announceSummary.value }, { P.announceSummary.set(it) }),
            bool("jarvis_announce_usage_low", "Warn when the 5-hour usage is almost gone", true, { P.announceUsageLow.value }, { P.announceUsageLow.set(it) }),
            choice("jarvis_event_routing", "Task news: Jarvis decides what to do, or just announces", true, listOf("model", "speak"), { P.eventRouting.value }, { P.eventRouting.set(it) }),
            int("jarvis_stall_minutes", "Report a task with no progress after this many minutes (0 = never)", true, 0..240, { P.stallMinutes.value }, { P.stallMinutes.set(it) }),
            int("jarvis_rotate_idle_minutes", "Fresh Jarvis chat after this many idle minutes (0 = never)", true, 0..1440, { P.rotateIdleMinutes.value }, { P.rotateIdleMinutes.set(it) }),
            int("jarvis_rotate_max_messages", "Fresh Jarvis chat after this many messages (0 = never)", true, 0..500, { P.rotateMaxMessages.value }, { P.rotateMaxMessages.set(it) }),
            bool("jarvis_context_in_prompt", "Send live context with every message", true, { P.contextInPrompt.value }, { P.contextInPrompt.set(it) }),
            // ---- read only (changed by the user in Settings → Jarvis)
            ro("jarvis_agent", "Agent that runs Jarvis") { P.agent.value.lowercase() },
            ro("jarvis_model", "Model of the Jarvis chat") { P.modelId.value.ifBlank { "default" } },
            ro("jarvis_permission_policy", "Approval policy") { P.permissionPolicy.value },
            ro("jarvis_task_approvals", "Approvals inside tasks") { P.taskApprovals.value },
            ro("jarvis_allow_setting_changes", "Jarvis may change app settings") { P.allowSettingChanges.value },
            ro("jarvis_about_user", "The user's own notes for Jarvis (edited by the user in Settings → Jarvis; use jarvis_memory_write for your own notes)") { P.aboutUser.value.ifBlank { "(empty)" } }
        )
    }

    private suspend fun getSetting(args: JSONObject): JSONObject {
        val key = args.optString("key").trim().lowercase()
        if (key.isNotEmpty()) {
            val s = settings.firstOrNull { it.key == key } ?: return error("Unknown setting. Known: ${settings.joinToString { it.key }}")
            return text("${s.key} = ${s.get()} (${s.values}) — ${s.about}${if (!s.own && !JarvisPreferences.allowSettingChanges.value) "; changing it needs the user's permission" else ""}")
        }
        return json(JSONObject().apply {
            put("app_settings_changeable", JarvisPreferences.allowSettingChanges.value)
            put("settings", JSONArray().apply {
                settings.forEach { s -> put(JSONObject().put("key", s.key).put("value", s.get() ?: JSONObject.NULL).put("values", s.values).put("about", s.about)) }
            })
        })
    }

    private suspend fun setSetting(args: JSONObject): JSONObject {
        val key = args.optString("key").trim().lowercase()
        val v = args.optString("value").trim()
        val s = settings.firstOrNull { it.key == key } ?: return error("Unknown setting. Known: ${settings.filter { !it.readOnly }.joinToString { it.key }}")
        val setter = s.set
        if (s.readOnly || setter == null) return error("$key can only be changed by the user in Settings → Jarvis. Offer to open it (jarvis_open_settings section=jarvis).")
        if (!s.own && !JarvisPreferences.allowSettingChanges.value) {
            return error("The user has not allowed Jarvis to change app settings. They can allow it in Settings → Jarvis → Approvals (\"Let Jarvis change app settings\"), or change it themselves in Settings → Appearance & Theme.")
        }
        setter(v)?.let { return error("$key: $it") }
        return text("$key set to ${s.get()}.")
    }

    private fun listPermissions(): JSONObject {
        val list = JarvisController.allPending()
        return json(JSONObject().apply {
            put("count", list.size)
            put("items", JSONArray().apply {
                list.forEach {
                    put(JSONObject().put("key", it.key).put("from", it.source).put("chat_id", it.chatId)
                        .put("tool", it.tool).put("wants_to", it.summary).put("risk", it.risk.name.lowercase()))
                }
            })
        })
    }

    // ---------------- IDE

    private suspend fun projects(): List<ProjectItem> {
        val cached = TermuxDaemonManager.projects.value
        if (cached.isNotEmpty()) return cached
        return runCatching { TermuxDaemonManager.loadProjects(vm.allConversations.value) }.getOrDefault(emptyList())
    }

    private suspend fun findProject(text: String): ProjectItem? {
        val t = text.trim().trimEnd('/').lowercase()
        val all = projects()
        return all.firstOrNull { it.path.equals(text.trim().trimEnd('/'), true) }
            ?: all.firstOrNull { it.name.equals(t, true) }
            ?: all.firstOrNull { it.name.lowercase().contains(t) }
            ?: all.firstOrNull { it.path.lowercase().contains(t) }
            ?: if (text.startsWith("/") && java.io.File(text).isDirectory) ProjectItem(java.io.File(text).name, text.trimEnd('/')) else null
    }

    private suspend fun ideListProjects(args: JSONObject): JSONObject {
        val q = args.optString("query").lowercase()
        val active = TermuxDaemonManager.activeProject.value?.path
        val list = projects().filter { q.isEmpty() || it.name.lowercase().contains(q) || it.path.lowercase().contains(q) }
        return json(pagedJson("projects", list.map { JSONObject().put("name", it.name).put("path", it.path).put("active", it.path == active) }, args))
    }

    private suspend fun ideOpenProject(args: JSONObject): JSONObject {
        val wanted = args.optString("project").trim()
        if (wanted.isEmpty()) return error("project is required")
        val p = findProject(wanted) ?: return error("No project matches \"$wanted\". Use jarvis_ide_list_projects.")
        TermuxDaemonManager.setActiveProject(p)
        vm.onProjectChanged(p.path)
        if (args.optBoolean("show_ide", true)) vm.requestViewMode("IDE")
        return text("Opened project ${p.name}.")
    }

    private fun flatten(nodes: List<FileNode>, out: MutableList<FileNode>, cap: Int = 6000) {
        for (n in nodes) {
            if (out.size >= cap) return
            if (n.isDir) flatten(n.children, out, cap) else out.add(n)
        }
    }

    private suspend fun resolveFile(file: String): Pair<String, String>? {
        val project = TermuxDaemonManager.activeProject.value
        val f = file.trim()
        if (f.startsWith("/") || f.startsWith("~")) return f to f.substringAfterLast('/')
        if (project != null && f.contains('/')) {
            val p = project.path.trimEnd('/') + "/" + f.removePrefix("./")
            if (IdeApiClient.readFileDetailed(p) != null) return p to f.substringAfterLast('/')
        }
        // just a name: look through the project's files
        val root = project?.path ?: return null
        val tree = IdeApiClient.getFileTree(root)
        val files = ArrayList<FileNode>()
        flatten(tree, files)
        val lower = f.lowercase()
        val hit = files.firstOrNull { it.name.equals(f, true) }
            ?: files.firstOrNull { it.path.lowercase().endsWith("/$lower") }
            ?: files.firstOrNull { it.name.lowercase().startsWith(lower) }
            ?: files.firstOrNull { it.name.lowercase().contains(lower) }
        return hit?.let { it.path to it.name }
    }

    private suspend fun ideOpenFile(args: JSONObject): JSONObject {
        val file = args.optString("file").trim()
        if (file.isEmpty()) return error("file is required")
        args.optString("project").trim().takeIf { it.isNotEmpty() }?.let { pn ->
            val p = findProject(pn) ?: return error("No project matches \"$pn\".")
            if (TermuxDaemonManager.activeProject.value?.path != p.path) { TermuxDaemonManager.setActiveProject(p); vm.onProjectChanged(p.path) }
        }
        val (path, name) = resolveFile(file) ?: return error("Could not find \"$file\" in the active project. Open the right project first.")
        val res = IdeApiClient.readFileDetailed(path) ?: return error("Could not read $path.")
        if (res.category == "binary") return error("$name is a binary file and cannot be opened in the editor.")
        TermuxDaemonManager.openOrSelectTab(path, name, res.content)
        if (args.optBoolean("show_ide", true)) vm.requestViewMode("IDE")
        return text("Opened $name.")
    }

    private fun ideCloseFile(args: JSONObject): JSONObject {
        val tabs = TermuxDaemonManager.openTabs.value
        val want = args.optString("file").trim()
        val tab = if (want.isEmpty()) tabs.firstOrNull { it.path == TermuxDaemonManager.activeTabPath.value }
        else tabs.firstOrNull { it.path == want || it.name.equals(want, true) || it.path.endsWith("/$want") }
        tab ?: return error("No such open file.")
        if (tab.isModified && !args.optBoolean("discard")) return error("${tab.name} has unsaved changes. Ask the user, then pass discard=true.")
        TermuxDaemonManager.closeTab(tab.path)
        return text("Closed ${tab.name}.")
    }

    private fun openFilesJson() = JSONObject().apply {
        val tabs = TermuxDaemonManager.openTabs.value
        put("active", TermuxDaemonManager.activeTabPath.value ?: JSONObject.NULL)
        put("files", JSONArray().apply {
            tabs.forEach { put(JSONObject().put("name", it.name).put("path", it.path).put("unsaved", it.isModified)) }
        })
    }
}
