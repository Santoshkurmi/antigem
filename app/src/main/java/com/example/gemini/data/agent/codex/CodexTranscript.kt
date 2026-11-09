package com.example.gemini.data.agent.codex

import com.example.gemini.data.agent.opencode.a
import com.example.gemini.data.agent.opencode.long
import com.example.gemini.data.agent.opencode.o
import com.example.gemini.data.agent.opencode.obj
import com.example.gemini.data.agent.opencode.str
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One Codex chat (thread) as the app shows it. Codex describes a chat as turns, each a list of items (the user's
 * message, the agent's messages and reasoning, commands, file changes, tool calls…). History (`thread/turns/list`)
 * and live events (`item/started`, deltas, `item/completed`, `turn/completed`…) fill the same model here, which is
 * rendered into [ChatMessage]s: every user message is one message, and the items between two user messages become
 * one assistant message (like a Claude turn).
 *
 * Not thread safe: the backend only touches it on its single worker.
 */
class CodexTranscript(var threadId: String = "") {

    class Item(val id: String, var type: String, var json: JsonObject) {
        /** agentMessage / plan text (deltas while it streams). */
        val text = StringBuilder()
        /** reasoning summary parts. */
        val summary = mutableListOf<StringBuilder>()
        /** raw reasoning text (only some models send it). */
        val reasoning = StringBuilder()
        /** command output while it runs. */
        val output = StringBuilder()
        var streaming = false
        var startedAt: Long? = null
        var completedAt: Long? = null
    }

    class Turn(val id: String) {
        val items = LinkedHashMap<String, Item>()
        /** The turn this one belongs to (a review runs as an inner turn of the review/start turn). */
        var rootTurnId: String? = null
        /** The model that ran this turn (live: the chat's model when it started; history: Codex's record). */
        var model: String? = null
        var status = "inProgress"
        var error: JsonObject? = null
        var startedAt: Long? = null
        var completedAt: Long? = null
        var durationMs: Long? = null
        /** Codex's todo list for this turn (`turn/plan/updated`). */
        var plan: List<Pair<String, String>>? = null
        /** Token use of this turn (live turns only: history has none). */
        var usage: TokenUsage? = null
        /** Thread total before this turn's first model request. */
        var usageBase: JsonObject? = null
    }

    /** A prompt sent from the app that Codex has not shown as a user message yet. */
    class LocalPrompt(
        val clientId: String, val text: String, val attachmentNames: List<String>, val created: Long,
        /** Images sent with it, shown as previews until Codex confirms the message. */
        val images: List<com.example.gemini.domain.model.ChatAttachment> = emptyList()
    )

    /** A question Codex asked (`item/tool/requestUserInput`), answered in the chat's question card. */
    class Question(val key: String, val turnId: String?, val questions: List<JsonObject>)

    private val turns = LinkedHashMap<String, Turn>()
    private val localPrompts = LinkedHashMap<String, LocalPrompt>()
    private val errorCards = mutableListOf<Pair<Long, Pair<String, String>>>()
    /** A prompt was sent and its turn has not started yet. */
    private var awaitingStart = false

    /** The model of the chat (Codex does not say per reply). */
    var model: String? = null

    val isTurnActive: Boolean get() = awaitingStart || turns.values.any { it.status == "inProgress" }

    /** The running turn's id, if any (steer, interrupt). */
    val activeTurnId: String? get() = turns.values.lastOrNull { it.status == "inProgress" }?.id

    val isEmpty get() = turns.isEmpty() && localPrompts.isEmpty()

    fun reset(id: String) {
        threadId = id
        turns.clear()
        localPrompts.clear()
        errorCards.clear()
        awaitingStart = false
        model = null
    }

    // ------------------------------------------------------------------ history

    /** Replaces everything with stored turns (oldest first). [active]: the thread has a turn running. */
    fun loadHistory(stored: List<JsonObject>, active: Boolean) {
        // usage and model of turns seen live survive a reload (history has neither; [applyMeta] adds Codex's record)
        val previous = LinkedHashMap(turns)
        turns.clear()
        for (t in stored) {
            val turn = turnOf(t)
            previous[turn.id]?.let { p -> turn.usage = p.usage; turn.model = p.model }
            turns[turn.id] = turn
        }
        if (active) {
            // Codex saves an item only once it is finished: the running turn's answer so far (streamed while this
            // chat was not on screen) is only in what was seen live. Merge it into the stored turn.
            for ((id, p) in previous) {
                if (p.status != "inProgress") continue
                val turn = turns[id]
                if (turn == null) {
                    turns[id] = p
                    continue
                }
                for ((itemId, live) in p.items) {
                    val stored = turn.items[itemId]
                    if (stored == null) {
                        turn.items[itemId] = live
                    } else {
                        if (stored.text.isEmpty() && live.text.isNotEmpty()) stored.text.append(live.text)
                        if (stored.summary.all { it.isEmpty() } && live.summary.any { it.isNotEmpty() }) {
                            stored.summary.clear(); stored.summary.addAll(live.summary)
                        }
                        if (stored.output.isEmpty() && live.output.isNotEmpty()) stored.output.append(live.output)
                        if (stored.completedAt == null) stored.streaming = live.streaming
                    }
                }
                if (turn.plan == null) turn.plan = p.plan
            }
        }
        if (!active) {
            for (t in turns.values) if (t.status == "inProgress") t.status = "interrupted"
            awaitingStart = false
        }
        // prompts sent a moment ago stay until their user message shows up
        val seen = turns.values.flatMap { t -> t.items.values.mapNotNull { it.json.str("clientId") } }.toSet()
        localPrompts.keys.removeAll(seen)
        for (t in turns.values) finishStreaming(t, t.status == "inProgress")
    }

    private fun turnOf(t: JsonObject): Turn {
        val turn = Turn(t.str("id").orEmpty())
        applyTurnFields(turn, t)
        for (it in t.a("items").orEmpty()) {
            val o = it.obj() ?: continue
            val item = itemOf(o)
            turn.items[item.id] = item
        }
        return turn
    }

    private fun applyTurnFields(turn: Turn, t: JsonObject) {
        t.str("status")?.let { turn.status = it }
        t.str("rootTurnId")?.takeIf { it != turn.id }?.let { turn.rootTurnId = it }
        turn.error = t.o("error") ?: turn.error
        t.long("startedAt")?.let { turn.startedAt = it * 1000 }
        t.long("completedAt")?.let { turn.completedAt = it * 1000 }
        t.long("durationMs")?.let { turn.durationMs = it }
    }

    private fun itemOf(o: JsonObject): Item {
        val item = Item(o.str("id").orEmpty(), o.str("type").orEmpty(), o)
        fillFromJson(item, o)
        return item
    }

    private fun fillFromJson(item: Item, o: JsonObject) {
        item.json = o
        item.type = o.str("type") ?: item.type
        o.long("startedAtMs")?.let { item.startedAt = it }
        o.long("completedAtMs")?.let { item.completedAt = it }
        when (item.type) {
            "agentMessage", "plan" -> o.str("text")?.takeIf { it.isNotEmpty() || item.text.isEmpty() }?.let { item.text.setLength(0); item.text.append(it) }
            "reasoning" -> {
                val parts = o.a("summary").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (parts.isNotEmpty()) {
                    item.summary.clear()
                    parts.forEach { item.summary.add(StringBuilder(it)) }
                }
                val content = o.a("content").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
                if (content.isNotEmpty()) { item.reasoning.setLength(0); item.reasoning.append(content) }
            }
            "commandExecution" -> o.str("aggregatedOutput")?.let { item.output.setLength(0); item.output.append(it) }
        }
    }

    // ------------------------------------------------------------------ live events

    /** The prompt was sent from the app: shown at once (its user message replaces it, matched by [clientId]). */
    fun addLocalPrompt(
        clientId: String, text: String, attachmentNames: List<String>, startsTurn: Boolean,
        images: List<com.example.gemini.domain.model.ChatAttachment> = emptyList()
    ) {
        localPrompts[clientId] = LocalPrompt(clientId, text, attachmentNames, System.currentTimeMillis(), images)
        if (startsTurn) awaitingStart = true
    }

    fun removeLocalPrompt(clientId: String) {
        localPrompts.remove(clientId)
        awaitingStart = false
    }

    /** A failure the app found itself (could not send…): an error card at the end. */
    fun addError(title: String, message: String) {
        errorCards.add(System.currentTimeMillis() to (title to message))
        awaitingStart = false
    }

    private fun turn(id: String?): Turn? {
        if (id.isNullOrBlank()) return null
        return turns.getOrPut(id) { Turn(id).also { it.startedAt = System.currentTimeMillis() } }
    }

    private fun item(turnId: String?, itemId: String?, type: String): Item? {
        if (itemId.isNullOrBlank()) return null
        val t = turn(turnId) ?: turns.values.lastOrNull() ?: return null
        t.items[itemId]?.let { return it }
        // an item of this id in another turn (history loaded mid-turn)
        for (other in turns.values) other.items[itemId]?.let { return it }
        return Item(itemId, type, buildJsonObject { put("id", itemId); put("type", type) }).also { t.items[itemId] = it }
    }

    /** Applies one notification of this thread; true when something visible changed. */
    fun apply(method: String, p: JsonObject): Boolean {
        val turnId = p.str("turnId")
        when (method) {
            "turn/started" -> {
                val t = p.o("turn") ?: return false
                val turn = turn(t.str("id")) ?: return false
                applyTurnFields(turn, t)
                turn.status = "inProgress"
                // the chat's model right now runs this turn (a model switch comes as thread/settings/updated first)
                if (turn.model == null) turn.model = model
                awaitingStart = false
            }
            "thread/settings/updated" -> {
                p.o("threadSettings").str("model")?.takeIf { it.isNotBlank() }?.let { model = it }
                return false
            }
            "model/rerouted" -> {
                // Codex moved the turn to another model (e.g. the requested one is busy)
                turns[turnId ?: return false]?.let { t -> p.str("toModel")?.let { t.model = it } } ?: return false
            }
            "turn/completed" -> {
                val t = p.o("turn") ?: return false
                val turn = turn(t.str("id")) ?: return false
                applyTurnFields(turn, t)
                if (turn.status == "inProgress") turn.status = "completed"
                finishStreaming(turn, false)
                // a review's inner turn ends with its outer turn (only the outer one reports turn/completed)
                for (other in turns.values) {
                    if (other.rootTurnId == turn.id && other.status == "inProgress") {
                        other.status = turn.status
                        finishStreaming(other, false)
                    }
                }
            }
            "item/started", "item/completed" -> {
                val o = p.o("item") ?: return false
                val t = turn(turnId) ?: return false
                val id = o.str("id") ?: return false
                val existing = t.items[id]
                if (existing == null) t.items[id] = itemOf(o) else fillFromJson(existing, o)
                val it = t.items[id]!!
                it.streaming = method == "item/started"
                if (it.type == "userMessage") {
                    it.json.str("clientId")?.let { cid -> localPrompts.remove(cid) }
                }
            }
            "item/agentMessage/delta", "item/plan/delta" -> {
                val it = item(turnId, p.str("itemId"), if (method == "item/plan/delta") "plan" else "agentMessage") ?: return false
                it.text.append(p.str("delta").orEmpty())
                it.streaming = true
            }
            "item/reasoning/summaryPartAdded" -> {
                val it = item(turnId, p.str("itemId"), "reasoning") ?: return false
                val i = p.long("summaryIndex")?.toInt() ?: it.summary.size
                while (it.summary.size <= i) it.summary.add(StringBuilder())
            }
            "item/reasoning/summaryTextDelta" -> {
                val it = item(turnId, p.str("itemId"), "reasoning") ?: return false
                val i = p.long("summaryIndex")?.toInt() ?: 0
                while (it.summary.size <= i) it.summary.add(StringBuilder())
                it.summary[i].append(p.str("delta").orEmpty())
                it.streaming = true
            }
            "item/reasoning/textDelta" -> {
                val it = item(turnId, p.str("itemId"), "reasoning") ?: return false
                it.reasoning.append(p.str("delta").orEmpty())
                it.streaming = true
            }
            "item/commandExecution/outputDelta" -> {
                val it = item(turnId, p.str("itemId"), "commandExecution") ?: return false
                it.output.append(p.str("delta").orEmpty())
                if (it.output.length > MAX_OUTPUT) it.output.delete(0, it.output.length - MAX_OUTPUT)
            }
            "item/fileChange/patchUpdated" -> {
                val it = item(turnId, p.str("itemId"), "fileChange") ?: return false
                val changes = p.a("changes") ?: return false
                it.json = JsonObject(it.json + ("changes" to changes))
            }
            "turn/plan/updated" -> {
                val t = turns[turnId ?: return false] ?: return false
                t.plan = p.a("plan").orEmpty().mapNotNull { s -> s.obj()?.let { (it.str("step").orEmpty()) to (it.str("status") ?: "pending") } }
                // the plan card sits where the plan first came
                if (t.items[planKey(t.id)] == null) {
                    t.items[planKey(t.id)] = Item(planKey(t.id), PLAN_TYPE, JsonObject(emptyMap()))
                }
            }
            "thread/tokenUsage/updated" -> {
                // only a turn already known: on resume Codex repeats the last usage of an old turn
                val t = turns[turnId ?: return false] ?: return false
                val usage = p.o("tokenUsage") ?: return false
                val total = usage.o("total") ?: return false
                if (t.usageBase == null) {
                    // the total before this request is the total minus this request
                    val last = usage.o("last")
                    t.usageBase = buildJsonObject {
                        for (k in USAGE_KEYS) put(k, (total.long(k) ?: 0) - (last.long(k) ?: 0))
                    }
                }
                val base = t.usageBase!!
                fun d(k: String) = ((total.long(k) ?: 0) - (base.long(k) ?: 0)).coerceAtLeast(0)
                val input = d("inputTokens")
                val cached = d("cachedInputTokens")
                t.usage = TokenUsage(
                    promptTokens = (input - cached).coerceAtLeast(0).toInt(),
                    outputTokens = d("outputTokens").toInt(),
                    cachedTokens = cached.toInt(),
                    cacheCreationTokens = d("cacheWriteInputTokens").toInt(),
                    totalTokens = d("totalTokens").toInt(),
                    model = t.model ?: model
                )
                return false
            }
            "error" -> {
                if (p.str("willRetry") == "true") return false
                val t = turns[turnId ?: return false] ?: return false
                p.o("error")?.let { t.error = it }
            }
            else -> return false
        }
        return true
    }

    /** Codex's record of each turn (model, token use, duration), which history leaves out. */
    fun applyMeta(meta: Map<String, CodexTurnMeta>) {
        for (t in turns.values) {
            val m = meta[t.id] ?: continue
            m.model?.let { t.model = it }
            m.durationMs?.let { if (t.durationMs == null || t.durationMs == 0L) t.durationMs = it }
            m.usage?.takeIf { it.totalTokens > 0 }?.let { t.usage = it.copy(model = t.model ?: it.model) }
        }
    }

    /** Codex says the thread is idle: nothing can still be running (a lost turn/completed would otherwise spin forever). */
    fun settleAll() {
        awaitingStart = false
        for (t in turns.values) if (t.status == "inProgress") {
            t.status = "completed"
            finishStreaming(t, false)
        }
    }

    /** The turn holding item [itemId] (a chat message id: a user message, or the first item of a reply). */
    fun turnOfItem(itemId: String): Turn? {
        val raw = itemId.removePrefix("turn_")
        turns[raw]?.let { return it }
        return turns.values.firstOrNull { t -> t.items.containsKey(itemId) }
    }

    /** The text of the first user message of [turn] (what was asked). */
    fun promptOf(turn: Turn): String? = turn.items.values.firstOrNull { it.type == "userMessage" }?.let { item ->
        item.json.a("content").orEmpty().mapNotNull { it.obj() }.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
    }

    /** The ids of the turns, oldest first. */
    fun turnIds(): List<String> = turns.keys.toList()

    /** What was asked in each turn, oldest first (as sent: hidden blocks included). */
    fun prompts(): List<String> = turns.values.mapNotNull { promptOf(it) }

    /** The last plan Codex proposed (plan mode), if the newest finished turn has one. */
    val lastPlan: String?
        get() = turns.values.lastOrNull { it.status != "inProgress" }?.items?.values?.lastOrNull { it.type == "plan" }?.text?.toString()?.takeIf { it.isNotBlank() }

    private fun finishStreaming(t: Turn, running: Boolean) {
        for (it in t.items.values) {
            if (!running) it.streaming = false
        }
    }

    // ------------------------------------------------------------------ output

    /**
     * The chat as messages. [awaiting]: item ids waiting for an approval; [question]: a question Codex is waiting on
     * (shown as a choice card at the end of its turn).
     */
    fun toChatMessages(conversationId: String, awaiting: Set<String>, question: Question?): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        val group = mutableListOf<Item>()
        var groupTurn: Turn? = null
        fun flush(turnEnd: Boolean) {
            val t = groupTurn
            if (t != null && (group.isNotEmpty() || (turnEnd && (t.error != null || (question != null && question.turnId == t.id))))) {
                out.add(assistantMessage(conversationId, t, group.toList(), turnEnd, awaiting, question))
            }
            group.clear()
        }
        for (t in turns.values) {
            groupTurn = t
            for (item in t.items.values) {
                when (item.type) {
                    "userMessage" -> {
                        flush(false)
                        out.add(userMessage(conversationId, item, t))
                    }
                    "contextCompaction" -> {
                        flush(false)
                        out.add(ChatMessage(id = item.id, conversationId = conversationId, role = MessageRole.SUMMARY,
                            content = "The conversation was compacted.", contextSummary = "The conversation was compacted.",
                            createdAt = item.startedAt ?: t.startedAt ?: 0L))
                    }
                    else -> group.add(item)
                }
            }
            flush(true)
        }
        for (lp in localPrompts.values) {
            val (text, files) = com.example.gemini.data.files.AttachedFiles.extract(lp.text, "local_" + lp.clientId)
            out.add(ChatMessage(id = "local_" + lp.clientId, conversationId = conversationId, role = MessageRole.USER,
                content = text + attachmentsSuffix(lp.attachmentNames), attachments = lp.images + files, createdAt = lp.created))
        }
        for ((at, e) in errorCards) {
            out.add(ChatMessage(id = "err_$at", conversationId = conversationId, role = MessageRole.ASSISTANT,
                content = errorMarker(e.first, e.second, null), createdAt = at))
        }
        return out
    }

    private fun attachmentsSuffix(names: List<String>) = names.takeIf { it.isNotEmpty() }?.let { "\n\n📎 " + it.joinToString(", ") }.orEmpty()

    private fun userMessage(conversationId: String, item: Item, t: Turn): ChatMessage {
        val parts = item.json.a("content").orEmpty().mapNotNull { it.obj() }
        // files sent by path are listed in the message's hidden text: shown as cards
        val (text, files) = com.example.gemini.data.files.AttachedFiles.extract(
            parts.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }, item.id
        )
        // images show as previews: a localImage is a file on the bridge machine, an image a data URL
        val images = parts.mapIndexedNotNull { i, p ->
            when (p.str("type")) {
                "localImage" -> p.str("path")?.takeIf { it.isNotBlank() }?.let { path ->
                    com.example.gemini.domain.model.ChatAttachment(
                        id = "${item.id}_img_$i", name = path.substringAfterLast('/'), path = path, isImage = true,
                        url = com.example.gemini.data.files.FileExplorerApi.rawUrl(path),
                        mimeType = com.example.gemini.data.files.AttachedFiles.mimeOf(path)
                    )
                }
                "image" -> p.str("url")?.takeIf { it.startsWith("data:") && it.contains(";base64,") }?.let { url ->
                    com.example.gemini.domain.model.ChatAttachment(
                        id = "${item.id}_img_$i", name = "image", path = "", isImage = true,
                        mimeType = url.substringAfter("data:").substringBefore(';'), base64 = url.substringAfter(";base64,")
                    )
                }
                else -> null
            }
        }
        val names = parts.mapNotNull { p ->
            when (p.str("type")) {
                "localAudio" -> p.str("path")?.substringAfterLast('/')
                "image" -> if (p.str("url")?.startsWith("data:") == true) null else "image"
                "mention" -> "@" + p.str("name").orEmpty()
                "skill" -> "$" + p.str("name").orEmpty()
                else -> null
            }
        }
        return ChatMessage(id = item.id, conversationId = conversationId, role = MessageRole.USER,
            content = text + attachmentsSuffix(names), attachments = images + files, createdAt = item.startedAt ?: t.startedAt ?: 0L)
    }

    private fun assistantMessage(conversationId: String, t: Turn, items: List<Item>, turnEnd: Boolean, awaiting: Set<String>, question: Question?): ChatMessage {
        val content = mutableListOf<String>()
        val thoughts = mutableListOf<String>()
        val tools = mutableListOf<ToolCall>()
        val running = t.status == "inProgress"
        for (item in items) {
            when (item.type) {
                "agentMessage", "plan" -> item.text.toString().trim().takeIf { it.isNotBlank() }?.let { content.add(it) }
                "reasoning" -> {
                    val text = item.summary.joinToString("\n\n") { it.toString().trim() }.trim().ifBlank { item.reasoning.toString().trim() }
                    if (text.isNotBlank()) {
                        val tag = if (item.streaming && running) ":streaming" else ""
                        content.add("<!-- thought$tag -->\n$text\n<!-- /thought -->")
                        thoughts.add(text)
                    }
                }
                "enteredReviewMode" -> content.add("*Reviewing: ${item.json.str("review").orEmpty()}*")
                "exitedReviewMode" -> item.json.str("review")?.takeIf { it.isNotBlank() }?.let { content.add(it) }
                "userMessage", "contextCompaction", "hookPrompt", "functionCallOutput", "sleep" -> Unit
                else -> toolCall(item, t, awaiting, running)?.let { call ->
                    if (tools.none { it.id == call.id }) {
                        tools.add(call)
                        content.add("<!-- tool_call:${call.id} -->")
                    }
                }
            }
        }
        if (turnEnd && question != null && question.turnId == t.id) {
            questionnaireOf(question)?.let { q ->
                val id = questionToolId(question.key)
                tools.add(ToolCall(id = id, name = "question", toolType = ToolType.ASK_CHOICE, command = q.title,
                    status = "AWAITING_CHOICE", interactionType = "codex", questionnaire = q))
                content.add("<!-- tool_call:$id -->")
            }
        }
        if (turnEnd) t.error?.let { e -> content.add(errorMarker(errorTitle(e), e.str("message").orEmpty(), e.str("additionalDetails"))) }
        val streaming = running && turnEnd
        val started = items.firstOrNull()?.startedAt ?: t.startedAt ?: 0L
        val usage = t.usage
        val turnModel = t.model ?: model
        return ChatMessage(
            id = items.firstOrNull()?.id ?: "turn_${t.id}",
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = content.joinToString("\n\n").trim(),
            thoughtText = thoughts.joinToString("\n\n").trim().takeIf { it.isNotBlank() },
            toolCalls = tools,
            isStreaming = streaming,
            tokenUsage = if (!turnEnd || running || usage == null || usage.totalTokens == 0) null
            else usage.copy(durationMs = t.durationMs ?: usage.durationMs, model = turnModel),
            createdAt = started,
            modelId = turnModel
        )
    }

    private fun toolCall(item: Item, t: Turn, awaiting: Set<String>, turnRunning: Boolean): ToolCall? {
        val j = item.json
        val pendingApproval = item.id in awaiting
        val duration = j.long("durationMs") ?: item.startedAt?.let { s -> item.completedAt?.let { e -> (e - s).coerceAtLeast(0) } }
        fun status(raw: String?, ok: Boolean = true): String = when {
            pendingApproval -> "PENDING_APPROVAL"
            raw == "inProgress" -> if (turnRunning) "RUNNING" else "TERMINATED"
            raw == "declined" -> "REJECTED"
            raw == "failed" -> "FAILED"
            raw == "completed" -> if (ok) "SUCCESS" else "FAILED"
            else -> if (turnRunning) "RUNNING" else "SUCCESS"
        }
        val id = safeId(item.id)
        return when (item.type) {
            "commandExecution" -> {
                val actions = j.a("commandActions").orEmpty().mapNotNull { it.obj() }
                val single = actions.singleOrNull()
                val type = when (single?.str("type")) {
                    "read" -> ToolType.VIEW_FILE
                    "listFiles" -> ToolType.LIST_DIR
                    "search" -> ToolType.GREP_SEARCH
                    else -> ToolType.BASH
                }
                val command = actions.mapNotNull { it.str("command") }.joinToString(" && ").ifBlank { unwrapShell(j.str("command").orEmpty()) }
                val exit = j.long("exitCode")?.toInt()
                ToolCall(id = id, name = "shell", toolType = type, command = command,
                    status = status(j.str("status"), ok = exit == null || exit == 0),
                    output = item.output.toString(), exitCode = exit, durationMs = duration, interactionType = "codex")
            }
            "fileChange" -> {
                val changes = j.a("changes").orEmpty().mapNotNull { it.obj() }
                ToolCall(id = id, name = "apply_patch", toolType = ToolType.EDIT_FILE,
                    command = changes.mapNotNull { it.str("path")?.substringAfterLast('/') }.distinct().joinToString(", ").ifBlank { "Edit files" },
                    status = status(j.str("status")), output = changes.joinToString("\n") { diffOf(it) },
                    durationMs = duration, interactionType = "codex")
            }
            "mcpToolCall" -> {
                val result = j.o("result")
                val err = j.o("error").str("message")
                val text = result.a("content").orEmpty().mapNotNull { c -> c.obj()?.takeIf { it.str("type") == "text" }?.str("text") }.joinToString("\n")
                ToolCall(id = id, name = "${j.str("server").orEmpty()}_${j.str("tool").orEmpty()}", toolType = ToolType.MCP,
                    command = "${j.str("server").orEmpty()} · ${j.str("tool").orEmpty()}",
                    status = status(j.str("status"), ok = err == null), output = listOfNotNull(text.ifBlank { null }, err).joinToString("\n"),
                    durationMs = duration, interactionType = "codex")
            }
            "dynamicToolCall" -> ToolCall(id = id, name = j.str("tool") ?: "tool", toolType = ToolType.UNKNOWN,
                command = j.str("tool").orEmpty(), status = status(j.str("status"), ok = j["success"]?.toString() != "false"),
                durationMs = duration, interactionType = "codex")
            "webSearch" -> ToolCall(id = id, name = "web_search", toolType = ToolType.SEARCH_WEB,
                command = j.str("query")?.ifBlank { null } ?: j.o("action").str("query") ?: j.o("action").str("url") ?: "Web search",
                status = if (turnRunning && item.streaming) "RUNNING" else "SUCCESS", interactionType = "codex")
            "imageView" -> ToolCall(id = id, name = "view_image", toolType = ToolType.VIEW_FILE,
                command = j.str("path")?.substringAfterLast('/') ?: "Image", status = "SUCCESS", interactionType = "codex")
            "imageGeneration" -> ToolCall(id = id, name = "image_generation", toolType = ToolType.GENERATE_IMAGE,
                command = j.str("revisedPrompt") ?: "Generate an image",
                status = if (j.o("failure") != null) "FAILED" else status(j.str("status")),
                output = j.str("savedPath").orEmpty(), interactionType = "codex")
            "collabAgentToolCall", "subAgentActivity" -> ToolCall(id = id, name = "subagent", toolType = ToolType.UNKNOWN,
                command = j.str("prompt")?.take(80) ?: j.str("agentPath") ?: "Sub-agent", status = status(j.str("status")),
                interactionType = "codex")
            PLAN_TYPE -> {
                val plan = t.plan ?: return null
                val done = plan.count { it.second == "completed" }
                ToolCall(id = id, name = "update_plan", toolType = ToolType.UNKNOWN, command = "Plan ($done/${plan.size} done)",
                    status = if (turnRunning && done < plan.size) "RUNNING" else "SUCCESS",
                    output = plan.joinToString("\n") { (step, st) -> "${when (st) { "completed" -> "☑"; "inProgress" -> "◐"; else -> "☐" }} $step" },
                    interactionType = "codex")
            }
            else -> null
        }
    }

    private fun questionnaireOf(q: Question): ChoiceQuestionnaire? {
        val questions = q.questions.mapNotNull { o ->
            val qid = o.str("id") ?: return@mapNotNull null
            val opts = o.a("options").orEmpty().mapNotNull { it.obj() }
            ChoiceQuestion(
                id = qid,
                prompt = o.str("question") ?: o.str("header") ?: qid,
                description = o.str("header")?.takeIf { it != o.str("question") },
                options = opts.mapNotNull { op -> op.str("label")?.let { ChoiceOption(id = it, label = it, description = op.str("description")) } },
                allowCustomAnswer = o["isOther"]?.toString() == "true" || opts.isEmpty()
            )
        }
        if (questions.isEmpty()) return null
        return ChoiceQuestionnaire(title = "Codex has a question", questions = questions, allowCustomNote = false)
    }

    companion object {
        private const val MAX_OUTPUT = 200_000
        private const val PLAN_TYPE = "__plan"
        private val USAGE_KEYS = listOf("totalTokens", "inputTokens", "cachedInputTokens", "cacheWriteInputTokens", "outputTokens", "reasoningOutputTokens")
        private val UNSAFE_ID = Regex("[^A-Za-z0-9_-]")

        /** Ids as the chat renderer's markers accept them. */
        fun safeId(raw: String): String = raw.replace(UNSAFE_ID, "_")

        fun questionToolId(key: String) = "codexq_" + safeId(key)

        private fun planKey(turnId: String) = "plan_$turnId"

        /** `/bin/bash -lc "cmd"` → `cmd`. */
        fun unwrapShell(command: String): String {
            val m = Regex("^\\S*(?:ba|z)?sh\\s+-l?c\\s+(['\"])(.*)\\1\\s*$", RegexOption.DOT_MATCHES_ALL).find(command.trim())
            return m?.groupValues?.get(2) ?: command
        }

        /** One file change as the edit card shows it: added lines "+", removed "-", hunks "@@ … @@". */
        fun diffOf(change: JsonObject): String {
            val diff = change.str("diff").orEmpty()
            return when (change.o("kind").str("type")) {
                "add" -> diff.trimEnd('\n').lines().joinToString("\n") { "+$it" }
                "delete" -> diff.trimEnd('\n').lines().joinToString("\n") { "-$it" }
                else -> diff.lines().filterNot { it.startsWith("--- ") || it.startsWith("+++ ") || it.startsWith("diff --git") || it.startsWith("index ") }
                    .joinToString("\n").trimEnd('\n')
            }
        }

        fun errorTitle(e: JsonObject): String {
            val info = e["codexErrorInfo"]
            val kind = (info as? JsonPrimitive)?.contentOrNull ?: (info as? JsonObject)?.keys?.firstOrNull().orEmpty()
            return when (kind) {
                "usageLimitExceeded" -> "Usage limit reached"
                "unauthorized" -> "Not signed in to Codex"
                "contextWindowExceeded" -> "The conversation is too long"
                "rateLimitExceeded", "serverOverloaded" -> "Codex is busy"
                "modelNotFound" -> "Model not available"
                "sandboxError" -> "Sandbox error"
                "httpConnectionFailed", "responseStreamConnectionFailed", "responseStreamDisconnected" -> "Connection problem"
                else -> "Codex error"
            }
        }

        fun errorMarker(title: String, message: String, details: String?): String {
            val err = buildJsonObject {
                put("title", title)
                put("userMessage", message)
                put("shortError", message)
                put("fullError", details.orEmpty())
            }
            return "<!-- error -->\n${CodexJson.encodeToString(JsonObject.serializer(), err)}\n<!-- /error -->"
        }
    }
}
