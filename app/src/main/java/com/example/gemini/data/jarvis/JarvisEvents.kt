package com.example.gemini.data.jarvis

import android.util.Log
import com.example.gemini.data.remote.PromptExpansion
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * News for the Jarvis managers: a task finished, failed, got stuck, wants an approval or asks something, or the
 * autopilot nudges an unfinished mission. News is queued per manager and handed over in ONE message once that
 * manager is free (everything that came in meanwhile, together), as a structured block the model cannot confuse with
 * the user's words or with another chat's: `<jarvis_events>` with one `<event>` per item and the sub-agent's own reply
 * inside `<task_reply>`. The chat shows only a one-line label (and renders it as a card), never the instructions.
 *
 * It also guards the managers: one that sleeps or polls while it waits for its tasks (a `sleep 60`, a wait loop,
 * ScheduleWakeup…) is stopped so the news reaches it, and the autopilot keeps an unfinished mission going.
 */
object JarvisEvents {
    private const val TAG = "JarvisEvents"

    data class Event(
        /** task_done, task_failed, task_stalled, task_approval, task_question, nudge, autopilot_paused */
        val kind: String,
        val taskId: String = "",
        val title: String = "",
        val agent: String = "",
        val team: String = "",
        val goal: String = "",
        /** The sub-agent's reply, the error, the question or the action waiting for approval. */
        val body: String = "",
        val key: String = "",
        val risk: String = "",
        val at: Long = System.currentTimeMillis()
    )

    private val queues = ConcurrentHashMap<String, MutableList<Event>>()
    /** Why the manager's last turn was stopped by the guard (told once, with the next news). */
    private val guardNotes = ConcurrentHashMap<String, String>()
    /** A message to a text manager was just sent: it is not sent another one until it shows as working (or a while passed). */
    private val sentAt = ConcurrentHashMap<String, Long>()
    private val busyBefore = ConcurrentHashMap<String, Boolean>()
    private val idleSince = ConcurrentHashMap<String, Long>()
    private val lastNudgeAt = ConcurrentHashMap<String, Long>()
    private val waitSeen = ConcurrentHashMap<String, Pair<String, Long>>()
    private val agyCheckedAt = ConcurrentHashMap<String, Long>()
    /** Last reply already read out (text managers with voice replies). */
    private val spokenReply = ConcurrentHashMap<String, String>()
    /** jarvis_speak was used in the text manager's current turn (its reply is not read out again). */
    private val spokeInTurn: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile private var voiceFlushPending = false
    @Volatile var holdVoiceUntil = 0L
    private var started = false

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch { loop() }
    }

    // ------------------------------------------------------------------ queue

    fun post(owner: String, e: Event) {
        val key = JarvisManagers.ownerOrVoice(owner)
        val q = queues.getOrPut(key) { java.util.Collections.synchronizedList(ArrayList()) }
        synchronized(q) {
            // a newer state of the same task replaces the older news of the same kind (no stale duplicates)
            q.removeAll { it.taskId.isNotBlank() && it.taskId == e.taskId && it.kind == e.kind && it.key == e.key }
            q += e
        }
        if (e.kind == "task_done" || e.kind == "task_failed") JarvisManagers.resetNudges(key)
    }

    fun pending(owner: String): Int = queues[owner]?.size ?: 0

    fun drain(owner: String): List<Event> {
        val q = queues[owner] ?: return emptyList()
        return synchronized(q) { val out = q.toList(); q.clear(); out }
    }

    fun takeGuardNote(owner: String): String? = guardNotes.remove(owner)

    fun onVoiceFlushTaken() { voiceFlushPending = false }

    fun markSpoke(owner: String) { spokeInTurn += owner }

    // ------------------------------------------------------------------ the message

    private fun kindWord(kind: String) = when (kind) {
        "task_done" -> "done"
        "task_failed" -> "failed"
        "task_stalled" -> "stuck"
        "task_approval" -> "needs approval"
        "task_question" -> "asks a question"
        "nudge" -> "keep going"
        "autopilot_paused" -> "autopilot paused"
        else -> kind
    }

    /** The one line the chat shows for the whole batch. */
    fun label(events: List<Event>): String {
        val parts = events.map { e -> if (e.title.isBlank()) "Mission: ${kindWord(e.kind)}" else "${e.title.take(30)}: ${kindWord(e.kind)}" }
        val head = if (events.size == 1) "⚡ Jarvis · " else "⚡ Jarvis · ${events.size} updates — "
        return head + parts.take(4).joinToString(" · ") + if (parts.size > 4) " · +${parts.size - 4} more" else ""
    }

    private fun attr(s: String) = s.replace("&", "&amp;").replace("\"", "'").replace("<", "‹").replace(">", "›").replace("\n", " ").trim()

    /** Text inside a section: its closing tag cannot appear in it. */
    private fun inner(s: String) = s.replace(Regex("</(task_reply|task_goal|error|question|approval|nudge|event|jarvis_events)>"), "‹/$1›").trim()

    private fun Event.toXml(n: Int): String = buildString {
        append("<event n=\"$n\" kind=\"$kind\"")
        if (taskId.isNotBlank()) append(" task_id=\"${attr(taskId)}\"")
        if (title.isNotBlank()) append(" task=\"${attr(title)}\"")
        if (team.isNotBlank()) append(" team=\"${attr(team)}\"")
        if (agent.isNotBlank()) append(" agent=\"${attr(agent)}\"")
        append(">\n")
        if (goal.isNotBlank()) append("<task_goal>\n${inner(goal.take(600))}\n</task_goal>\n")
        when (kind) {
            "task_done" -> append(if (body.isNotBlank()) "<task_reply>\n${inner(body)}\n</task_reply>\n" else "<task_reply>(no text reply; use jarvis_task_get)</task_reply>\n")
            "task_failed" -> append("<error>\n${inner(body.ifBlank { "unknown error" })}\n</error>\n")
            "task_stalled" -> append("<error>No visible progress for ${JarvisPreferences.stallMinutes.value} minutes while still running.</error>\n")
            "task_approval" -> append("<approval key=\"${attr(key)}\" risk=\"${attr(risk)}\">\nwants to ${inner(body)}\n</approval>\n")
            "task_question" -> append("<question>\n${inner(body)}\n</question>\n")
            "nudge" -> append("<nudge>\n${inner(body)}\n</nudge>\n")
            "autopilot_paused" -> append("<nudge>\n${inner(body)}\n</nudge>\n")
        }
        append("</event>\n")
    }

    private fun handling(owner: String, events: List<Event>): String = buildString {
        val voice = owner == JarvisManagers.VOICE
        val kinds = events.map { it.kind }.toSet()
        append("These are automatic messages from the AntiGem app about work YOU delegated; they are not the user's words. ")
        append("Each <event> is separate: read every <task_reply> as that sub-agent's own report.\n")
        if (voice) append("- Tell the user with ONE jarvis_speak covering all events together (one or two short spoken sentences), not one call per event.\n")
        else append("- Write the user a short update in this chat (a line per event, plain and concrete).\n")
        if ("task_done" in kinds) append("- Done: check the result against the task goal and the mission. Do not trust \"done\" blindly: verify what matters (read the files, run the tests, or send a reviewer team). Good → mark the mission step done (jarvis_mission_update) and delegate the next step. Not good enough → jarvis_task_reply with precise feedback.\n")
        if ("task_failed" in kinds || "task_stalled" in kinds) append("- Failed / stuck: find out why (jarvis_task_get), then redirect it with jarvis_task_reply, or start a fresh task with a better plan. Ask the user before anything big or risky.\n")
        if ("task_approval" in kinds) append("- Approval: if it clearly serves the task's goal and is not risky, approve it with jarvis_resolve_permission(key, approve=true). Risky or unclear: " +
            (if (voice) "read it back to the user with jarvis_ask first." else "ask the user in your reply and decide after their answer.") + "\n")
        if ("task_question" in kinds) append("- Question: if you or the user already know the answer, give it with jarvis_task_answer; otherwise ask the user, then answer.\n")
        if ("nudge" in kinds) append("- Nudge: your mission is not done and nothing is running. Continue: delegate the next step, verify, or if everything in done_when is met, call jarvis_mission_update(status=\"done\") and report. If you are blocked, say exactly what you need from the user.\n")
        if ("autopilot_paused" in kinds) append("- Autopilot paused itself after several nudges without progress. Tell the user what is blocking and what you need.\n")
        append("- Start independent tasks in parallel (several jarvis_task_start calls in one turn).\n")
        append("- Then END YOUR TURN. Never wait for tasks: no sleep / timeout / watch / wait loops, no ScheduleWakeup or Monitor, no repeated jarvis_task_get / jarvis_task_list / jarvis_read_chat to see if they finished. The next news arrives by itself as a message like this one.")
    }

    /** The whole message for a batch: label for the chat, structured events, the manager's brief, how to handle it. */
    fun format(owner: String, events: List<Event>, guardNote: String?): String {
        val body = buildString {
            append(OPEN).append("${events.size}\" from=\"AntiGem app (automatic, not typed by the user)\">\n")
            events.forEachIndexed { i, e -> append(e.toXml(i + 1)) }
            append("</jarvis_events>\n\n")
            if (!guardNote.isNullOrBlank()) append("<app_note>\n$guardNote\n</app_note>\n\n")
            append(JarvisManagers.brief(owner)).append("\n\n")
            append("<how_to_handle>\n").append(handling(owner, events)).append("\n</how_to_handle>")
        }
        return PromptExpansion.labeled(label(events), body)
    }

    // ------------------------------------------------------------------ reading it back (the chat's event card)

    data class Parsed(val kind: String, val title: String, val team: String, val agent: String, val taskId: String, val text: String)

    private val eventRe = Regex("<event ([^>]*)>([\\s\\S]*?)</event>")
    private val attrRe = Regex("(\\w+)=\"([^\"]*)\"")
    private val sectionRe = Regex("<(task_reply|error|question|approval|nudge)[^>]*>([\\s\\S]*?)</\\1>")

    /** How a real news block starts (the rules every message carries only mention "<jarvis_events>"). */
    private const val OPEN = "<jarvis_events count=\""

    fun isEventMessage(raw: String): Boolean = raw.contains(OPEN)

    /** The events of a message the app sent (null when it is not one). */
    fun parse(raw: String): List<Parsed>? {
        val start = raw.indexOf(OPEN)
        if (start < 0) return null
        val end = raw.indexOf("</jarvis_events>", start).takeIf { it > 0 } ?: raw.length
        val block = raw.substring(start, end)
        return eventRe.findAll(block).map { m ->
            val attrs = attrRe.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2].replace("&amp;", "&") }
            val text = sectionRe.findAll(m.groupValues[2]).joinToString("\n\n") { it.groupValues[2].trim() }
            Parsed(attrs["kind"].orEmpty(), attrs["task"].orEmpty(), attrs["team"].orEmpty(), attrs["agent"].orEmpty(), attrs["task_id"].orEmpty(), text)
        }.toList()
    }

    // ------------------------------------------------------------------ the loop: delivery, guard, autopilot, voice replies

    /** A loop that only waits: watch, or until / while loops around a sleep. */
    private val waitLoop = Regex("(^|[\\s;&|(`])(watch\\s|until\\b[\\s\\S]*\\bsleep\\b|while\\b[\\s\\S]*\\bsleep\\b)", RegexOption.IGNORE_CASE)
    private val sleepCmd = Regex("(^|[\\s;&|(`])sleep\\s+(\\d+(?:\\.\\d+)?)([smhd]?)", RegexOption.IGNORE_CASE)

    /** A command that waits: a wait loop, or a sleep of 10 seconds or more (a short pause inside real work is fine). */
    fun isWaitCommand(cmd: String): Boolean {
        if (waitLoop.containsMatchIn(cmd)) return true
        return sleepCmd.findAll(cmd).any { m ->
            val n = m.groupValues[2].toDoubleOrNull() ?: 0.0
            val secs = when (m.groupValues[3].lowercase()) { "m" -> n * 60; "h" -> n * 3600; "d" -> n * 86400; else -> n }
            secs >= 10
        }
    }

    /** A tool that only waits (a long sleep, a wait loop, a wake-up timer). */
    fun isWaitTool(t: ToolCall): Boolean {
        val n = t.name.lowercase()
        if (n.contains("schedulewakeup") || n == "monitor" || n.contains("sleep") || n == "wait") return true
        val shell = n == "bash" || n == "shell" || n.contains("terminal") || n.contains("command") || n == "run_command"
        return shell && isWaitCommand(t.command)
    }

    private suspend fun loop() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(1500)
            try {
                if (!JarvisController.isAttached || !JarvisPreferences.enabled.value) continue
                val owners = (JarvisManagers.textKeys() + JarvisManagers.VOICE + queues.keys).distinct()
                for (o in owners) runCatching { tick(o) }.onFailure { Log.w(TAG, "tick $o: ${it.message}") }
            } catch (e: Exception) {
                Log.w(TAG, "loop: ${e.message}")
            }
        }
    }

    private fun ownsActive(owner: String) = JarvisTasks.activeTasks().any { it.owner == owner }

    private suspend fun tick(owner: String) {
        val now = System.currentTimeMillis()
        val voice = owner == JarvisManagers.VOICE
        if (!voice && !JarvisManagers.isTextManager(owner)) { queues.remove(owner); return }
        val queued = queues[owner]?.toList().orEmpty()
        val oldest = queued.minOfOrNull { it.at }
        val active = ownsActive(owner)
        var busy = if (voice) JarvisController.isVoiceChatBusy() else JarvisDriver.isBusy(owner)
        // a Jarvis text chat with something going on keeps a live backend (its state, messages and approvals stay current)
        if (!voice && (queued.isNotEmpty() || active || busy)) {
            JarvisDriver.ensure(owner)
            busy = busy || JarvisDriver.isBusy(owner)
            val offScreen = JarvisController.vm.currentConversation.value?.id != owner
            if (busy && offScreen && JarvisDriver.agentOf(owner) == com.example.gemini.domain.model.AgentKind.AGY) JarvisTasks.refreshAgyPending(owner, 4000)
        }

        // ---- turn edges (text managers: read the reply out when voice replies are on)
        val was = busyBefore.put(owner, busy) ?: false
        if (busy) { idleSince.remove(owner); if (!was) sentAt.remove(owner) }
        else if (was || idleSince[owner] == null) idleSince[owner] = now
        if (!voice && was && !busy) onTextTurnEnded(owner)

        // ---- the guard: waiting for tasks inside a turn
        if (busy && (queued.isNotEmpty() || active)) guard(owner, queued.isNotEmpty(), now) else waitSeen.remove(owner)

        // ---- delivery
        if (queued.isNotEmpty() && oldest != null && now - oldest >= JarvisPreferences.eventBatchMs.value.toLong().coerceIn(0, 30_000)) {
            if (voice) {
                if (now >= holdVoiceUntil && !voiceFlushPending) { voiceFlushPending = true; JarvisController.requestEventFlush() }
            } else if (!busy && now - (sentAt[owner] ?: 0L) > 25_000) {
                val batch = drain(owner)
                if (batch.isNotEmpty()) {
                    sentAt[owner] = now
                    JarvisManagers.markSent(owner)
                    spokeInTurn -= owner
                    JarvisDriver.send(owner, format(owner, batch, takeGuardNote(owner))).onFailure { e ->
                        Log.w(TAG, "send to $owner failed: ${e.message}")
                        // put it back and try again later
                        val q = queues.getOrPut(owner) { java.util.Collections.synchronizedList(ArrayList()) }
                        synchronized(q) { q.addAll(0, batch) }
                        sentAt[owner] = now
                    }
                }
            }
        }

        // ---- a text chat with nothing going on for a while does not need its own backend any more
        val m = JarvisManagers.of(owner)
        if (!voice && queued.isEmpty() && !active && !busy && !m.mission.autopilot && now - (idleSince[owner] ?: now) > 180_000 && JarvisDriver.isAttached(owner)) {
            JarvisDriver.release(owner)
        }

        // ---- autopilot
        if (m.mission.isActive && m.mission.autopilot && !busy && queued.isEmpty() && !active) {
            val idleFor = now - (idleSince[owner] ?: now)
            val sinceNudge = now - (lastNudgeAt[owner] ?: 0L)
            val sinceSent = now - (sentAt[owner] ?: 0L)
            if (idleFor > 30_000 && sinceNudge > 60_000 && sinceSent > 25_000 && !(voice && JarvisController.isUserTalking())) {
                // looked at now: the next look is a minute away, whether it nudges or not
                lastNudgeAt[owner] = now
                if (waitingForUser(owner)) return
                val max = JarvisPreferences.autopilotMaxNudges.value.coerceIn(1, 20)
                if (m.mission.nudges >= max) {
                    JarvisManagers.update(owner) { it.copy(mission = it.mission.copy(autopilot = false, nudges = 0)) }
                    post(owner, Event("autopilot_paused", body = "Autopilot nudged you $max times in a row without any progress, so it paused."))
                } else {
                    JarvisManagers.update(owner) { it.copy(mission = it.mission.copy(nudges = it.mission.nudges + 1)) }
                    val left = m.mission.steps.filter { it.status != "done" }.joinToString("; ") { "${it.n}. ${it.text}" }.take(400)
                    post(owner, Event("nudge", body = "The mission \"${m.mission.goal.take(120)}\" is not done and no team is working." +
                        (if (left.isNotBlank()) " Open steps: $left" else "") + " (nudge ${m.mission.nudges + 1} of $max)"))
                }
            }
        }
    }

    /** The manager's last words asked the user something: the answer is theirs to give, the autopilot does not push. */
    private suspend fun waitingForUser(owner: String): Boolean {
        val last = if (owner == JarvisManagers.VOICE) JarvisController.said.value else runCatching { JarvisDriver.lastReply(owner) }.getOrDefault("")
        return last.trimEnd().endsWith("?")
    }

    private suspend fun guard(owner: String, hasNews: Boolean, now: Long) {
        val mode = JarvisPreferences.waitGuard.value
        if (mode == "off") return
        val voice = owner == JarvisManagers.VOICE
        val agy = (if (voice) JarvisPreferences.currentChatAgent.value else JarvisDriver.agentOf(owner).name) == com.example.gemini.domain.model.AgentKind.AGY.name
        // Antigravity's steps come from the server: look at most every 15 s
        if (agy && now - (agyCheckedAt[owner] ?: 0L) < 15_000) return
        if (agy) agyCheckedAt[owner] = now
        val tool = if (voice) JarvisController.voiceRunningTool() else JarvisDriver.runningTool(owner)
        if (tool == null || !isWaitTool(tool)) { waitSeen.remove(owner); return }
        val seen = waitSeen[owner]?.takeIf { it.first == tool.id } ?: (tool.id to now).also { waitSeen[owner] = it }
        val waited = now - seen.second
        val what = tool.command.trim().take(60).ifBlank { tool.name }
        val note = "You were waiting inside your turn (`$what`) for work you delegated. Never do that: delegate, then end your turn. " +
            "The app delivers every result to you by itself as a <jarvis_events> message."
        if (mode == "remind") { guardNotes[owner] = note; return }
        if ((hasNews && waited > 3_000) || waited > 12_000) {
            Log.i(TAG, "stopping $owner: it waits ($what)")
            waitSeen.remove(owner)
            guardNotes[owner] = "The app stopped your previous turn. $note"
            if (voice) JarvisController.stopVoiceTurnForNews() else JarvisDriver.stop(owner)
        }
    }

    private suspend fun onTextTurnEnded(owner: String) {
        val m = JarvisManagers.get(owner) ?: return
        JarvisManagers.setStatus(owner, null)
        if (!m.speak) { spokeInTurn -= owner; return }
        if (spokeInTurn.remove(owner)) return
        delay(600)
        val reply = JarvisDriver.lastReply(owner)
        if (reply.isBlank() || spokenReply[owner] == reply) return
        spokenReply[owner] = reply
        if (reply.contains(JarvisSkill.MCP_MISSING)) return
        JarvisController.announce(JarvisController.spokenForm(reply))
    }
}
