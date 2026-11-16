package com.example.gemini.data.agent.dsh

import com.example.gemini.data.agent.opencode.a
import com.example.gemini.data.agent.opencode.bool
import com.example.gemini.data.agent.opencode.long
import com.example.gemini.data.agent.opencode.o
import com.example.gemini.data.agent.opencode.obj
import com.example.gemini.data.agent.opencode.str
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChoiceOption
import com.example.gemini.domain.model.ChoiceQuestion
import com.example.gemini.domain.model.ChoiceQuestionnaire
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TreeMap

/**
 * One DeepSeek Harness chat (session) as the app shows it. dsh describes a chat as an append-only log of session
 * events (`user/message`, `assistant/message` per model step, `tool/call`, `tool/result`, `turn/end`…). The saved
 * log (`/transcript`) and live `session.event` notifications fill the same ordered map here (keyed by the event's
 * own sequence number, so the two never duplicate), which is rendered into [ChatMessage]s: every prompt the user
 * typed is one message, and everything a turn did after it becomes one assistant message (like a Codex turn).
 *
 * Not thread safe: the backend only touches it on its single worker.
 */
class DshTranscript(var sessionId: String = "") {

    /** A prompt sent from the app that dsh has not shown as a user message yet. */
    class LocalPrompt(
        val clientId: String, val text: String, val attachmentNames: List<String>, val created: Long,
        /** Images sent with it, shown as previews (dsh keeps only attachment references). */
        val images: List<ChatAttachment> = emptyList(),
        /** dsh's id of the message, once `session/query` answered. */
        var messageId: String? = null
    )

    /** A question dsh asked (`user-question/ask`), answered in the chat's question card. */
    class Question(val key: String, val questions: List<JsonObject>)

    private class ToolResult(val text: String, val isError: Boolean, val errorReason: String?, val meta: JsonObject?, val time: Long?)

    private class Group(val id: String, val startedAt: Long) {
        val content = mutableListOf<String>()
        val thoughts = mutableListOf<String>()
        /** callId → (name, raw arguments, time of the call), in call order. */
        val tools = LinkedHashMap<String, Triple<String, String, Long?>>()
        var usage: TokenUsage? = null
        var model: String? = null
        var bareModel: String? = null
        var lastAt: Long = startedAt
        var ended = false
        var aborted = false
        val errors = mutableListOf<Pair<String, String>>()
        var question: Question? = null
    }

    private val events = TreeMap<Long, JsonObject>()
    /** The log's first line: `{type:"session", id, createdAt, cwd, delegationDepth, origin?, parentSession?}`. */
    var header: JsonObject? = null
        private set
    private val localPrompts = LinkedHashMap<String, LocalPrompt>()
    /** Images sent from the app, by dsh's message id: the confirmed message keeps showing them. */
    private val sentImages = HashMap<String, List<ChatAttachment>>()
    private val errorCards = mutableListOf<Pair<Long, Pair<String, String>>>()
    /** A turn/start without its turn/end yet. */
    private var turnOpen = false

    // the model step being written right now (the bridge's live-only `antigem/stream` deltas); its finished
    // `assistant/message` replaces it
    private var liveStep = false
    private val liveText = StringBuilder()
    private val liveReasoning = StringBuilder()
    private var liveTool = false
    private var liveStartedAt = 0L

    /** dsh says the session is working (`session.status`). */
    var running = false

    /** A turn runs, or a prompt sent from the app waits for its turn. */
    val isTurnActive: Boolean get() = localPrompts.isNotEmpty() || turnOpen || running

    val isEmpty get() = events.isEmpty() && localPrompts.isEmpty()

    // ------------------------------------------------------------------ input

    /**
     * Replaces the saved part of the chat with the log read from disk ([stored], header first). Live events seen
     * meanwhile stay (same sequence numbers: the stored copy wins). [active]: dsh says the session is working.
     */
    fun loadHistory(stored: List<JsonObject>, active: Boolean) {
        for (ev in stored) {
            if (ev.str("type") == "session") { header = ev; continue }
            val seq = ev.long("seq") ?: continue
            events[seq * 4] = ev
        }
        turnOpen = false
        for (ev in events.descendingMap().values) {
            when (ev.str("type")) {
                "turn/end" -> break
                "turn/start" -> { turnOpen = true; break }
            }
        }
        running = active
        if (!active) turnOpen = false
        // prompts sent a moment ago stay until their user message shows up
        for (ev in events.values) if (ev.str("type") == "user/message") confirm(ev.o("data"))
    }

    /** Applies one live session event; true when something visible changed. */
    fun apply(ev: JsonObject): Boolean {
        val type = ev.str("type") ?: return false
        if (type == "session") { header = ev; return false }
        val seq = ev.long("seq")
        val key = if (seq != null) seq * 4 else (if (events.isEmpty()) 0L else events.lastKey()) + 1
        events[key] = ev
        when (type) {
            "turn/start" -> turnOpen = true
            "turn/end" -> { turnOpen = false; clearLive() }
            "assistant/message" -> clearLive()
            "user/message" -> confirm(ev.o("data"))
        }
        return type in VISIBLE
    }

    /**
     * Applies one live delta of the step being written (`{type: start | text | reasoning | block | end, text?,
     * blockType?}`); true when something visible changed.
     */
    fun applyLive(item: JsonObject): Boolean {
        when (item.str("type")) {
            "start" -> {
                clearLive()
                liveStep = true
                liveStartedAt = System.currentTimeMillis()
                return false
            }
            "text" -> {
                val delta = item.str("text") ?: return false
                if (liveText.isEmpty() && delta.isBlank()) return false
                liveStep = true
                liveTool = false
                liveText.append(delta)
            }
            "reasoning" -> {
                val delta = item.str("text") ?: return false
                liveStep = true
                liveReasoning.append(delta)
            }
            "block" -> when (item.str("blockType")) {
                // a new block of the same kind starts a new paragraph
                "text" -> if (liveText.isNotEmpty()) liveText.append("\n\n") else return false
                "reasoning" -> if (liveReasoning.isNotEmpty()) liveReasoning.append("\n\n") else return false
                "tool-call" -> { liveStep = true; liveTool = true }
                else -> return false
            }
            // the finished step follows as its assistant/message: the partial stays on screen until then
            "end" -> { liveTool = false; return false }
            else -> return false
        }
        if (liveStartedAt == 0L) liveStartedAt = System.currentTimeMillis()
        return true
    }

    /** Drops the partial step (its final message arrived, the turn ended, dsh exited, or the stream reconnected). */
    fun clearLive(): Boolean {
        val had = liveStep && (liveText.isNotEmpty() || liveReasoning.isNotEmpty() || liveTool)
        liveStep = false
        liveText.setLength(0)
        liveReasoning.setLength(0)
        liveTool = false
        liveStartedAt = 0L
        return had
    }

    /** A user message dsh logged: the app's own copy of it (matched by id, else by text) is no longer needed. */
    private fun confirm(d: JsonObject?) {
        if (d.o("source").str("kind") != "user" || localPrompts.isEmpty()) return
        val id = d.str("id")
        val text = textOf(d.a("content")).trim()
        val lp = localPrompts.values.firstOrNull { id != null && it.messageId == id }
            ?: localPrompts.values.firstOrNull { it.messageId == null && it.text.trim() == text }
            ?: return
        localPrompts.remove(lp.clientId)
        if (id != null && lp.images.isNotEmpty()) sentImages[id] = lp.images
    }

    /** The prompt was sent from the app: shown at once (its user message replaces it). */
    fun addLocalPrompt(clientId: String, text: String, attachmentNames: List<String>, images: List<ChatAttachment> = emptyList()) {
        localPrompts[clientId] = LocalPrompt(clientId, text, attachmentNames, System.currentTimeMillis(), images)
    }

    /** dsh took the prompt as message [messageId] (its user message may already be here). */
    fun bindLocalPrompt(clientId: String, messageId: String) {
        val lp = localPrompts[clientId] ?: return
        lp.messageId = messageId
        events.values.firstOrNull { it.str("type") == "user/message" && it.o("data").str("id") == messageId }?.let { confirm(it.o("data")) }
    }

    fun removeLocalPrompt(clientId: String) {
        localPrompts.remove(clientId)
    }

    /** A failure the app found itself (could not send…): an error card at the end. */
    fun addError(title: String, message: String) {
        errorCards.add(System.currentTimeMillis() to (title to message))
    }

    /** dsh says the session is idle, or it exited: nothing can still be running. */
    fun settleAll() {
        running = false
        turnOpen = false
        clearLive()
    }

    // ------------------------------------------------------------------ facts

    /** "provider/model" the chat last ran on (or was switched to), or null. */
    fun lastModel(): String? {
        for (ev in events.descendingMap().values) {
            val d = ev.o("data")
            when (ev.str("type")) {
                "model/selection" -> return DshPreferences.join(d.str("provider"), d.str("model")).ifBlank { null }
                "assistant/message" -> {
                    val src = d.o("message").o("source")
                    return DshPreferences.join(src.str("provider"), src.str("model")).ifBlank { null }
                }
            }
        }
        return null
    }

    /** Reasoning effort of the newest model switch, or null. */
    fun lastEffort(): String? =
        events.descendingMap().values.firstOrNull { it.str("type") == "model/selection" }?.o("data").str("reasoningEffort")

    /** Plan mode as the newest `plan/mode` event says, or null when the chat never switched it. */
    fun planMode(): Boolean? =
        events.descendingMap().values.firstOrNull { it.str("type") == "plan/mode" }?.o("data").bool("active")

    /** The newest title dsh gave the chat, or null. */
    fun title(): String? =
        events.descendingMap().values.firstOrNull { it.str("type") == "session/title" }?.o("data").str("title")?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ output

    /**
     * The chat as messages. [awaiting]: tool call ids waiting for an approval; [question]: a question dsh is waiting
     * on (shown as a choice card at the end of the running reply).
     */
    fun toChatMessages(conversationId: String, awaiting: Set<String>, question: Question?): List<ChatMessage> {
        // pass 1: what each tool call returned (a result can come after the user message of a spliced prompt)
        val results = HashMap<String, ToolResult>()
        val exitCodes = HashMap<String, Int>()
        for (ev in events.values) {
            val d = ev.o("data")
            when (ev.str("type")) {
                "tool/result" -> {
                    val m = d.o("message")
                    val id = m.str("toolCallId") ?: m.o("source").str("callId") ?: continue
                    results[id] = ToolResult(textOf(m.a("content")), m.bool("isError") == true, d.o("error")?.let { it.str("reason") ?: it.str("name") },
                        d.o("meta"), ev.long("time"))
                }
                "job/settled" -> {
                    val id = d.o("toolCall").str("callId") ?: continue
                    Regex("exit code:?\\s*(-?\\d+)").find(d.str("detail").orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { exitCodes[id] = it }
                }
            }
        }

        // pass 2: messages
        val out = mutableListOf<ChatMessage>()
        var g: Group? = null
        val seenTools = HashSet<String>()
        var summarySinceStart = false
        // a reply is named after the event it follows (a user message, a turn's end…): the same id while it is being
        // written live and once its saved events arrive
        var boundary = "start"
        fun group(@Suppress("UNUSED_PARAMETER") key: Long, t: Long): Group = g ?: Group("a_$boundary", t).also { g = it }
        fun flush(live: Boolean) {
            val cur = g ?: return
            g = null
            out.add(assistantMessage(conversationId, cur, live, awaiting, results, exitCodes))
        }
        for ((key, ev) in events) {
            val d = ev.o("data")
            val t = ev.long("time") ?: 0L
            when (ev.str("type")) {
                "user/message" -> if (d.o("source").str("kind") == "user") {
                    flush(false)
                    out.add(userMessage(conversationId, d, key, t))
                    boundary = key.toString()
                }
                "assistant/message" -> {
                    val grp = group(key, t)
                    val msg = d.o("message")
                    for (b in msg.a("content").orEmpty()) {
                        val block = b.obj() ?: continue
                        when (block.str("type")) {
                            "reasoning" -> block.str("text")?.trim()?.takeIf { it.isNotBlank() }?.let {
                                grp.content.add("<!-- thought -->\n$it\n<!-- /thought -->")
                                grp.thoughts.add(it)
                            }
                            "text" -> block.str("text")?.trim()?.takeIf { it.isNotBlank() }?.let { grp.content.add(it) }
                            "tool-call" -> {
                                val id = block.str("id") ?: continue
                                if (seenTools.add(id)) {
                                    grp.tools[id] = Triple(block.str("name").orEmpty(), block.str("arguments").orEmpty(), t)
                                    grp.content.add("<!-- tool_call:${safeId(id)} -->")
                                }
                            }
                        }
                    }
                    val src = msg.o("source")
                    DshPreferences.join(src.str("provider"), src.str("model")).ifBlank { null }?.let { grp.model = it }
                    src.str("model")?.let { grp.bareModel = it }
                    d.o("usage")?.let { u ->
                        val prev = grp.usage ?: TokenUsage()
                        grp.usage = prev.copy(
                            promptTokens = prev.promptTokens + (u.long("inputTokens") ?: 0).toInt(),
                            outputTokens = prev.outputTokens + (u.long("outputTokens") ?: 0).toInt(),
                            cachedTokens = prev.cachedTokens + (u.long("cacheReadTokens") ?: 0).toInt(),
                            cacheCreationTokens = prev.cacheCreationTokens + (u.long("cacheWriteTokens") ?: 0).toInt(),
                            totalTokens = prev.totalTokens + (u.long("totalTokens") ?: 0).toInt()
                        )
                    }
                    grp.lastAt = t
                }
                "tool/call" -> {
                    // normally announced by its assistant message already
                    val id = d.str("callId") ?: continue
                    if (seenTools.add(id)) {
                        val grp = group(key, t)
                        grp.tools[id] = Triple(d.str("name").orEmpty(), d.str("arguments").orEmpty(), t)
                        grp.content.add("<!-- tool_call:${safeId(id)} -->")
                    }
                }
                "goal/change" -> {
                    val goal = d.o("goal")
                    val line = when (d.str("operation")) {
                        null -> "*Goal cleared*"
                        "complete" -> "*Goal complete: ${goal.str("objective").orEmpty()}*"
                        "pause" -> "*Goal paused*"
                        "resume" -> "*Goal resumed: ${goal.str("objective").orEmpty()}*"
                        "block" -> "*Goal blocked: ${goal.str("objective").orEmpty()}*"
                        else -> "*Goal: ${goal.str("objective").orEmpty()}*"
                    }
                    group(key, t).content.add(line)
                }
                "command/done" -> if (d.str("kind") == "error") {
                    group(key, t).errors.add("Command failed" to d.str("text").orEmpty().ifBlank { "dsh could not run the command." })
                }
                "compaction/start" -> summarySinceStart = false
                "compaction/summary" -> {
                    flush(false)
                    summarySinceStart = true
                    val text = textOf(d.a("summary")).trim()
                    out.add(ChatMessage(id = "s_$key", conversationId = conversationId, role = MessageRole.SUMMARY,
                        content = COMPACTED, contextSummary = text.ifBlank { COMPACTED }, createdAt = t))
                    boundary = key.toString()
                }
                "compaction/end" -> {
                    val err = d.str("error")
                    if (err != null) group(key, t).errors.add("Could not compact the chat" to err)
                    else if (!summarySinceStart) {
                        flush(false)
                        out.add(ChatMessage(id = "s_$key", conversationId = conversationId, role = MessageRole.SUMMARY,
                            content = COMPACTED, contextSummary = COMPACTED, createdAt = t))
                        boundary = key.toString()
                    }
                    summarySinceStart = true
                }
                "turn/end" -> {
                    val reason = d.o("reason")
                    val cur = g
                    when (reason.str("kind")) {
                        "error" -> {
                            val e = reason.o("error")
                            group(key, t).errors.add(errorTitle(e) to (e.str("message") ?: "The model request failed."))
                        }
                        "max-tokens" -> group(key, t).errors.add("Reply cut off" to "The reply reached its output token limit (Settings → DeepSeek Harness → Provider).")
                        "aborted", "interrupted" -> cur?.aborted = true
                    }
                    g?.let { it.ended = true; it.lastAt = t }
                    flush(false)
                    boundary = key.toString()
                }
            }
        }
        // the step the model is writing right now: its thinking and text so far
        val hasLive = liveStep && (liveText.isNotEmpty() || liveReasoning.isNotEmpty() || liveTool)
        if (hasLive) {
            val grp = g ?: Group("a_$boundary", liveStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis()).also { g = it }
            val reasoning = liveReasoning.toString().trim()
            val text = liveText.toString().trim()
            if (reasoning.isNotBlank()) {
                // still thinking until the text starts
                grp.content.add("<!-- thought${if (text.isEmpty()) ":streaming" else ""} -->\n$reasoning\n<!-- /thought -->")
                grp.thoughts.add(reasoning)
            }
            if (text.isNotBlank()) grp.content.add(text)
            if (liveTool) grp.content.add("*Preparing a tool call…*")
        }
        // a question waits at the end of the running reply
        if (question != null) {
            val grp = g ?: Group("a_$boundary", System.currentTimeMillis()).also { g = it }
            grp.question = question
        }
        flush(live = isTurnActive || hasLive)
        for (lp in localPrompts.values) {
            val (text, files) = com.example.gemini.data.files.AttachedFiles.extract(lp.text, "local_" + lp.clientId)
            out.add(ChatMessage(id = "local_" + lp.clientId, conversationId = conversationId, role = MessageRole.USER,
                content = text + attachmentsSuffix(lp.attachmentNames), attachments = lp.images + files, createdAt = lp.created,
                checkpointKey = lp.clientId))
        }
        for ((at, e) in errorCards) {
            out.add(ChatMessage(id = "err_$at", conversationId = conversationId, role = MessageRole.ASSISTANT,
                content = errorMarker(e.first, e.second), createdAt = at))
        }
        return out
    }

    private fun attachmentsSuffix(names: List<String>) = names.takeIf { it.isNotEmpty() }?.let { "\n\n📎 " + it.joinToString(", ") }.orEmpty()

    private fun userMessage(conversationId: String, d: JsonObject?, key: Long, t: Long): ChatMessage {
        val id = d.str("id") ?: "u_$key"
        val parts = d.a("content").orEmpty().mapNotNull { it.obj() }
        // files sent by path are listed in the message's hidden text: shown as cards
        val (text, files) = com.example.gemini.data.files.AttachedFiles.extract(
            parts.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }, id
        )
        val images = sentImages[id].orEmpty()
        val names = parts.mapNotNull { p ->
            when (p.str("type")) {
                "image" -> if (images.isNotEmpty()) null else p.o("attachment").str("name") ?: "image"
                "file" -> p.o("attachment").str("name") ?: "file"
                else -> null
            }
        }
        return ChatMessage(id = id, conversationId = conversationId, role = MessageRole.USER,
            content = text + attachmentsSuffix(names), attachments = images + files, createdAt = t, checkpointKey = id)
    }

    private fun assistantMessage(
        conversationId: String, grp: Group, live: Boolean, awaiting: Set<String>,
        results: Map<String, ToolResult>, exitCodes: Map<String, Int>
    ): ChatMessage {
        val content = grp.content.toMutableList()
        val tools = grp.tools.map { (id, call) -> toolCall(id, call.first, call.second, call.third, live && !grp.aborted, awaiting, results[id], exitCodes[id]) }
            .toMutableList()
        grp.question?.let { q ->
            questionnaireOf(q)?.let { questionnaire ->
                val id = questionToolId(q.key)
                tools.add(ToolCall(id = id, name = "question", toolType = ToolType.ASK_CHOICE, command = questionnaire.title,
                    status = "AWAITING_CHOICE", interactionType = "dsh", questionnaire = questionnaire))
                content.add("<!-- tool_call:$id -->")
            }
        }
        for ((title, message) in grp.errors) content.add(errorMarker(title, message))
        val usage = grp.usage?.takeIf { !live && it.totalTokens > 0 }?.copy(
            durationMs = (grp.lastAt - grp.startedAt).coerceAtLeast(0), model = grp.bareModel
        )
        return ChatMessage(
            id = grp.id,
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = content.joinToString("\n\n").trim(),
            thoughtText = grp.thoughts.joinToString("\n\n").trim().takeIf { it.isNotBlank() },
            toolCalls = tools,
            isStreaming = live,
            tokenUsage = usage,
            createdAt = grp.startedAt,
            modelId = grp.model
        )
    }

    private fun toolCall(
        callId: String, name: String, rawArgs: String, calledAt: Long?, turnRunning: Boolean,
        awaiting: Set<String>, res: ToolResult?, exit: Int?
    ): ToolCall {
        val args = runCatching { DshJson.parseToJsonElement(rawArgs).obj() }.getOrNull()
        fun arg(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> args.str(k)?.takeIf { it.isNotBlank() } }
        val id = safeId(callId)
        val failed = res?.isError == true || (exit != null && exit != 0)
        val status = when {
            callId in awaiting -> "PENDING_APPROVAL"
            res != null -> if (failed) "FAILED" else "SUCCESS"
            turnRunning -> "RUNNING"
            else -> "TERMINATED"
        }
        val output = listOfNotNull(res?.text?.takeIf { it.isNotBlank() }, res?.errorReason?.takeIf { it.isNotBlank() && res.text.isBlank() })
            .joinToString("\n").let { if (it.length > MAX_OUTPUT) it.take(MAX_OUTPUT) + "\n…" else it }
        val duration = res?.time?.let { e -> calledAt?.let { s -> (e - s).coerceAtLeast(0) } }
        fun card(type: ToolType, command: String, shownName: String = name, out: String = output) =
            ToolCall(id = id, name = shownName, toolType = type, command = command, status = status, output = out,
                exitCode = exit, durationMs = duration, interactionType = "dsh")
        val n = name.lowercase()
        return when {
            n == "bash" || n == "shell" -> card(ToolType.BASH, arg("command", "cmd") ?: rawArgs)
            n == "read" || n == "read_image" || n == "read_file" -> card(ToolType.VIEW_FILE, arg("path", "file_path", "filePath", "file") ?: rawArgs)
            n == "write" || n == "edit" || n == "multi_edit" || n == "str_replace_editor" || n == "apply_patch" -> {
                val path = arg("path", "file_path", "filePath", "file")
                card(ToolType.EDIT_FILE, path?.substringAfterLast('/') ?: "Edit files", out = editDiff(n, args, res?.meta).ifBlank { output })
            }
            n == "glob" -> card(ToolType.FIND, arg("pattern", "glob") ?: rawArgs)
            n == "grep" -> card(ToolType.GREP_SEARCH, arg("pattern", "query", "regex") ?: rawArgs)
            n == "ls" || n == "list_dir" -> card(ToolType.LIST_DIR, arg("path", "dir", "directory") ?: ".")
            n.contains("web_search") -> card(ToolType.SEARCH_WEB, arg("query", "q") ?: "Web search")
            n == "web_fetch" || n == "fetch" -> card(ToolType.READ_URL, arg("url") ?: rawArgs)
            n.startsWith("mcp__") -> {
                val rest = name.removePrefix("mcp__")
                card(ToolType.MCP, rest.substringBefore("__") + " · " + rest.substringAfter("__", rest))
            }
            n == "subagent" -> card(ToolType.UNKNOWN, arg("description") ?: arg("prompt")?.take(80) ?: "Sub-agent", "subagent")
            n == "list_agents" -> card(ToolType.UNKNOWN, "List agents", "subagent")
            n == "send_message" -> card(ToolType.UNKNOWN, "Message to an agent: " + (arg("message")?.take(80) ?: ""), "subagent")
            n == "todo_write" -> {
                val todos = args.a("todos").orEmpty().mapNotNull { it.obj() }
                val done = todos.count { it.str("status") == "completed" }
                ToolCall(id = id, name = "update_plan", toolType = ToolType.UNKNOWN, command = "Plan ($done/${todos.size} done)",
                    status = if (status == "RUNNING" && done < todos.size) "RUNNING" else if (status == "PENDING_APPROVAL") status else "SUCCESS",
                    output = todos.joinToString("\n") { t ->
                        "${when (t.str("status")) { "completed" -> "☑"; "in_progress" -> "◐"; else -> "☐" }} ${t.str("content").orEmpty()}"
                    },
                    interactionType = "dsh")
            }
            else -> card(ToolType.UNKNOWN, if (rawArgs.isBlank() || rawArgs == "{}") name else "$name ${rawArgs.take(160)}")
        }
    }

    /** An edit as the edit card shows it: "+" / "-" lines and "@@" hunks. */
    private fun editDiff(name: String, args: JsonObject?, meta: JsonObject?): String {
        // dsh-tool-fs puts its result-time diff in the result's meta
        val metaDiff = meta.str("diff") ?: meta.str("patch") ?: meta.str("unifiedDiff")
        if (!metaDiff.isNullOrBlank()) return cleanDiff(metaDiff)
        return when (name) {
            "write" -> args.str("content")?.trimEnd('\n')?.lines()?.take(400)?.joinToString("\n") { "+$it" }.orEmpty()
            "edit", "str_replace_editor" -> {
                val old = args.str("old_string") ?: args.str("oldString") ?: args.str("old_str")
                val new = args.str("new_string") ?: args.str("newString") ?: args.str("new_str")
                if (old == null && new == null) "" else
                    (old.orEmpty().lines().map { "-$it" } + new.orEmpty().lines().map { "+$it" }).joinToString("\n")
            }
            "apply_patch" -> cleanDiff(args.str("patch") ?: args.str("input") ?: "")
            else -> ""
        }
    }

    private fun cleanDiff(diff: String): String = diff.lines()
        .filterNot { it.startsWith("--- ") || it.startsWith("+++ ") || it.startsWith("diff --git") || it.startsWith("index ") || it.startsWith("*** ") }
        .joinToString("\n").trimEnd('\n')

    private fun questionnaireOf(q: Question): ChoiceQuestionnaire? {
        var title = "DeepSeek Harness has a question"
        val questions = q.questions.mapNotNull { o ->
            val qid = o.str("id") ?: return@mapNotNull null
            val opts = o.a("options").orEmpty().mapNotNull { it.obj() }
            val intent = o.o("intent")
            if (intent.str("kind") == "plan-review") title = "Review the plan"
            ChoiceQuestion(
                id = qid,
                prompt = o.str("question") ?: o.str("header") ?: qid,
                description = listOfNotNull(o.str("header")?.takeIf { it != o.str("question") }, o.str("detail")).joinToString("\n\n").ifBlank { null },
                isMultiSelect = o.bool("multiSelect") == true,
                options = opts.mapNotNull { op -> op.str("label")?.let { ChoiceOption(id = it, label = it, description = op.str("description")) } },
                // a plan review or a permission card has fixed answers only
                allowCustomAnswer = intent == null || opts.isEmpty()
            )
        }
        if (questions.isEmpty()) return null
        return ChoiceQuestionnaire(title = title, questions = questions, allowCustomNote = false)
    }

    companion object {
        private const val MAX_OUTPUT = 200_000
        private const val COMPACTED = "The conversation was compacted."
        private val UNSAFE_ID = Regex("[^A-Za-z0-9_-]")

        /** Event types that change what the chat shows. */
        private val VISIBLE = setOf(
            "user/message", "assistant/message", "tool/call", "tool/result", "job/settled", "turn/start", "turn/end",
            "compaction/start", "compaction/summary", "compaction/end", "goal/change", "command/done"
        )

        /** Ids as the chat renderer's markers accept them. */
        fun safeId(raw: String): String = raw.replace(UNSAFE_ID, "_")

        fun questionToolId(key: String) = "dshq_" + safeId(key)

        /** The text blocks of a content array. */
        fun textOf(content: JsonArray?): String =
            content.orEmpty().mapNotNull { b -> b.obj()?.takeIf { it.str("type") == "text" }?.str("text") }.joinToString("\n")

        /** A provider failure's title (LlmFailure: message, code, status). */
        fun errorTitle(e: JsonObject?): String {
            val status = e.long("status")?.toInt() ?: Regex("^(\\d{3})\\b").find(e.str("message").orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val code = e.str("code").orEmpty().lowercase()
            return when {
                status == 401 || status == 403 || code.contains("auth") -> "The API key was rejected"
                status == 402 || code.contains("credit") || code.contains("balance") -> "Out of credits"
                status == 429 || code.contains("rate") -> "Rate limited"
                code.contains("context") -> "The conversation is too long"
                status != null && status >= 500 -> "The provider had an error"
                else -> "DeepSeek Harness error"
            }
        }

        fun errorMarker(title: String, message: String): String {
            val err = buildJsonObject {
                put("title", title)
                put("userMessage", message)
                put("shortError", message)
                put("fullError", "")
            }
            return "<!-- error -->\n${DshJson.encodeToString(JsonObject.serializer(), err)}\n<!-- /error -->"
        }
    }
}
