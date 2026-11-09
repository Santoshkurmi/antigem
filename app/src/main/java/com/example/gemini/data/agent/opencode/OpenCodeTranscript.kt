package com.example.gemini.data.agent.opencode

import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChoiceOption
import com.example.gemini.domain.model.ChoiceQuestion
import com.example.gemini.domain.model.ChoiceQuestionnaire
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val OpenCodeJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; isLenient = true }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull ?: (this?.get(key) as? JsonPrimitive)?.doubleOrNull?.toLong()
internal fun JsonObject?.dbl(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject?.o(key: String): JsonObject? = this?.get(key) as? JsonObject
internal fun JsonObject?.a(key: String): JsonArray? = this?.get(key) as? JsonArray

/**
 * One OpenCode chat as the app shows it. OpenCode's v2 messages (`GET /session/{id}/message`) and its live events
 * (`/event`) describe the same thing — user prompts, assistant steps made of text / reasoning / tool parts — so both
 * are folded into the same model here and rendered into [ChatMessage]s. Consecutive assistant steps after a prompt
 * become one assistant message (like a Claude turn), with their token usage summed.
 *
 * Not thread safe: the backend only touches it on its single worker.
 */
class OpenCodeTranscript(var sessionId: String = "") {

    class Part(val key: String, val kind: Kind) {
        enum class Kind { TEXT, REASONING, TOOL }
        val text = StringBuilder()
        var streaming = false
        // tool
        var toolName: String = ""
        var toolStatus: String = "RUNNING"
        var toolInput: JsonObject = JsonObject(emptyMap())
        var toolOutput: String = ""
        var toolError: String? = null
        var startedAt: Long? = null
        var endedAt: Long? = null
        var exitCode: Int? = null
        /** Waiting for a permission reply (status shown as such; the request itself is in [pendingPermissions]). */
        var awaitingPermission = false
    }

    class Msg(val id: String, val role: String, var created: Long) {
        // user
        var text: String = ""
        var attachmentNames: List<String> = emptyList()
        /** Images sent with the prompt, shown as previews (OpenCode keeps their data with the message). */
        var images: List<com.example.gemini.domain.model.ChatAttachment> = emptyList()
        // assistant
        val parts = mutableListOf<Part>()
        var model: String? = null
        var agent: String? = null
        var inputTokens = 0L
        var outputTokens = 0L
        var reasoningTokens = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var cost = 0.0
        var completed: Long? = null
        var finish: String? = null
        var error: String? = null
        var errorTitle: String? = null
        /** The provider rejected the account (expired sign-in, invalid key): the card offers to reconnect. */
        var errorAuth = false
        /** The user message was sent from the app and is not confirmed by OpenCode yet. */
        var pending = false
        /** Made in the app (a prompt just sent, an error card): kept until the stored history has it. */
        var local = false
    }

    class Permission(
        val id: String,
        val sessionId: String,
        val action: String,
        val resources: List<String>,
        val save: List<String>,
        val toolCallId: String?,
        val messageId: String?,
        val metadata: JsonObject
    )

    class Form(val id: String, val sessionId: String, val title: String, val fields: List<JsonObject>) {
        /** Question prompt → field key (the question panel answers by prompt text). */
        val keyByPrompt = HashMap<String, String>()
    }

    private val messages = LinkedHashMap<String, Msg>()
    val pendingPermissions = LinkedHashMap<String, Permission>()
    val pendingForms = LinkedHashMap<String, Form>()

    /** A turn is running (OpenCode's execution.started … succeeded / failed / interrupted). */
    var isTurnActive = false
        private set

    /** Last retry notice while the provider is busy ("Retrying (attempt 3): overloaded"). */
    var retryNotice: String? = null
        private set
    var compacting = false
        private set
    var lastTitle: String? = null
        private set

    fun reset(id: String) {
        sessionId = id
        messages.clear()
        pendingPermissions.clear()
        pendingForms.clear()
        isTurnActive = false
        retryNotice = null
        compacting = false
        lastTitle = null
    }

    val isEmpty get() = messages.isEmpty()

    // ------------------------------------------------------------------ history

    /** Replaces everything with the stored messages ([items] newest or oldest first, both work). */
    fun loadHistory(items: List<JsonObject>, active: Boolean) {
        // prompts sent a moment ago (and the app's own error cards) that the stored list does not show yet stay visible:
        // without this a prompt sent while the previous turn's history was loading vanished, and its reply was glued
        // to the answer before it
        val storedIds = items.mapNotNull { it.str("id") }.toHashSet()
        val keep = messages.values.filter { (it.local || it.pending) && it.id !in storedIds }
        messages.clear()
        val sorted = items.sortedWith(compareBy({ it.o("time").long("created") ?: 0L }, { it.str("id") ?: "" }))
        for (m in sorted) applyStored(m)
        if (keep.isNotEmpty()) {
            val merged = (messages.values + keep).sortedBy { it.created }
            messages.clear()
            for (m in merged) messages[m.id] = m
        }
        isTurnActive = active
        if (!active) {
            for (msg in messages.values) for (part in msg.parts) {
                part.streaming = false
                if (part.kind == Part.Kind.TOOL && part.toolStatus == "RUNNING") part.toolStatus = "TERMINATED"
            }
        }
    }

    private fun applyStored(m: JsonObject) {
        val id = m.str("id") ?: return
        val created = m.o("time").long("created") ?: 0L
        when (m.str("type")) {
            "user" -> {
                val msg = Msg(id, "user", created)
                msg.text = m.str("text") ?: m.o("payload").str("text") ?: ""
                val files = (m.a("files") ?: m.o("payload").a("files")).orEmpty().mapNotNull { it.obj() }
                // a stored file is {data (base64), mime, source, name}: images become previews, the rest a name
                val images = files.mapIndexedNotNull { i, f ->
                    val mime = f.str("mime").orEmpty()
                    val data = f.str("data").orEmpty()
                    if (!mime.startsWith("image/") || data.isBlank()) null
                    else com.example.gemini.domain.model.ChatAttachment(
                        id = "${id}_img_$i", name = f.str("name") ?: "image", path = "", isImage = true, mimeType = mime, base64 = data
                    )
                }
                msg.images = images
                msg.attachmentNames = files.filter { f -> !(f.str("mime").orEmpty().startsWith("image/") && !f.str("data").isNullOrBlank()) }
                    .mapNotNull { it.str("name") ?: it.str("uri")?.substringAfterLast('/') }
                messages[id] = msg
            }
            "assistant" -> {
                val msg = Msg(id, "assistant", created)
                msg.model = modelRef(m.o("model"))
                msg.agent = m.str("agent")
                msg.completed = m.o("time").long("completed")
                msg.finish = m.str("finish")
                applyTokens(msg, m.o("tokens"), m.dbl("cost"))
                m.o("error")?.let { setError(msg, it) }
                var textN = 0
                var reasoningN = 0
                for (c in m.a("content").orEmpty()) {
                    val co = c.obj() ?: continue
                    when (co.str("type")) {
                        "text" -> Part("text:${textN++}", Part.Kind.TEXT).also { it.text.append(co.str("text").orEmpty()); msg.parts.add(it) }
                        "reasoning" -> Part("reasoning:${reasoningN++}", Part.Kind.REASONING).also { it.text.append(co.str("text").orEmpty()); msg.parts.add(it) }
                        "tool" -> {
                            val p = Part("tool:${co.str("id")}", Part.Kind.TOOL)
                            p.toolName = co.str("name").orEmpty()
                            val state = co.o("state")
                            state.o("input")?.let { p.toolInput = it }
                            p.toolOutput = contentText(state.a("content"))
                            p.toolStatus = when (state.str("status")) {
                                "completed" -> "SUCCESS"
                                "error", "failed" -> "FAILED"
                                else -> "RUNNING"
                            }
                            state.o("error")?.let { p.toolError = it.str("message"); if (p.toolStatus == "RUNNING") p.toolStatus = "FAILED" }
                            state.o("metadata").long("exit")?.let { p.exitCode = it.toInt() }
                            p.startedAt = co.o("time").long("ran") ?: co.o("time").long("created")
                            p.endedAt = co.o("time").long("completed")
                            msg.parts.add(p)
                        }
                    }
                }
                messages[id] = msg
            }
            "idle" -> {
                // a failed run leaves its error on the idle marker
                if (m.str("outcome") == "failed") {
                    m.o("error")?.let { err -> lastAssistant()?.let { setError(it, err) } }
                }
            }
            "compaction", "summary" -> {
                val msg = Msg(id, "summary", created)
                msg.text = m.str("text") ?: m.str("summary") ?: "The conversation was compacted."
                messages[id] = msg
            }
        }
    }

    // ------------------------------------------------------------------ live events

    /** The prompt was sent from the app: shown at once (OpenCode confirms it with the same id). */
    fun addLocalPrompt(id: String, text: String, attachmentNames: List<String>, images: List<com.example.gemini.domain.model.ChatAttachment> = emptyList()) {
        val msg = Msg(id, "user", System.currentTimeMillis())
        msg.text = text
        msg.attachmentNames = attachmentNames
        msg.images = images
        msg.pending = true
        msg.local = true
        messages[id] = msg
        isTurnActive = true
        retries = 0
    }

    /** Retries OpenCode scheduled in the current turn (provider busy / rate limited). */
    var retries = 0
        private set

    /** The prompt the assistant message [assistantId] answers (the user message right before its turn). */
    fun promptBefore(assistantId: String): Msg? {
        var lastUser: Msg? = null
        for (m in messages.values) {
            if (m.id == assistantId) return lastUser
            if (m.role == "user") lastUser = m
        }
        return null
    }

    fun userMessage(id: String): Msg? = messages[id]?.takeIf { it.role == "user" }

    /** The user's prompts in this chat, oldest first (as sent: hidden blocks included). */
    fun userPrompts(): List<String> = messages.values.filter { it.role == "user" }.map { it.text }

    /**
     * The turn waits for the model itself: no command or tool is running, nothing waits for the user. Only then does
     * a long silence mean the provider is stuck.
     */
    val isWaitingOnModel: Boolean
        get() = isTurnActive && pendingPermissions.isEmpty() && pendingForms.isEmpty() && !compacting &&
            messages.values.none { m -> m.parts.any { it.kind == Part.Kind.TOOL && it.toolStatus == "RUNNING" && !it.awaitingPermission } }

    fun setNotice(text: String?) { retryNotice = text }

    fun removeLocalPrompt(id: String) {
        if (messages[id]?.pending == true) messages.remove(id)
    }

    /** Applies one event of this session; returns true when something visible changed. */
    fun apply(type: String, d: JsonObject): Boolean {
        when (type) {
            "session.execution.started" -> { isTurnActive = true; retryNotice = null; retries = 0 }
            "session.execution.succeeded", "session.idle" -> endTurn(null)
            "session.execution.interrupted" -> endTurn(null)
            "session.execution.failed" -> endTurn(d.o("error"))
            "session.inbox.enqueued" -> {
                val id = d.str("inboxID") ?: return false
                val item = d.o("item")
                if (item.str("type") != "user") return false
                val existing = messages[id]
                if (existing != null) {
                    existing.pending = false
                    return false
                }
                val msg = Msg(id, "user", System.currentTimeMillis())
                msg.text = item.o("payload").str("text").orEmpty()
                messages[id] = msg
            }
            "session.inbox.delivered" -> messages[d.str("inboxID")]?.pending = false
            "session.inbox.cancelled" -> messages.remove(d.str("inboxID"))
            "session.step.started" -> {
                isTurnActive = true
                retryNotice = null
                val msg = assistant(d.str("assistantMessageID") ?: return false, d.long("started"))
                msg.model = modelRef(d.o("model")) ?: msg.model
                msg.agent = d.str("agent") ?: msg.agent
            }
            "session.step.ended" -> {
                val msg = messages[d.str("assistantMessageID")] ?: return false
                msg.finish = d.str("finish")
                msg.completed = System.currentTimeMillis()
                applyTokens(msg, d.o("tokens"), d.dbl("cost"))
                msg.parts.forEach { it.streaming = false }
            }
            "session.step.failed" -> {
                val msg = messages[d.str("assistantMessageID")] ?: return false
                d.o("error")?.let { setError(msg, it) }
                msg.parts.forEach { it.streaming = false }
            }
            "session.text.started", "session.text.delta", "session.text.ended",
            "session.reasoning.started", "session.reasoning.delta", "session.reasoning.ended" -> {
                val msg = assistant(d.str("assistantMessageID") ?: return false, null)
                val reasoning = type.startsWith("session.reasoning")
                val key = (if (reasoning) "reasoning:" else "text:") + (d.long("ordinal") ?: 0L)
                val part = msg.parts.find { it.key == key }
                    ?: Part(key, if (reasoning) Part.Kind.REASONING else Part.Kind.TEXT).also { msg.parts.add(it) }
                when {
                    type.endsWith(".started") -> part.streaming = true
                    type.endsWith(".delta") -> { part.text.append(d.str("delta").orEmpty()); part.streaming = true }
                    else -> {
                        d.str("text")?.let { part.text.setLength(0); part.text.append(it) }
                        part.streaming = false
                    }
                }
            }
            "session.tool.input.started", "session.tool.input.ended", "session.tool.called",
            "session.tool.progress", "session.tool.success", "session.tool.failed" -> {
                val msg = assistant(d.str("assistantMessageID") ?: return false, null)
                val toolId = d.str("id") ?: return false
                val key = "tool:$toolId"
                val part = msg.parts.find { it.key == key } ?: Part(key, Part.Kind.TOOL).also { msg.parts.add(it) }
                d.str("name")?.let { part.toolName = it }
                when (type) {
                    "session.tool.input.started" -> { part.toolStatus = "RUNNING"; part.startedAt = System.currentTimeMillis() }
                    "session.tool.input.ended" -> d.str("text")?.let { t ->
                        runCatching { OpenCodeJson.parseToJsonElement(t) }.getOrNull().obj()?.let { part.toolInput = it }
                    }
                    "session.tool.called" -> d.o("input")?.let { part.toolInput = it }
                    "session.tool.progress" -> d.o("metadata").let { md ->
                        (md.str("output") ?: md.str("stdout"))?.let { part.toolOutput = it }
                    }
                    "session.tool.success" -> {
                        part.toolStatus = "SUCCESS"
                        part.awaitingPermission = false
                        part.toolOutput = contentText(d.a("content")).ifBlank { part.toolOutput }
                        d.o("metadata").long("exit")?.let { part.exitCode = it.toInt() }
                        part.endedAt = System.currentTimeMillis()
                    }
                    "session.tool.failed" -> {
                        part.toolStatus = "FAILED"
                        part.awaitingPermission = false
                        part.toolError = d.o("error").str("message")
                        part.toolOutput = contentText(d.a("content")).ifBlank { part.toolOutput }
                        part.endedAt = System.currentTimeMillis()
                    }
                }
            }
            "session.retry.scheduled" -> {
                // "attempt" is the attempt about to run: attempt 2 is the first retry
                retries = d.long("attempt")?.let { (it - 1).toInt() } ?: (retries + 1)
                val why = d.o("error").str("message").orEmpty().take(160)
                retryNotice = "The provider is busy — retry $retries of $MAX_RETRIES" + if (why.isNotBlank()) ": $why" else ""
                lastRetryError = why
            }
            "session.compaction.started" -> compacting = true
            "session.compaction.ended", "session.compaction.failed" -> compacting = false
            "session.renamed" -> lastTitle = d.str("title")
            "permission.asked" -> {
                val id = d.str("id") ?: return false
                val src = d.o("source")
                val perm = Permission(
                    id = id,
                    sessionId = d.str("sessionID") ?: sessionId,
                    action = d.str("action").orEmpty(),
                    resources = d.a("resources").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    save = d.a("save").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    toolCallId = src.str("id"),
                    messageId = src.str("messageID"),
                    metadata = d.o("metadata") ?: JsonObject(emptyMap())
                )
                pendingPermissions[id] = perm
                findTool(perm.toolCallId)?.awaitingPermission = true
            }
            "permission.replied" -> {
                val perm = pendingPermissions.remove(d.str("requestID")) ?: return false
                findTool(perm.toolCallId)?.let {
                    it.awaitingPermission = false
                    if (d.str("reply") == "reject") { it.toolStatus = "REJECTED"; it.endedAt = System.currentTimeMillis() }
                }
            }
            "form.created" -> {
                val f = d.o("form") ?: return false
                val id = f.str("id") ?: return false
                pendingForms[id] = Form(id, f.str("sessionID") ?: sessionId, f.str("title").orEmpty(), f.a("fields").orEmpty().mapNotNull { it.obj() })
            }
            "form.replied", "form.cancelled" -> pendingForms.remove(d.str("formID") ?: d.str("id") ?: d.o("form").str("id"))
            else -> return false
        }
        return true
    }

    fun addPermission(p: Permission) {
        pendingPermissions[p.id] = p
        findTool(p.toolCallId)?.awaitingPermission = true
    }

    fun addForm(f: Form) { pendingForms[f.id] = f }

    fun removePermission(id: String, rejected: Boolean) {
        val perm = pendingPermissions.remove(id) ?: return
        findTool(perm.toolCallId)?.let {
            it.awaitingPermission = false
            if (rejected) { it.toolStatus = "REJECTED"; it.endedAt = System.currentTimeMillis() }
        }
    }

    fun removeForm(id: String) { pendingForms.remove(id) }

    /** The last retry's reason (shown when the app gives up). */
    var lastRetryError: String = ""
        private set

    /** A failure the app found itself (could not send, gave up after retries, no answer): an error card at the end. */
    fun addError(title: String, message: String) {
        val id = "err_" + System.nanoTime()
        val msg = Msg(id, "assistant", System.currentTimeMillis())
        msg.errorTitle = title
        msg.error = message
        msg.local = true
        messages[id] = msg
    }

    private fun endTurn(error: JsonObject?) {
        isTurnActive = false
        retryNotice = null
        compacting = false
        for (msg in messages.values) {
            msg.pending = false
            for (p in msg.parts) {
                p.streaming = false
                if (p.kind == Part.Kind.TOOL && p.toolStatus == "RUNNING") p.toolStatus = "TERMINATED"
            }
        }
        if (error != null) {
            val target = lastAssistant()
            if (target != null && target.error == null) setError(target, error) else if (target == null) {
                addError(errorTitle(error), error.str("message").orEmpty())
            }
        }
    }

    private fun assistant(id: String, started: Long?): Msg =
        messages[id] ?: Msg(id, "assistant", started ?: System.currentTimeMillis()).also { messages[id] = it }

    private fun lastAssistant(): Msg? = messages.values.lastOrNull { it.role == "assistant" }

    private fun findTool(toolId: String?): Part? {
        if (toolId == null) return null
        val key = "tool:$toolId"
        for (msg in messages.values.reversed()) msg.parts.find { it.key == key }?.let { return it }
        return null
    }

    private fun applyTokens(msg: Msg, t: JsonObject?, cost: Double?) {
        if (t == null) return
        msg.inputTokens = t.long("input") ?: 0
        msg.outputTokens = t.long("output") ?: 0
        msg.reasoningTokens = t.long("reasoning") ?: 0
        msg.cacheRead = t.o("cache").long("read") ?: 0
        msg.cacheWrite = t.o("cache").long("write") ?: 0
        if (cost != null) msg.cost = cost
    }

    private fun setError(msg: Msg, err: JsonObject) {
        msg.errorTitle = errorTitle(err)
        msg.error = err.str("message") ?: err.toString()
        msg.errorAuth = isAuthError(err)
    }

    private fun isAuthError(err: JsonObject): Boolean {
        val type = err.str("type").orEmpty().lowercase()
        val status = err.long("status")
        val text = (err.str("message").orEmpty() + " " + err.o("response").str("body").orEmpty()).lowercase()
        return type.contains("auth") || status == 401L || status == 403L ||
            listOf("unauthorized", "invalid api key", "invalid_api_key", "incorrect api key", "expired", "not signed in", "invalid token", "authentication").any { text.contains(it) }
    }

    private fun errorTitle(err: JsonObject): String = when {
        err.str("type")?.contains("rate-limit") == true -> "Rate limited"
        err.str("type")?.contains("auth") == true -> "Not signed in to the provider"
        err.str("type")?.startsWith("provider") == true -> "Provider error"
        else -> "OpenCode error"
    }

    // ------------------------------------------------------------------ output

    fun toChatMessages(conversationId: String): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        val group = mutableListOf<Msg>()
        // [trailing]: the replies after the last prompt (only those can be the one streaming now)
        fun flush(trailing: Boolean = false) {
            if (group.isNotEmpty()) out.add(assistantMessage(conversationId, group.toList(), trailing))
            group.clear()
        }
        for (m in messages.values) {
            when (m.role) {
                "user" -> {
                    flush()
                    // files sent by path are listed in the message's hidden text: shown as cards
                    val (text, files) = com.example.gemini.data.files.AttachedFiles.extract(m.text, m.id)
                    out.add(
                        ChatMessage(
                            id = m.id,
                            conversationId = conversationId,
                            role = MessageRole.USER,
                            content = text + m.attachmentNames.takeIf { it.isNotEmpty() }?.let { "\n\n📎 " + it.joinToString(", ") }.orEmpty(),
                            attachments = m.images + files,
                            createdAt = m.created
                        )
                    )
                }
                "summary" -> {
                    flush()
                    out.add(ChatMessage(id = m.id, conversationId = conversationId, role = MessageRole.SUMMARY, content = m.text, contextSummary = m.text, createdAt = m.created))
                }
                else -> group.add(m)
            }
        }
        flush(trailing = true)
        // the last assistant block streams while the turn runs
        if (isTurnActive) {
            val i = out.indexOfLast { it.role == MessageRole.ASSISTANT }
            if (i >= 0 && i == out.lastIndex) out[i] = out[i].copy(isStreaming = true, tokenUsage = null)
        }
        return out
    }

    private fun assistantMessage(conversationId: String, steps: List<Msg>, trailing: Boolean): ChatMessage {
        val content = mutableListOf<String>()
        val thoughts = mutableListOf<String>()
        val tools = mutableListOf<ToolCall>()
        var usage = TokenUsage()
        var cost = 0.0
        var model: String? = null
        // only the reply after the newest prompt streams: right after a new prompt (before its reply has a step) the
        // previous reply is finished and keeps its usage card and actions
        val active = isTurnActive && trailing
        for (step in steps) {
            model = step.model ?: model
            for (p in step.parts) {
                when (p.kind) {
                    Part.Kind.REASONING -> {
                        val t = p.text.toString().trim()
                        if (t.isNotBlank()) {
                            val tag = if (p.streaming && active) ":streaming" else ""
                            content.add("<!-- thought$tag -->\n$t\n<!-- /thought -->")
                            thoughts.add(t)
                        }
                    }
                    Part.Kind.TEXT -> p.text.toString().trim().takeIf { it.isNotBlank() }?.let { content.add(it) }
                    Part.Kind.TOOL -> {
                        val call = toolCall(p)
                        if (tools.none { it.id == call.id }) {
                            tools.add(call)
                            content.add("<!-- tool_call:${call.id} -->")
                        }
                    }
                }
            }
            step.error?.let { e ->
                val err = buildJsonObject {
                    put("title", step.errorTitle ?: "OpenCode error")
                    put("userMessage", e)
                    put("shortError", e)
                    put("fullError", "")
                    if (step.errorAuth) put("action", "opencode_providers")
                }
                content.add("<!-- error -->\n${OpenCodeJson.encodeToString(JsonObject.serializer(), err)}\n<!-- /error -->")
            }
            usage = usage.copy(
                promptTokens = usage.promptTokens + step.inputTokens.toInt(),
                outputTokens = usage.outputTokens + step.outputTokens.toInt() + step.reasoningTokens.toInt(),
                cachedTokens = usage.cachedTokens + step.cacheRead.toInt(),
                cacheCreationTokens = usage.cacheCreationTokens + step.cacheWrite.toInt()
            )
            cost += step.cost
        }
        // a question (form) is shown as a choice card at the end of the reply
        for (f in pendingForms.values) {
            val q = questionnaireOf(f) ?: continue
            val id = formToolId(f.id)
            tools.add(
                ToolCall(id = id, name = "question", toolType = ToolType.ASK_CHOICE, command = f.title.ifBlank { "Question" },
                    status = "AWAITING_CHOICE", interactionType = "opencode", questionnaire = q)
            )
            content.add("<!-- tool_call:$id -->")
        }
        val first = steps.first()
        val last = steps.last()
        val started = first.created
        val ended = last.completed
        val total = usage.promptTokens + usage.outputTokens + usage.cachedTokens + usage.cacheCreationTokens
        return ChatMessage(
            id = first.id,
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = content.joinToString("\n\n").trim(),
            thoughtText = thoughts.joinToString("\n\n").trim().takeIf { it.isNotBlank() },
            toolCalls = tools,
            isStreaming = active,
            tokenUsage = if (active || total == 0) null else usage.copy(
                totalTokens = total,
                costUsd = cost.takeIf { it > 0 },
                durationMs = if (ended != null && ended > started) ended - started else 0L,
                model = model
            ),
            createdAt = started,
            modelId = model
        )
    }

    private fun toolCall(p: Part): ToolCall {
        val status = when {
            p.awaitingPermission && p.toolStatus == "RUNNING" -> "PENDING_APPROVAL"
            else -> p.toolStatus
        }
        val output = buildString {
            append(p.toolOutput)
            p.toolError?.takeIf { it.isNotBlank() && !p.toolOutput.contains(it) }?.let { if (isNotEmpty()) append("\n"); append(it) }
            if (isEmpty() && p.toolName.equals("todowrite", true)) append(todoChecklist(p.toolInput))
        }
        return ToolCall(
            id = safeId(p.key.removePrefix("tool:")),
            name = p.toolName.ifBlank { "tool" },
            toolType = toolTypeFor(p.toolName),
            command = describeTool(p.toolName, p.toolInput),
            status = status,
            output = output,
            exitCode = p.exitCode,
            durationMs = p.startedAt?.let { s -> p.endedAt?.let { e -> (e - s).coerceAtLeast(0) } },
            interactionType = "opencode"
        )
    }

    private fun questionnaireOf(f: Form): ChoiceQuestionnaire? {
        val questions = f.fields.mapNotNull { field ->
            if (field.bool("hidden") == true) return@mapNotNull null
            val key = field.str("key") ?: return@mapNotNull null
            val prompt = field.str("title") ?: field.str("description") ?: key
            f.keyByPrompt[prompt] = key
            when (field.str("type")) {
                "multiselect" -> {
                    val max = field.long("maxItems")
                    ChoiceQuestion(
                        id = key,
                        prompt = prompt,
                        description = field.str("description")?.takeIf { it != prompt },
                        isMultiSelect = max == null || max > 1,
                        options = field.a("options").orEmpty().mapNotNull { o ->
                            val oo = o.obj() ?: return@mapNotNull null
                            ChoiceOption(id = oo.str("value") ?: return@mapNotNull null, label = oo.str("label") ?: oo.str("value")!!, description = oo.str("description"))
                        },
                        allowCustomAnswer = field.bool("custom") != false
                    )
                }
                "boolean" -> ChoiceQuestion(
                    id = key, prompt = prompt, description = field.str("description")?.takeIf { it != prompt },
                    options = listOf(ChoiceOption(id = "true", label = "Yes"), ChoiceOption(id = "false", label = "No")),
                    allowCustomAnswer = false
                )
                "string", "number", "integer" -> ChoiceQuestion(
                    id = key, prompt = prompt, description = field.str("description")?.takeIf { it != prompt } ?: field.str("placeholder"),
                    options = emptyList(), allowCustomAnswer = true
                )
                else -> null
            }
        }
        if (questions.isEmpty()) return null
        return ChoiceQuestionnaire(title = f.title.ifBlank { "OpenCode has a question" }, questions = questions, allowCustomNote = false)
    }

    companion object {
        /** Retries of a busy / rate-limited provider before the app stops the turn (OpenCode itself keeps going). */
        const val MAX_RETRIES = 3

        private val UNSAFE_ID = Regex("[^A-Za-z0-9_-]")

        /** Tool ids as the chat renderer's markers accept them (some providers use ':' or '.'). */
        fun safeId(raw: String): String = raw.replace(UNSAFE_ID, "_")

        fun formToolId(formId: String) = "form_" + safeId(formId)

        fun modelRef(m: JsonObject?): String? {
            val id = m.str("id") ?: return null
            val provider = m.str("providerID") ?: return id
            return "$provider/$id"
        }

        fun contentText(content: JsonArray?): String =
            content.orEmpty().mapNotNull { c -> c.obj()?.takeIf { it.str("type") == "text" }?.str("text") }.joinToString("\n").trim()

        fun toolTypeFor(name: String): ToolType = when (name.lowercase()) {
            "shell", "bash", "pty", "terminal" -> ToolType.BASH
            "read", "view" -> ToolType.VIEW_FILE
            "edit", "write", "patch", "apply_patch", "multiedit" -> ToolType.EDIT_FILE
            "glob", "find" -> ToolType.FIND
            "grep", "search", "codesearch" -> ToolType.GREP_SEARCH
            "list", "ls" -> ToolType.LIST_DIR
            "websearch", "web_search" -> ToolType.SEARCH_WEB
            "webfetch", "fetch", "web_fetch" -> ToolType.READ_URL
            "question" -> ToolType.ASK_CHOICE
            else -> if (name.contains("_") && !name.startsWith("todo")) ToolType.MCP else ToolType.UNKNOWN
        }

        fun describeTool(name: String, input: JsonObject): String {
            fun s(vararg keys: String) = keys.firstNotNullOfOrNull { input.str(it)?.takeIf { v -> v.isNotBlank() } }.orEmpty()
            fun file(vararg keys: String) = s(*keys).let { p -> p.substringAfterLast('/').ifBlank { p } }
            return when (name.lowercase()) {
                "shell", "bash" -> s("command", "cmd")
                "read", "view" -> file("path", "filePath", "file_path")
                "edit", "write", "patch", "multiedit" -> file("path", "filePath", "file_path")
                "apply_patch" -> "Apply a patch"
                "glob", "find" -> s("pattern", "glob")
                "grep", "search" -> s("pattern", "query") + s("path").takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
                "list", "ls" -> s("path").ifBlank { "." }
                "websearch", "web_search" -> s("query")
                "webfetch", "fetch" -> s("url")
                "task", "agent", "subagent" -> s("description", "prompt").take(80).ifBlank { "Subagent" }
                "todowrite" -> {
                    val todos = input.a("todos").orEmpty().mapNotNull { it.obj() }
                    "Todo list (${todos.count { it.str("status") == "completed" }}/${todos.size} done)"
                }
                "skill" -> s("name", "id")
                else -> input.entries.firstOrNull { it.value is JsonPrimitive }?.let { (it.value as JsonPrimitive).contentOrNull?.take(80) }.orEmpty()
            }.ifBlank { name }
        }

        fun todoChecklist(input: JsonObject): String =
            input.a("todos").orEmpty().mapNotNull { it.obj() }.joinToString("\n") { t ->
                val mark = when (t.str("status")) {
                    "completed" -> "☑"
                    "in_progress" -> "◐"
                    else -> "☐"
                }
                "$mark ${t.str("content").orEmpty()}"
            }
    }
}
