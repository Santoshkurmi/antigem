package com.example.gemini.data.jarvis

import android.content.Context
import android.content.SharedPreferences
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jarvis as a lead agent ("command center"). Every Jarvis chat is a manager: the hidden voice chat (key [VOICE]) and
 * every Jarvis text chat the user opens from the Jarvis tab (key = that chat's id). A manager owns the tasks it starts
 * (their news goes back to it, see [JarvisEvents]), keeps a mission (goal, done-when, steps, autopilot) and teams
 * (named roles: agent, model, project, instructions). The mission and teams live here, in the app, and are repeated
 * to the model in every message ([brief]), so a manager that works for hours never forgets what it is doing, even
 * after its own context was compacted.
 */
object JarvisManagers {
    const val VOICE = "voice"

    data class Step(val n: Int, val text: String, val status: String = "todo", val note: String = "")

    data class Mission(
        val goal: String = "",
        val doneWhen: String = "",
        val steps: List<Step> = emptyList(),
        /** "active", "done" or "paused". */
        val status: String = "active",
        /** Keep working until done: the app nudges the manager when it stops while nothing is running. */
        val autopilot: Boolean = false,
        val notes: String = "",
        val updatedAt: Long = System.currentTimeMillis(),
        /** Nudges in a row without progress (reset whenever something moves). */
        val nudges: Int = 0
    ) {
        val isEmpty: Boolean get() = goal.isBlank() && steps.isEmpty()
        val doneCount: Int get() = steps.count { it.status == "done" }
        val isActive: Boolean get() = !isEmpty && status == "active"
    }

    data class Team(
        val name: String,
        val agent: AgentKind,
        val model: String = "",
        /** What this team does and how (sent hidden with every task the team gets). */
        val role: String = "",
        val project: String = ""
    )

    data class Manager(
        val key: String,
        /** The agent of a text chat (null for the voice chat: it follows Settings → Jarvis → Agent). */
        val agent: AgentKind? = null,
        /** Read the replies out loud (text chats). */
        val speak: Boolean = false,
        val mission: Mission = Mission(),
        val teams: List<Team> = emptyList(),
        /** The guide version this chat already got in full (empty: the next message carries the whole guide). */
        val bootFor: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        /** Last time a message (the user's or news) was sent to this chat. */
        val lastSentAt: Long = 0L
    ) {
        val isVoice: Boolean get() = key == VOICE
    }

    private var sp: SharedPreferences? = null
    private val _managers = MutableStateFlow<Map<String, Manager>>(emptyMap())
    /** Every manager by key (the voice one only once it has a mission or teams). */
    val managers: StateFlow<Map<String, Manager>> = _managers.asStateFlow()

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    /** What each text manager says it is doing right now (jarvis_status), shown on its command strip. */
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("jarvis_managers", Context.MODE_PRIVATE)
        sp = p
        _managers.value = runCatching { parse(p.getString("all", null)) }.getOrDefault(emptyMap())
    }

    // ------------------------------------------------------------------ lookups

    fun get(key: String?): Manager? = key?.let { _managers.value[it] }

    /** The manager (the voice one always exists, empty until used). */
    fun of(key: String): Manager = _managers.value[key] ?: Manager(key)

    fun isTextManager(id: String?): Boolean = !id.isNullOrBlank() && id != VOICE && _managers.value.containsKey(id)

    fun textKeys(): List<String> = _managers.value.keys.filter { it != VOICE }

    /** The manager a chat belongs to: its own key for a text chat, [VOICE] for the voice chat, null otherwise. */
    fun keyForChat(chatId: String?): String? = when {
        chatId.isNullOrBlank() -> null
        isTextManager(chatId) -> chatId
        JarvisPreferences.isJarvisChat(chatId) -> VOICE
        else -> null
    }

    /** A valid owner: a text chat that still exists as a manager, otherwise the voice chat. */
    fun ownerOrVoice(key: String?): String = if (isTextManager(key)) key!! else VOICE

    /** Who is calling a tool, and whether that is certain (not a guess between several Jarvis chats at work). */
    data class Caller(val key: String, val sure: Boolean)

    /** MCP connection (session id) → the manager it belongs to, learned from calls that named their manager_id. */
    private val sessionOwner = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Connections shared by several managers (an agent that uses one connection for all its chats): never used to tell them apart. */
    private val sharedSessions: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun learnSession(session: String?, key: String) {
        if (session.isNullOrBlank() || session in sharedSessions) return
        val prev = sessionOwner.putIfAbsent(session, key)
        if (prev != null && prev != key) { sessionOwner.remove(session); sharedSessions += session }
    }

    private fun known(key: String) = key == VOICE || isTextManager(key)

    /**
     * Which Jarvis chat is calling a tool: the manager_id it passed (every message tells it its id), else the one its MCP
     * connection belongs to, else, when exactly one Jarvis chat is working, that one. Anything else is a guess
     * ([Caller.sure] = false): tools that act for a manager then ask for the manager_id instead of guessing.
     */
    fun resolveCaller(arg: String?, session: String? = null): Caller {
        val a = arg?.trim().orEmpty()
        if (a.isNotEmpty()) {
            val key = when {
                a.equals(VOICE, ignoreCase = true) -> VOICE
                isTextManager(a) -> a
                a.length >= 6 -> textKeys().firstOrNull { it.startsWith(a) }
                else -> null
            }
            if (key != null) { learnSession(session, key); return Caller(key, true) }
        }
        if (!session.isNullOrBlank() && session !in sharedSessions) {
            sessionOwner[session]?.takeIf { known(it) }?.let { return Caller(it, true) }
        }
        val texts = textKeys()
        val busyText = texts.filter { runCatching { JarvisDriver.isBusy(it) }.getOrDefault(false) }
        val voiceBusy = runCatching { JarvisController.isVoiceChatBusy() }.getOrDefault(false)
        val busy = busyText + listOfNotNull(VOICE.takeIf { voiceBusy })
        // only an explicit manager_id teaches a connection its owner; a guess never does
        if (busy.size == 1) return Caller(busy[0], true)
        if (texts.isEmpty()) return Caller(VOICE, busy.isEmpty() || busy == listOf(VOICE))
        // several at work (or none seen yet): the one written to last, but only as a guess
        val pool = busy.ifEmpty { texts + VOICE }
        val recent = pool.maxByOrNull { if (it == VOICE) JarvisPreferences.lastActivityAt.value else of(it).lastSentAt } ?: VOICE
        return Caller(recent, false)
    }

    /** A Jarvis chat's name for messages ("the voice chat", or its title). */
    fun nameOf(key: String): String = if (key == VOICE) "the voice Jarvis chat" else
        runCatching { JarvisController.vm.allConversations.value.find { it.id == key }?.title }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "the Jarvis chat \"${it.take(40)}\"" }
            ?: "another Jarvis chat"

    // ------------------------------------------------------------------ changes

    @Synchronized
    fun update(key: String, change: (Manager) -> Manager) {
        val cur = _managers.value[key] ?: Manager(key)
        val next = change(cur)
        if (next == cur && _managers.value.containsKey(key)) return
        _managers.value = _managers.value + (key to next)
        save()
    }

    /** Makes [chatId] a Jarvis text chat (it is listed under Jarvis and gets the command-center guide). */
    fun registerTextChat(chatId: String, agent: AgentKind, speak: Boolean = JarvisPreferences.managerSpeakDefault.value) {
        if (chatId.isBlank() || chatId == VOICE) return
        JarvisPreferences.addChatId(chatId)
        if (_managers.value.containsKey(chatId)) return
        update(chatId) { Manager(chatId, agent = agent, speak = speak) }
    }

    @Synchronized
    fun remove(key: String) {
        if (!_managers.value.containsKey(key)) return
        _managers.value = _managers.value - key
        _status.value = _status.value - key
        save()
    }

    fun setSpeak(key: String, on: Boolean) = update(key) { it.copy(speak = on) }

    fun markSent(key: String) = update(key) { it.copy(lastSentAt = System.currentTimeMillis()) }

    fun setStatus(key: String, text: String?) {
        _status.value = if (text.isNullOrBlank()) _status.value - key else _status.value + (key to text.trim().take(70))
    }

    // ---- mission

    fun setMission(key: String, goal: String, doneWhen: String, steps: List<String>, autopilot: Boolean?) = update(key) { m ->
        m.copy(mission = Mission(
            goal = goal.trim().take(600),
            doneWhen = doneWhen.trim().take(400),
            steps = steps.map { it.trim() }.filter { it.isNotEmpty() }.take(40).mapIndexed { i, s -> Step(i + 1, s.take(200)) },
            autopilot = autopilot ?: m.mission.autopilot
        ))
    }

    /** Changes one step, adds steps, sets the mission's status, autopilot or notes. Returns what changed, for the reply. */
    fun updateMission(
        key: String, step: Int?, stepStatus: String?, note: String?, addSteps: List<String>,
        status: String?, autopilot: Boolean?, notes: String?
    ): String {
        val changes = ArrayList<String>()
        update(key) { m ->
            var mi = m.mission
            if (step != null) {
                val s = mi.steps.firstOrNull { it.n == step }
                if (s != null) {
                    mi = mi.copy(steps = mi.steps.map {
                        if (it.n == step) it.copy(status = stepStatus ?: it.status, note = note?.trim()?.take(200) ?: it.note) else it
                    })
                    changes += "step $step${stepStatus?.let { " → $it" }.orEmpty()}"
                } else changes += "no step $step"
            }
            if (addSteps.isNotEmpty()) {
                var n = (mi.steps.maxOfOrNull { it.n } ?: 0)
                mi = mi.copy(steps = mi.steps + addSteps.map { it.trim() }.filter { it.isNotEmpty() }.map { Step(++n, it.take(200)) })
                changes += "${addSteps.size} step(s) added"
            }
            if (status != null && status in setOf("active", "done", "paused")) {
                mi = mi.copy(status = status, autopilot = if (status != "active") false else mi.autopilot)
                changes += "mission $status"
            }
            if (autopilot != null) { mi = mi.copy(autopilot = autopilot); changes += "autopilot ${if (autopilot) "on" else "off"}" }
            if (notes != null) { mi = mi.copy(notes = notes.trim().take(1500)); changes += "notes saved" }
            // the manager moved the mission on: the autopilot's patience starts over
            m.copy(mission = mi.copy(updatedAt = System.currentTimeMillis(), nudges = 0))
        }
        return changes.joinToString(", ").ifBlank { "nothing changed" }
    }

    fun clearMission(key: String) = update(key) { it.copy(mission = Mission()) }

    fun setAutopilot(key: String, on: Boolean) = update(key) { it.copy(mission = it.mission.copy(autopilot = on, nudges = 0, status = if (on && it.mission.status != "active") "active" else it.mission.status)) }

    /** Something moved (a task finished, the user wrote): the autopilot's patience starts over. */
    fun resetNudges(key: String) {
        if ((_managers.value[key]?.mission?.nudges ?: 0) == 0) return
        update(key) { it.copy(mission = it.mission.copy(nudges = 0)) }
    }

    // ---- teams

    fun upsertTeam(key: String, team: Team) = update(key) { m ->
        m.copy(teams = (m.teams.filter { !it.name.equals(team.name, ignoreCase = true) } + team).take(12))
    }

    fun removeTeam(key: String, name: String): Boolean {
        var had = false
        update(key) { m -> had = m.teams.any { it.name.equals(name, true) }; m.copy(teams = m.teams.filter { !it.name.equals(name, true) }) }
        return had
    }

    fun team(key: String, name: String?): Team? =
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { n -> of(key).teams.firstOrNull { it.name.equals(n, ignoreCase = true) } }

    // ------------------------------------------------------------------ what the model is told every time

    private fun ago(ms: Long): String {
        val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h${(s % 3600) / 60}m"
        }
    }

    private fun mark(status: String) = when (status) {
        "done" -> "[x]"
        "doing" -> "[>]"
        "blocked" -> "[!]"
        else -> "[ ]"
    }

    /**
     * The manager's memory, repeated in every message it gets: mission with its steps, teams and its own tasks. Short
     * enough to send each time; the app keeps it, so it survives compaction.
     */
    fun brief(key: String): String {
        val m = of(key)
        val mine = JarvisTasks.tasks.value.filter { it.owner == key && (it.active || System.currentTimeMillis() - it.updatedAt < 30 * 60_000L) }
        return buildString {
            append("<mission_brief manager_id=\"$key\">\n")
            val mi = m.mission
            if (mi.isEmpty) {
                append("mission: none yet. For any job with more than one step call jarvis_mission_set first (goal, done_when, steps).\n")
            } else {
                append("mission: ${mi.goal}\n")
                append("status: ${mi.status}")
                if (mi.steps.isNotEmpty()) append(" · ${mi.doneCount}/${mi.steps.size} steps done")
                append(" · autopilot ${if (mi.autopilot) "ON (keep going until done_when is met)" else "off"}\n")
                if (mi.doneWhen.isNotBlank()) append("done when: ${mi.doneWhen}\n")
                if (mi.steps.isNotEmpty()) {
                    append("steps:\n")
                    mi.steps.forEach { s ->
                        append("  ${mark(s.status)} ${s.n}. ${s.text}")
                        if (s.note.isNotBlank()) append(" — ${s.note}")
                        append('\n')
                    }
                }
                if (mi.notes.isNotBlank()) append("notes: ${mi.notes.take(600)}\n")
            }
            if (m.teams.isNotEmpty()) {
                append("teams: ")
                append(m.teams.joinToString(" · ") { t ->
                    "${t.name} (${t.agent.name.lowercase()}${if (t.model.isNotBlank()) ", ${t.model}" else ""})" + (if (t.role.isNotBlank()) ": ${t.role.take(80)}" else "")
                })
                append('\n')
            }
            if (mine.isEmpty()) append("your tasks: none running\n")
            else {
                append("your tasks:\n")
                mine.take(10).forEach { t ->
                    append("  - \"${t.title.take(40)}\" id ${t.id.take(8)}")
                    if (t.team.isNotBlank()) append(" · team ${t.team}")
                    append(" · ${t.agent.name.lowercase()} · ${t.state.label} · started ${ago(t.createdAt)} ago")
                    if (t.note.isNotBlank() && t.note != "Working") append(" · ${t.note.take(60)}")
                    append('\n')
                }
            }
            append("</mission_brief>")
        }
    }

    // ------------------------------------------------------------------ storage

    private fun save() {
        val arr = JSONArray()
        _managers.value.values.forEach { m ->
            arr.put(JSONObject().apply {
                put("key", m.key); put("agent", m.agent?.name ?: ""); put("speak", m.speak); put("boot", m.bootFor)
                put("created", m.createdAt); put("sent", m.lastSentAt)
                put("mission", JSONObject().apply {
                    put("goal", m.mission.goal); put("done_when", m.mission.doneWhen); put("status", m.mission.status)
                    put("autopilot", m.mission.autopilot); put("notes", m.mission.notes); put("updated", m.mission.updatedAt)
                    put("nudges", m.mission.nudges)
                    put("steps", JSONArray().apply {
                        m.mission.steps.forEach { s -> put(JSONObject().put("n", s.n).put("text", s.text).put("status", s.status).put("note", s.note)) }
                    })
                })
                put("teams", JSONArray().apply {
                    m.teams.forEach { t ->
                        put(JSONObject().put("name", t.name).put("agent", t.agent.name).put("model", t.model).put("role", t.role).put("project", t.project))
                    }
                })
            })
        }
        sp?.edit()?.putString("all", arr.toString())?.apply()
    }

    private fun parse(raw: String?): Map<String, Manager> {
        if (raw.isNullOrBlank()) return emptyMap()
        val arr = JSONArray(raw)
        val out = LinkedHashMap<String, Manager>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val key = o.optString("key")
            if (key.isBlank()) continue
            val mo = o.optJSONObject("mission") ?: JSONObject()
            val steps = mo.optJSONArray("steps")?.let { a ->
                (0 until a.length()).map { j -> a.getJSONObject(j).let { s -> Step(s.optInt("n", j + 1), s.optString("text"), s.optString("status", "todo"), s.optString("note")) } }
            }.orEmpty()
            val teams = o.optJSONArray("teams")?.let { a ->
                (0 until a.length()).mapNotNull { j ->
                    val t = a.getJSONObject(j)
                    val agent = runCatching { AgentKind.valueOf(t.optString("agent")) }.getOrNull() ?: return@mapNotNull null
                    Team(t.optString("name"), agent, t.optString("model"), t.optString("role"), t.optString("project"))
                }
            }.orEmpty()
            out[key] = Manager(
                key = key,
                agent = o.optString("agent").takeIf { it.isNotBlank() }?.let { runCatching { AgentKind.valueOf(it) }.getOrNull() },
                speak = o.optBoolean("speak"),
                mission = Mission(
                    goal = mo.optString("goal"), doneWhen = mo.optString("done_when"), steps = steps,
                    status = mo.optString("status", "active"), autopilot = mo.optBoolean("autopilot"), notes = mo.optString("notes"),
                    updatedAt = mo.optLong("updated", System.currentTimeMillis()), nudges = mo.optInt("nudges")
                ),
                teams = teams,
                bootFor = o.optString("boot"),
                createdAt = o.optLong("created", System.currentTimeMillis()),
                lastSentAt = o.optLong("sent")
            )
        }
        return out
    }
}
