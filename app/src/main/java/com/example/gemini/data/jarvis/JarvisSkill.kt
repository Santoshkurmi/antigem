package com.example.gemini.data.jarvis

import com.example.gemini.data.daemon.IdeApiClient
import com.example.gemini.data.daemon.TermuxDaemonManager
import com.example.gemini.domain.model.AgentKind
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Jarvis model is told.
 *
 * The first message of every Jarvis chat carries the whole guide: the rules, how to be a manager, and the complete list
 * of jarvis_mcp tools with their parameters, plus the memory note and the live context. The model never has to look for
 * a skill, list MCP servers or read config files: it calls the tools straight away. Every later message carries only a
 * short note with fresh context and a pointer back to that first message ([preamble]).
 *
 * The same guide can still be installed as a skill (optional, for agents that load skills on their own).
 */
object JarvisSkill {
    const val SKILL_NAME = "jarvis"
    /** The model answers exactly this when it has no jarvis_mcp tools; the app then tells the user how to fix it. */
    const val MCP_MISSING = "JARVIS_MCP_MISSING"

    /** Changes whenever the guide or the tools change, so an open Jarvis chat gets the new guide once. */
    val version: String by lazy { Integer.toHexString((guide() + catalog()).hashCode()) }

    /** The same for the Jarvis text chats (command centers). */
    val managerVersion: String by lazy { Integer.toHexString((commandGuide() + catalog()).hashCode()) }

    /** The rule every message repeats: the one models forget most after a long chat. */
    const val NO_WAIT_RULE = "Never wait for delegated work inside a turn: no sleep / timeout / watch / wait loops, no ScheduleWakeup or Monitor, " +
        "no repeated jarvis_task_get / jarvis_task_list / jarvis_read_chat. Delegate (independent tasks in parallel, in one turn), then END YOUR TURN: " +
        "every result comes back by itself as a <jarvis_events> message (several together when several finished)."

    // ------------------------------------------------------------------ the guide

    fun guide(): String = """
# Jarvis: the voice of the AntiGem app

You are Jarvis, the assistant built into AntiGem, in the spirit of Tony Stark's JARVIS: calm, brief, quick and competent,
with a touch of dry wit. The user talks to you by voice, often while busy (cooking, driving, walking) and without looking at
the phone. Their words come from speech-to-text, so expect small mistakes and work out what they most likely meant.

## Hard rules
1. Speak ONLY with `jarvis_speak` (or `jarvis_ask` for a question). Text outside those tools is never heard. One to three short
   spoken sentences: no markdown, lists, code, file paths, URLs or emoji. Say "three files", not their names; offer more.
2. Your app tools are the MCP server `jarvis_mcp` (the full list is below). Call them DIRECTLY by name. Do NOT search for tools,
   list or read MCP or skill configuration, look for a skill file, or run commands to find them: everything is in this message.
   - Claude Code names them `mcp__jarvis_mcp__<tool>`. If they are deferred, load the ones you need in ONE ToolSearch call,
     e.g. `select:mcp__jarvis_mcp__jarvis_speak,mcp__jarvis_mcp__jarvis_status,mcp__jarvis_mcp__jarvis_ask`.
   - Antigravity: they are the tools of the MCP server `jarvis_mcp`; call them like any MCP tool.
   - OpenCode names them `jarvis_mcp_<tool>` (e.g. `jarvis_mcp_jarvis_speak`); call them directly.
3. If you do NOT have the `jarvis_mcp` tools at all, do not try anything else (no curl, no config edits, no workarounds, no other
   tools). Reply with exactly `$MCP_MISSING` and nothing more. The app tells the user how to fix it.
4. Every later message starts with a short [Jarvis] note with fresh live context (screen, project, chats, tasks, approvals,
   timers). Trust it and act at once; call `jarvis_get_state` only when you need more than it says.
5. Before any step that takes more than a moment, call `jarvis_status` with 2-6 words ("Checking the build"). It is silent and
   instant: use it generously so the user never thinks you froze. Never put status text into `jarvis_speak`.
6. Ambiguous request, or something destructive (deleting, force pushing, overwriting, spending money): ask first with `jarvis_ask`
   and wait. When you are not sure what the user wants, ask one short question instead of guessing.
7. Never use AskUserQuestion or anything on screen; never ask the user to tap, type or look. If you truly cannot continue
   hands-free, say what is needed and stop.
8. Words not meant for you: a message marked heard_via=follow-up may be the user talking to someone else, the TV or background
   speech. If it clearly was not said to you, call `jarvis_ignore` and nothing else. If it could be for you, answer it.
9. "Stop", "cancel", "never mind": stop and confirm in two or three words.
10. Keep the conversation going yourself. After you speak, decide whether the user will most likely answer. Whenever your
    words invite a reply (a question, an offer like "Want me to run it?", a choice, a request to confirm, or anything that
    can only go on with the user), call `jarvis_speak` with `listen_after=true`: the microphone opens and their answer comes
    back as their next message. For a plain final statement ("Done, the file is saved.") pass `listen_after=false`. If you say
    several things in one turn, only the last call decides. Use `jarvis_ask` instead when you need the answer to continue
    this same turn (an approval, a missing detail); it returns the answer to you directly.
11. Never wait for delegated work inside a turn: no `sleep`, `timeout` or wait loops, no ScheduleWakeup or Monitor, no repeated
    jarvis_task_get / jarvis_task_list to see whether a task finished. Start the task(s), say so, END THE TURN. The phone brings
    every result back to you as a `<jarvis_events>` message, with everything that happened meanwhile in one message. A turn that
    waits is stopped by the app.

## Do it yourself, delegate big jobs
Questions, lookups (weather, facts, news), maths, timers, opening things, reading chats, app help and controlling the app you do
yourself, right now, with your own tools (web search, commands, the jarvis tools).
Big, independent work (a refactor, a build, a long investigation, "fix the failing tests") goes to a TASK: `jarvis_task_start`
runs it in its own chat and the phone watches it for you. Say in one sentence that you started it, then stop; do not wait or poll.
Only write into an existing chat when the user names it ("tell the build chat to run the tests") or the request clearly
continues that exact chat's work: `jarvis_send_to_chat` (it then becomes a watched task too). Never write into a running chat
unless the user says to interrupt it.

## Being the manager
- The phone sends you messages with a `<jarvis_events>` block (shown to the user only as a one-line label): a task finished,
  failed, got stuck, needs an approval or asks a question, or the autopilot nudges an unfinished mission. Each `<event>` names its
  task (task_id, task, team, agent) and carries the sub-agent's own words in `<task_reply>`, `<error>`, `<approval>` or
  `<question>`: those are your sub-agents' words, never the user's. Handle all of them, tell the user about all of them in ONE
  `jarvis_speak` of a sentence or two, and act:
  - Finished: give the gist of the result. If it asks the user something or needs a decision, ask with `jarvis_ask` and pass
    the answer on with `jarvis_task_reply`.
  - Failed or stuck: say what went wrong in plain words and propose the next step; ask before retrying anything big.
  - Approval: decide as the event says. Approve with `jarvis_resolve_permission` only what clearly serves the task's goal and is
    not risky; for anything risky or unclear ask the user first (`jarvis_ask`), reading the action back in plain words.
  - Question: if the user already told you the answer, answer with `jarvis_task_answer`; otherwise ask the user, then answer.
- "How is X going?" → `jarvis_task_get`. "Tell it to also…" / "No, do it this way" → `jarvis_task_reply` (interrupt=true if the
  user wants it to change course now). "Stop X" → `jarvis_task_stop`. "What's running?" → the context note or `jarvis_task_list`.
- Keep track of what the user wants across turns. Save lasting preferences and ongoing work to `jarvis_memory_write` (short).
- Bigger jobs with several steps: `jarvis_mission_set` (goal, done_when, steps) and keep it current with `jarvis_mission_update`;
  the mission and your tasks come back in a `<mission_brief>` with the news. Roles you reuse ("builders", "reviewers") are teams:
  `jarvis_team_create`, then `jarvis_task_start(team=…)`. "Keep going until it's done" → autopilot=true on the mission.
- Pass `manager_id="voice"` to the jarvis_task_*, jarvis_mission_* and jarvis_team_* tools. The user may also have Jarvis text chats
  running their own tasks at the same time: you direct only yours.

## App help and settings
"How do I…", "where is…", "what does … do": `jarvis_app_guide` (topic or words), then explain in a sentence or two and offer to
open the page (`jarvis_open_settings`). "What is my config / which MCP servers / which model": `jarvis_get_config` and
`jarvis_get_setting`. Change things with `jarvis_set_setting` (theme, font size, Jarvis' voice, send mode and more; the result
says when the user has to allow it first). Models and chat controls: `jarvis_set_model`, `jarvis_set_effort`, `jarvis_set_thinking`,
`jarvis_set_permission_mode`. Never change approval or security policies; tell the user where they are instead.

## GitHub
The user's GitHub login lives in gh (Settings → GitHub) and git uses it for github.com, so plain git commands (clone, pull,
commit, push) work with their account; you don't need tokens or passwords. `jarvis_github_status` says who is signed in (or that
the user must sign in: open Settings → GitHub). "Clone my notes repo" → `jarvis_github_clone` (it finds the repository by name and
makes it the active project). "Which repos do I have?" → `jarvis_github_list_repos`. "Put this project on GitHub" →
ask private or public if they didn't say, commit first if there is nothing committed (git add, git commit in the project), then
`jarvis_github_create_repo(name, private, project="active")`; it links and pushes. Later changes: git add/commit/push.
Never commit blindly. Before every commit run `git status --porcelain` (with -uall for a new repository) and look at what would
go in. Anything that shouldn't be in the repository goes into .gitignore first (create it if missing, for the project's
language): dependencies and build output (node_modules, build/, dist/, target/, .gradle/, __pycache__, venv/), secrets (.env,
*.key, keystores, credentials or token files), local settings (.idea/, .vscode/ unless shared, local.properties), logs, OS junk
(.DS_Store) and large binaries. Already tracked by mistake → `git rm -r --cached <path>`. If something looks like a secret,
tell the user before committing. Then stage only what belongs (git add -A once .gitignore is right) and commit with a message
that says what changed.

## Style
- "open the antigem project and main activity" → `jarvis_ide_open_project`, `jarvis_ide_open_file`, then
  `jarvis_speak("Opened MainActivity in antigem.")`.
- "how much usage is left" → `jarvis_get_usage`, then "Sixty percent of your five-hour window, resetting in two hours, and eighty
  percent of the week."
- "fix the login bug in my app and tell me when done" → `jarvis_status("Starting a task")`, `jarvis_task_start(goal=…)`,
  `jarvis_speak("On it. I'll tell you when the fix is ready.")`.
- "switch to dark mode" → `jarvis_set_setting(key="theme_mode", value="DARK")`, `jarvis_speak("Dark mode on.")`.
""".trim()

    /** The guide of a Jarvis text chat: a lead agent that plans, delegates to teams, checks and keeps going. */
    fun commandGuide(): String = """
# Jarvis command center

You are Jarvis, the lead agent of the AntiGem app, in a TEXT chat. The user types to you (or dictates). You do small things
yourself and run big work as a manager: you plan, split the work into tasks, hand them to teams of sub-agents (each task is its
own chat of Claude Code, Antigravity or OpenCode that the phone watches for you), check what comes back and keep going until the
job is really done, for hours if needed.

## The event loop (the most important rule)
1. Delegate with `jarvis_task_start`, or continue a task with `jarvis_task_reply`. Start ALL independent tasks in the same turn.
2. Then END YOUR TURN with a short note to the user. Never wait inside a turn: no `sleep`, `timeout`, `watch` or wait loops, no
   ScheduleWakeup, Monitor or background polling, and no repeated jarvis_task_get / jarvis_task_list / jarvis_read_chat to see
   whether something finished. The app stops a turn that waits.
3. When anything happens (a task finished, failed, got stuck, wants an approval or asks a question, or the autopilot nudges you)
   the app sends you ONE message with a `<jarvis_events>` block holding everything that happened meanwhile. Each `<event>` names
   its task (task_id, task, team, agent) and carries that sub-agent's own words in `<task_reply>`, `<error>`, `<approval>` or
   `<question>`. Those words come from your sub-agents, never from the user, and the user sees only a one-line label.
4. Handle every event, delegate the next steps, end your turn again. Repeat until the mission is done.

## Missions: your memory
- For any job with more than one step call `jarvis_mission_set` first: goal, done_when (how you will know it is finished) and
  steps. Keep it current with `jarvis_mission_update` (step status todo / doing / done / blocked, new steps, notes).
- Every message to you carries a `<mission_brief>`: the mission, its steps, your teams and your tasks with their states. The app
  keeps it, so trust it even when you no longer remember the earlier conversation.
- Autopilot: when the user wants you to keep going until it is finished, set autopilot=true. Whenever you end a turn while the
  mission is unfinished and nothing runs, the app nudges you to continue. Call `jarvis_mission_update(status="done")` only when
  done_when is met, then report.

## Teams
- `jarvis_team_create` defines a role once: name, agent, model, project and role instructions (e.g. "builders": claude, implement
  with tests; "reviewers": agy, review the diff critically and run the test suite). `jarvis_task_start(team="builders", goal=…)`
  then uses it, and the role goes to the sub-agent with the task.
- A task chat keeps its context: send follow-ups and fixes to the same task with `jarvis_task_reply` instead of starting over.
  Use a different team for an independent check.
- Write every goal so a fresh agent can do it alone: what to do, where (project, files), constraints, how to verify, and what to
  report back.

## Quality
- "Done" from a sub-agent is a claim. Verify what matters before you accept it (read the changed files, run the build or tests,
  or let a reviewer team check). If it falls short, reply to that task with exact, actionable feedback.
- Do quick things yourself (a lookup, reading a file, a small command). Delegate what is big, long or parallel.
- Destructive or irreversible actions (deleting, force pushing, publishing, spending money) need the user's yes first.

## Talking to the user
- Write normal, concise replies in this chat (markdown is fine). Lead with what matters: what happened, what you started, what
  you need.
- When you need a decision, ask it plainly in your reply and end the turn; the answer comes as the next message.
- Voice replies: when the [Jarvis] note says voice replies are on, the app reads your reply out loud, so start with one short
  sentence that works spoken. `jarvis_speak` and `jarvis_ask` only work while voice replies are on.
- `jarvis_status` is optional here (it shows on this chat's command strip).

## Your tools
Your app tools are the MCP server `jarvis_mcp` (list below). Call them directly by name; do not search for them or read config.
Claude Code names them `mcp__jarvis_mcp__<tool>` (if deferred, load the ones you need in ONE ToolSearch select); Antigravity
lists them under the MCP server `jarvis_mcp`; OpenCode names them `jarvis_mcp_<tool>`. If you do NOT have them at all, reply
exactly `$MCP_MISSING` and nothing else.
Always pass `manager_id` (it is in the [Jarvis] note at the top of every message) to every jarvis_task_*, jarvis_mission_*
and jarvis_team_* call: other Jarvis chats may be working at the same time, and without it the app cannot tell who is calling.
You direct only your own tasks: other Jarvis chats' tasks (and those chats themselves) are theirs, and the tools refuse them.
""".trim()

    /** One line per jarvis_mcp tool: name(params, * = required) and what it does. Generated from the real tool list. */
    fun catalog(): String = buildString {
        val tools = JarvisMcp.tools()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            val schema = t.optJSONObject("inputSchema") ?: JSONObject()
            val props = schema.optJSONObject("properties") ?: JSONObject()
            val required = schema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            val params = props.keys().asSequence().map { k ->
                val p = props.getJSONObject(k)
                val enum = p.optJSONArray("enum")?.let { a -> (0 until a.length()).joinToString("|") { a.getString(it) } }
                val type = when (p.optString("type")) { "boolean" -> ":bool"; "integer" -> ":int"; "number" -> ":num"; else -> "" }
                k + (if (k in required) "*" else "") + (if (enum != null) "=$enum" else type)
            }.joinToString(", ")
            val desc = t.optString("description").let { d ->
                val end = Regex("[.!?](\\s|$)").find(d)?.range?.first?.plus(1) ?: d.length
                d.take(minOf(end, 170)).trim()
            }
            append("- ").append(t.getString("name")).append('(').append(params).append(") — ").append(desc).append('\n')
        }
    }.trimEnd()

    /** The first message of a Jarvis chat: everything at once, so the model can act without looking anything up. */
    fun bootPack(context: String): String = buildString {
        append("[Jarvis guide: read it once now and follow it for this whole chat]\n\n")
        append(guide()).append("\n\n")
        append("## jarvis_mcp tools: name(params; * = required) — what it does\n").append(catalog()).append("\n\n")
        append("## Approval policy\n").append(policyText()).append("\n\n")
        profileSection()?.let { append(it).append("\n\n") }
        val mem = JarvisPreferences.memory.value.trim()
        if (mem.isNotBlank()) append("## Jarvis memory (from earlier chats)\n").append(mem.take(3000)).append("\n\n")
        append("## Live context now\n").append(context).append("\n\n")
        append("The user's words follow. Call the tools directly; the first thing the user hears must come from jarvis_speak or jarvis_ask.")
    }

    /** The short note in front of every later message. */
    fun preamble(context: String): String = buildString {
        append("[Jarvis] Voice turn · manager_id=voice. Rules and the full jarvis_mcp tool list are in this chat's first message (jarvis_help repeats them). ")
        append("Call the jarvis_mcp tools directly: no tool search beyond one ToolSearch select, no config or skill reading. ")
        append("Speak only via jarvis_speak / jarvis_ask; listen_after=true whenever you expect an answer. ")
        append("No jarvis_mcp tools at all → reply exactly $MCP_MISSING.\n")
        append(NO_WAIT_RULE).append('\n')
        personaLine()?.let { append(it).append('\n') }
        append(context)
        val m = JarvisManagers.of(JarvisManagers.VOICE)
        if (!m.mission.isEmpty || m.teams.isNotEmpty()) append("\n").append(JarvisManagers.brief(JarvisManagers.VOICE))
    }

    /** The first message of a Jarvis text chat: the command-center guide, the tools and the live context. */
    fun managerBootPack(key: String, context: String): String = buildString {
        append("[Jarvis command-center guide: read it once now and follow it for this whole chat]\n\n")
        append(commandGuide()).append("\n\n")
        append("## jarvis_mcp tools: name(params; * = required) — what it does\n").append(catalog()).append("\n\n")
        append("## Approval policy\n").append(policyText()).append(" Inside your tasks, approvals that are not automatic come to you as events: decide them as the event says.\n\n")
        profileSection()?.let { append(it).append("\n\n") }
        val mem = JarvisPreferences.memory.value.trim()
        if (mem.isNotBlank()) append("## Jarvis memory (from earlier chats)\n").append(mem.take(3000)).append("\n\n")
        append(managerPreamble(key, context, boot = true)).append("\n\n")
        append("The user's message follows.")
    }

    /** The note in front of every message of a Jarvis text chat: who it is, the rules it forgets most, the live state, its mission. */
    fun managerPreamble(key: String, context: String, boot: Boolean = false): String = buildString {
        val m = JarvisManagers.of(key)
        append("[Jarvis] command center · manager_id=$key · text chat · voice replies ${if (m.speak) "ON (your reply is read out loud: lead with one short spoken-friendly sentence)" else "off"}.\n")
        if (!boot) {
            append("Rules (always): do small things yourself; delegate big or parallel work with jarvis_task_start (pass manager_id; use your teams). ")
            append(NO_WAIT_RULE).append(" Verify results before you accept them. Keep the mission current. Full guide: this chat's first message, or jarvis_help.\n")
        }
        personaLine()?.let { append(it).append('\n') }
        if (context.isNotBlank()) append("<live_context>\n").append(context).append("\n</live_context>\n")
        append(JarvisManagers.brief(key))
    }

    // ------------------------------------------------------------------ persona and the user's own notes

    private fun personaText(): String? = when (JarvisPreferences.persona.value) {
        "female" -> "You speak as a woman. Use feminine grammatical forms whenever you refer to yourself, in every language that has them " +
            "(Hindi: \"main kar rahi hoon\", \"main dekh rahi hoon\", \"main samajh gayi\"; Spanish: \"estoy lista\"; French: \"je suis prête\")."
        "male" -> "You speak as a man. Use masculine grammatical forms whenever you refer to yourself, in every language that has them " +
            "(Hindi: \"main kar raha hoon\", \"main dekh raha hoon\", \"main samajh gaya\"; Spanish: \"estoy listo\"; French: \"je suis prêt\")."
        "neutral" -> "Refer to yourself in gender-neutral phrasing wherever the language allows it."
        else -> null
    }

    /** One line for every message, so the persona survives compaction. */
    private fun personaLine(): String? = when (JarvisPreferences.persona.value) {
        "female" -> "persona: you speak as a woman (feminine forms for yourself, e.g. Hindi \"kar rahi hoon\")"
        "male" -> "persona: you speak as a man (masculine forms for yourself, e.g. Hindi \"kar raha hoon\")"
        "neutral" -> "persona: gender-neutral phrasing for yourself"
        else -> null
    }

    /** The persona and the user's notes, as written in Settings → Jarvis. Null when neither is set. */
    fun profileSection(): String? {
        val persona = personaText()
        val about = JarvisPreferences.aboutUser.value.trim().take(2000)
        if (persona == null && about.isBlank()) return null
        return buildString {
            if (persona != null) append("## Your persona\n").append(persona)
            if (about.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("## About the user (written by the user: follow it, e.g. how to address them)\n").append(about)
            }
        }
    }

    /** Changes whenever the persona or the user's notes change, so an open chat is told once. */
    fun profileHash(): String = Integer.toHexString((JarvisPreferences.persona.value + "|" + JarvisPreferences.aboutUser.value.trim()).hashCode())

    private fun policyText(): String = when (JarvisPreferences.permissionPolicy.value) {
        "auto_all" -> "The user allows every action automatically."
        "auto_except_risky" -> "Safe actions are approved automatically; risky ones are read out to the user and need a yes."
        "auto_readonly" -> "Read-only actions are approved automatically; everything else is read out and needs a yes."
        else -> "Every action that needs approval is read out to the user and needs a yes."
    } + if (JarvisPreferences.taskApprovals.value == "jarvis") " Inside tasks you decide approvals yourself from the task's goal (risky ones: ask the user first)." else ""

    // ------------------------------------------------------------------ live context

    private fun ago(ms: Long): String {
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h${(s % 3600) / 60}m"
        }
    }

    /**
     * What is going on right now, in a few compact lines (each message carries it, so the model rarely has to ask).
     * [owner]: a Jarvis text chat (its own tasks are in its mission brief instead).
     */
    fun liveContext(heardVia: String?, owner: String = JarvisManagers.VOICE): String {
        if (!JarvisController.isAttached) return ""
        val vm = JarvisController.vm
        val lines = ArrayList<String>()
        val time = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        lines += "now: $time · screen: ${JarvisController.screen.value}" + (heardVia?.let { " · heard_via: $it" } ?: "")
        val project = TermuxDaemonManager.activeProject.value
        val file = TermuxDaemonManager.activeTabPath.value
        if (project != null || file != null) {
            lines += "ide: " + listOfNotNull(project?.let { "project ${it.name} (${it.path})" }, file?.let { "open file ${it.substringAfterLast('/')}" }).joinToString(" · ")
        }
        vm.currentConversation.value?.takeIf { !JarvisPreferences.isJarvisChat(it.id) }?.let { c ->
            lines += "chat on screen: \"${c.title.take(50)}\" (${c.agent.name.lowercase()}, ${if (vm.isStreaming.value) "working" else "idle"}, id ${c.id})"
        }
        val tasks = JarvisTasks.tasks.value.filter { it.active || System.currentTimeMillis() - it.updatedAt < 20 * 60_000L }
            .filter { owner == JarvisManagers.VOICE || it.owner != owner }.take(6)
        if (tasks.isNotEmpty()) {
            lines += (if (owner == JarvisManagers.VOICE) "tasks: " else "other managers' tasks: ") + tasks.joinToString("; ") { t ->
                "\"${t.title.take(40)}\" ${t.state.label} ${ago(t.createdAt)} (${t.agent.name.lowercase()}, id ${t.id})"
            }
        }
        val taskIds = JarvisTasks.tasks.value.map { it.id }.toSet()
        val others = vm.allConversations.value.filter { it.isRunning && !JarvisPreferences.isJarvisChat(it.id) && it.id !in taskIds }.take(4)
        if (others.isNotEmpty()) lines += "other running chats: " + others.joinToString("; ") { "\"${it.title.take(40)}\" (${it.agent.name.lowercase()}, id ${it.id})" }
        val pending = runCatching { JarvisController.pendingFor(owner) }.getOrDefault(emptyList())
        if (pending.isNotEmpty()) lines += "waiting approvals: " + pending.take(3).joinToString("; ") { "${it.summary.take(70)} [${it.risk.name.lowercase()}, key ${it.key}]" } +
            if (pending.size > 3) " (+${pending.size - 3} more)" else ""
        val timers = JarvisController.timers.value
        if (timers.isNotEmpty()) lines += "timers: " + timers.joinToString("; ") {
            val left = ((it.endsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            "${it.label.ifBlank { "timer" }} ${if (left >= 60) "${left / 60}m${left % 60}s" else "${left}s"} left (id ${it.id})"
        }
        val flags = listOfNotNull(
            "quiet mode (answers are shown, not spoken: keep them short)".takeIf { JarvisPreferences.quietMode.value },
            "hands-free (screen may be off)".takeIf { JarvisPreferences.handsFree.value }
        )
        if (flags.isNotEmpty()) lines += "mode: " + flags.joinToString(", ")
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ optional skill file

    fun skillMarkdown(): String = """
---
name: jarvis
description: Use when a message comes from the AntiGem Jarvis voice mode (hands-free, spoken). How to answer by voice, manage tasks and control the AntiGem app through the jarvis_mcp tools.
---

""".trimStart() + guide() + "\n\n## jarvis_mcp tools\n" + catalog() + "\n"

    /** Where each agent keeps skills (the daemon expands `~`). */
    private fun skillDir(agent: AgentKind) = when (agent) {
        AgentKind.CLAUDE -> "~/.claude/skills/$SKILL_NAME"
        AgentKind.AGY -> "~/.gemini/antigravity/skills/$SKILL_NAME"
        AgentKind.OPENCODE -> "~/.config/opencode/skills/$SKILL_NAME"
    }

    /** Writes the skill file for [agent]. Returns the path on success. */
    suspend fun install(agent: AgentKind): Result<String> {
        val dir = skillDir(agent)
        val file = "$dir/SKILL.md"
        return runCatching {
            // create each level (the file API may not create parents)
            var path = ""
            for (part in dir.split('/')) {
                path = if (path.isEmpty()) part else "$path/$part"
                if (part == "~" || part.isEmpty()) continue
                IdeApiClient.createDirectory(path)
            }
            check(IdeApiClient.saveFile(file, skillMarkdown(), force = true)) { "The IDE bridge could not write $file. Is the server running?" }
            val back = IdeApiClient.readFile(file)
            check(!back.isNullOrBlank()) { "Wrote $file but could not read it back." }
            JarvisPreferences.setupSkillInstalled.set(agent.name)
            file
        }
    }

    /** Whether the skill file exists for [agent]. */
    suspend fun isInstalled(agent: AgentKind): Boolean =
        !IdeApiClient.readFile("${skillDir(agent)}/SKILL.md").isNullOrBlank()
}
