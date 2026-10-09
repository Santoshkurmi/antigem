package com.example.gemini.data.agent.claude

import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChoiceOption
import com.example.gemini.domain.model.ChoiceQuestion
import com.example.gemini.domain.model.ChoiceQuestionnaire
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Builds the chat feed of one Claude conversation from stream-json events (live) and transcript entries (history),
 * producing the same [ChatMessage] / [ToolCall] shapes the Antigravity engine produces so the chat UI renders both.
 * Also tracks what the chat controls need: permission mode, model, prompt-cache timing, plan limits.
 *
 * Not thread-safe: the owner serializes access.
 */
class ClaudeTranscript {

    /** A permission prompt (`can_use_tool`) waiting for the user. */
    data class PendingPermission(
        val requestId: String,
        val toolUseId: String,
        val toolName: String,
        val input: JsonObject,
        val suggestions: JsonArray?,
        val reason: String?,
        val blockedPath: String?
    )

    /** Prompt-cache state for the "cache warm, N min left" indicator. */
    data class CacheInfo(
        val anchorMs: Long,
        val ttlMs: Long,
        val recacheTokens: Long,
        val compacted: Boolean = false
    )

    private sealed class Turn {
        var version = 0
        var cached: ChatMessage? = null
        var cachedVersion = -1
    }

    private class UserTurn(
        val uuid: String,
        val text: String,
        val attachments: List<ChatAttachment>,
        val createdAt: Long,
        val stepIndex: Int,
        /** Transcript message right before this prompt; rewinding to it drops this prompt and everything after. */
        val parentUuid: String?
    ) : Turn()

    private class SummaryTurn(val id: String, val text: String) : Turn()

    private class AssistantTurn(val id: String) : Turn() {
        val parts = mutableListOf<Part>()
        val partByKey = HashMap<String, Part>()
        val blockCounts = HashMap<String, Int>()
        var isStreaming = false
        var lastMessageUsage: ClaudeUsage? = null
        var resultUsage: TokenUsage? = null
    }

    private sealed class Part {
        class Thinking(val text: StringBuilder = StringBuilder(), var streaming: Boolean = false) : Part()
        class Text(val text: StringBuilder = StringBuilder()) : Part()
        class Tool(val toolUseId: String) : Part()
        class Error(val title: String, val message: String) : Part()
    }

    private class ToolState(
        val id: String,
        var name: String,
        var input: JsonObject?,
        val stepIndex: Int,
        val turn: AssistantTurn
    ) {
        var status = "RUNNING"
        var output = ""
        var progress: String? = null
        var exitCode: Int? = null
        var startedAt = System.currentTimeMillis()
        var durationMs: Long? = null
    }

    var conversationId: String = ""
        private set
    var sessionModel: String? = null
        private set
    var permissionMode: String? = null
        private set
    var cacheInfo: CacheInfo? = null
        private set
    /** Live plan-limit windows from `rate_limit_event` (key → utilization 0..1, resetsAt seconds). */
    var rateLimits: Map<String, ClaudeUnifiedWindow> = emptyMap()
        private set
    /** Cumulative session cost reported by the last `result`. */
    var sessionCostUsd: Double = 0.0
        private set
    /** The last turn failed because the CLI is not signed in. */
    var needsLogin: Boolean = false
        private set

    private val turns = mutableListOf<Turn>()
    private val seenUuids = HashSet<String>()
    private val tools = LinkedHashMap<String, ToolState>()
    private val pending = LinkedHashMap<String, PendingPermission>()
    private val taskToTool = HashMap<String, String>()
    private var activeTurn: AssistantTurn? = null
    private var currentStreamMessageId: String? = null
    private var stepCounter = 0
    private var afterCompactBoundary = false
    private var lastEventUuid: String? = null
    private var lastResultCost = 0.0

    fun reset(convId: String) {
        conversationId = convId
        turns.clear()
        seenUuids.clear()
        tools.clear()
        pending.clear()
        taskToTool.clear()
        activeTurn = null
        currentStreamMessageId = null
        stepCounter = 0
        afterCompactBoundary = false
        lastEventUuid = null
        lastResultCost = 0.0
        sessionModel = null
        permissionMode = null
        cacheInfo = null
        rateLimits = emptyMap()
        sessionCostUsd = 0.0
        needsLogin = false
    }

    val isTurnActive: Boolean get() = activeTurn?.isStreaming == true

    fun pendingFor(toolUseId: String): PendingPermission? = pending[toolUseId]

    fun pendingList(): List<PendingPermission> = pending.values.toList()

    fun hasPending(): Boolean = pending.isNotEmpty()

    /** uuid of the transcript message right before the prompt with [userUuid] (null for the first prompt). */
    fun parentOfUser(userUuid: String): String? =
        turns.filterIsInstance<UserTurn>().firstOrNull { it.uuid == userUuid }?.parentUuid

    /** The prompt text of the user message with [userUuid]. */
    fun userText(userUuid: String): String? =
        turns.filterIsInstance<UserTurn>().firstOrNull { it.uuid == userUuid }?.text

    /** The last user prompt before the assistant message [assistantMessageId]. */
    fun userBefore(assistantMessageId: String): String? {
        val idx = turns.indexOfFirst { it is AssistantTurn && it.id == assistantMessageId }
        if (idx < 0) return null
        return (turns.subList(0, idx).lastOrNull { it is UserTurn } as? UserTurn)?.uuid
    }

    // ------------------------------------------------------------------ local actions

    /** Shows the user's message immediately; the CLI's replay with the same uuid is ignored later. */
    fun addLocalUserMessage(uuid: String, text: String, attachments: List<ChatAttachment>) {
        addUserTurn(uuid, text, attachments, System.currentTimeMillis(), lastEventUuid)
        beginAssistantTurn()
        needsLogin = false
    }

    fun markPermissionAnswered(toolUseId: String, newStatus: String, output: String? = null) {
        pending.remove(toolUseId)
        tools[toolUseId]?.let { tool ->
            tool.status = newStatus
            if (output != null) tool.output = output
            touch(tool.turn)
        }
    }

    fun addError(title: String, message: String) {
        val turn = activeTurn ?: beginAssistantTurn()
        turn.parts.add(Part.Error(title, message))
        turn.isStreaming = false
        touch(turn)
    }

    /** The turn is over without a `result` (process exited, interrupted while disconnected…). */
    fun endTurn() {
        activeTurn?.let {
            it.isStreaming = false
            touch(it)
        }
        pending.clear()
    }

    // ------------------------------------------------------------------ incoming events

    /** Applies one stream-json line (live). Returns the event type for the caller's bookkeeping. */
    fun applyLive(data: JsonObject): String {
        val type = data["type"]?.jsonPrimitive?.contentOrNull ?: return ""
        when (type) {
            "stream_event" -> onStreamEvent(decode(data) ?: return type)
            "assistant" -> onAssistant(decode(data) ?: return type)
            "user" -> onUser(decode(data) ?: return type, live = true)
            "result" -> onResult(decode(data) ?: return type)
            "system" -> onSystem(decode(data) ?: return type)
            "control_request" -> onControlRequest(decode(data) ?: return type)
            "control_cancel_request" -> decode<ClaudeControlCancelEvent>(data)?.let { cancel ->
                val entry = pending.entries.firstOrNull { it.value.requestId == cancel.request_id }
                if (entry != null) markPermissionAnswered(entry.key, "RUNNING")
            }
            "rate_limit_event" -> onRateLimit(data)
        }
        return type
    }

    /** Applies one transcript entry from `/history`. */
    fun applyHistory(entry: JsonObject) {
        when (entry["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> onAssistant(decode(entry) ?: return)
            "user" -> onUser(decode(entry) ?: return, live = false)
            "system" -> {
                val sys = decode<ClaudeSystemEvent>(entry) ?: return
                when (sys.subtype) {
                    "local_command" -> sys.content?.let { addLocalCommandOutput(it) }
                    "compact_boundary" -> {
                        afterCompactBoundary = true
                        cacheInfo = cacheInfo?.copy(compacted = true)
                    }
                }
            }
        }
        entry["uuid"]?.jsonPrimitive?.contentOrNull?.let { lastEventUuid = it }
    }

    /** History is complete: nothing is streaming. */
    fun finishHistory() {
        turns.filterIsInstance<AssistantTurn>().forEach {
            if (it.isStreaming) {
                it.isStreaming = false
                touch(it)
            }
        }
        tools.values.filter { it.status == "RUNNING" || it.status == "PENDING_APPROVAL" || it.status == "AWAITING_CHOICE" }
            .forEach {
                it.status = "TERMINATED"
                touch(it.turn)
            }
        activeTurn = null
    }

    private fun onStreamEvent(e: ClaudeStreamEvent) {
        if (e.parent_tool_use_id != null) return
        val ev = e.event
        when (ev.type) {
            "message_start" -> {
                currentStreamMessageId = ev.message?.id
                ev.message?.usage?.let { recordCache(it, System.currentTimeMillis()) }
                val turn = activeTurn ?: beginAssistantTurn()
                turn.isStreaming = true
                touch(turn)
            }
            "content_block_start" -> {
                val turn = activeTurn ?: beginAssistantTurn()
                val key = blockKey(currentStreamMessageId, ev.index ?: return)
                if (turn.partByKey.containsKey(key)) return
                val block = ev.content_block ?: return
                val part = when (block.type) {
                    "thinking" -> Part.Thinking(streaming = true)
                    "text" -> Part.Text(StringBuilder(block.text.orEmpty()))
                    "tool_use" -> {
                        val id = block.id ?: return
                        ensureTool(id, block.name ?: "tool", block.input, turn)
                        Part.Tool(id)
                    }
                    else -> return
                }
                turn.partByKey[key] = part
                if (part !is Part.Tool || turn.parts.none { it is Part.Tool && it.toolUseId == part.toolUseId }) {
                    turn.parts.add(part)
                }
                touch(turn)
            }
            "content_block_delta" -> {
                val turn = activeTurn ?: return
                val part = turn.partByKey[blockKey(currentStreamMessageId, ev.index ?: return)] ?: return
                val delta = ev.delta ?: return
                when {
                    part is Part.Text && delta.type == "text_delta" -> part.text.append(delta.text.orEmpty())
                    part is Part.Thinking && delta.type == "thinking_delta" -> part.text.append(delta.thinking.orEmpty())
                    else -> return
                }
                touch(turn)
            }
            "content_block_stop" -> {
                val turn = activeTurn ?: return
                val part = turn.partByKey[blockKey(currentStreamMessageId, ev.index ?: return)]
                if (part is Part.Thinking && part.streaming) {
                    part.streaming = false
                    touch(turn)
                }
            }
        }
    }

    private fun onAssistant(e: ClaudeAssistantEvent) {
        if (e.parent_tool_use_id != null || e.isSidechain) return
        // events re-emitted after /compact carry the uuids we already showed
        if (e.uuid != null && !seenUuids.add(e.uuid)) return
        e.uuid?.let { lastEventUuid = it }
        val synthetic = e.message.model == "<synthetic>"
        // a synthetic message outside a turn (e.g. output of a live model switch) is complete on arrival
        val turn = activeTurn ?: beginAssistantTurn().also {
            if (synthetic) {
                it.isStreaming = false
                activeTurn = null
            }
        }
        if (!synthetic) e.message.model?.let { sessionModel = it }
        val msgId = e.message.id ?: e.uuid ?: "msg"
        e.message.usage?.let { u ->
            if (!synthetic) {
                turn.lastMessageUsage = u
                // history: no message_start events, so the cache timer comes from the stored timestamps
                parseTime(e.timestamp)?.let { recordCache(u, it) }
            }
        }
        for (block in e.message.content) {
            val index = turn.blockCounts.getOrDefault(msgId, 0)
            turn.blockCounts[msgId] = index + 1
            val key = blockKey(msgId, index)
            val existing = turn.partByKey[key]
            when (block.type) {
                "thinking" -> {
                    val text = block.thinking.orEmpty()
                    val part = existing as? Part.Thinking ?: Part.Thinking().also { turn.partByKey[key] = it; turn.parts.add(it) }
                    if (text.isNotEmpty()) {
                        part.text.setLength(0)
                        part.text.append(text)
                    }
                    part.streaming = false
                }
                "text" -> {
                    val part = existing as? Part.Text ?: Part.Text().also { turn.partByKey[key] = it; turn.parts.add(it) }
                    part.text.setLength(0)
                    part.text.append(block.text.orEmpty())
                }
                "tool_use" -> {
                    val id = block.id ?: continue
                    ensureTool(id, block.name ?: "tool", block.input, turn)
                    if (existing == null && turn.parts.none { it is Part.Tool && it.toolUseId == id }) {
                        val part = Part.Tool(id)
                        turn.partByKey[key] = part
                        turn.parts.add(part)
                    }
                }
            }
        }
        touch(turn)
    }

    private fun onUser(e: ClaudeUserEvent, live: Boolean) {
        if (e.parent_tool_use_id != null || e.isSidechain) return
        if (e.uuid != null && e.uuid in seenUuids) return
        e.uuid?.let { seenUuids.add(it) }
        val content = e.message.content ?: return
        // transcripts flag the compaction summary; the live stream sends it as a synthetic message after the boundary
        if (e.isCompactSummary || (e.isSynthetic && afterCompactBoundary)) {
            afterCompactBoundary = false
            val text = contentText(content).trim()
            if (text.isNotBlank()) turns.add(SummaryTurn("summary_${e.uuid ?: turns.size}", text))
            e.uuid?.let { lastEventUuid = it }
            return
        }
        val blocks: List<ClaudeContentBlock> = when (content) {
            is JsonPrimitive -> listOf(ClaudeContentBlock(type = "text", text = content.contentOrNull.orEmpty()))
            is JsonArray -> content.mapNotNull { runCatching { ClaudeJson.decodeFromJsonElement<ClaudeContentBlock>(it) }.getOrNull() }
            else -> return
        }

        val toolResults = blocks.filter { it.type == "tool_result" }
        if (toolResults.isNotEmpty()) {
            val structured = e.tool_use_result ?: e.toolUseResult
            for (r in toolResults) applyToolResult(r, if (toolResults.size == 1) structured else null)
            e.uuid?.let { lastEventUuid = it }
            return
        }
        if (e.isMeta) return

        val texts = blocks.filter { it.type == "text" }.mapNotNull { it.text }
        val joined = texts.joinToString("\n").trim()
        if (joined.startsWith("[Request interrupted by user")) {
            addNotice("Interrupted by user")
            endTurn()
            e.uuid?.let { lastEventUuid = it }
            return
        }
        if (joined.contains("<local-command-stdout>") || joined.contains("<local-command-stderr>")) {
            addLocalCommandOutput(joined)
            e.uuid?.let { lastEventUuid = it }
            return
        }
        if (joined.startsWith("<local-command-caveat>")) return

        val uuid = e.uuid ?: "u_${turns.size}"
        // files saved on the bridge are listed in an <attached-files> block: show them as attachments, not text
        val (promptText, savedFiles) = ClaudeAttachments.extract(joined)
        val displayText = commandDisplay(promptText) ?: stripContextTags(promptText)
        val attachments = blocks.mapIndexedNotNull { i, b -> b.toAttachment(uuid, i) } +
            savedFiles.mapIndexed { i, f -> ClaudeAttachments.toChatAttachment(f, "${uuid}_file_$i") }
        if (displayText.isBlank() && attachments.isEmpty()) return
        val createdAt = parseTime(e.timestamp) ?: System.currentTimeMillis()
        addUserTurn(uuid, displayText, attachments, createdAt, e.parentUuid ?: lastEventUuid)
        lastEventUuid = uuid
        if (live) beginAssistantTurn() else activeTurn = null
    }

    private fun onResult(r: ClaudeResultEvent) {
        val turn = activeTurn
        val turnCost = (r.total_cost_usd - lastResultCost).takeIf { it > 0.0 }
        if (r.total_cost_usd > 0.0) {
            lastResultCost = r.total_cost_usd
            sessionCostUsd = r.total_cost_usd
        }
        if (turn != null) {
            turn.isStreaming = false
            r.usage?.toTokenUsage(r.duration_ms, turnCost)?.let { turn.resultUsage = it }
            val interrupted = r.terminal_reason?.startsWith("aborted") == true
            if (r.is_error && !interrupted) {
                val msg = r.errors.joinToString("\n").ifBlank { r.result.orEmpty() }.ifBlank { r.subtype }
                if (msg.contains("/login", ignoreCase = true) || msg.contains("not logged in", ignoreCase = true) ||
                    msg.contains("authentication", ignoreCase = true) || msg.contains("OAuth token", ignoreCase = true)
                ) needsLogin = true
                turn.parts.add(Part.Error(errorTitle(r.subtype, msg), msg))
            }
            touch(turn)
        }
        pending.clear()
        // tools that never got a result in this turn are no longer running
        tools.values.filter { it.turn === turn && (it.status == "RUNNING" || it.status == "PENDING_APPROVAL" || it.status == "AWAITING_CHOICE") }
            .forEach { it.status = "TERMINATED" }
        activeTurn = null
    }

    private fun onSystem(s: ClaudeSystemEvent) {
        when (s.subtype) {
            "init" -> {
                sessionModel = s.model ?: sessionModel
                permissionMode = s.permissionMode ?: permissionMode
            }
            "status" -> s.permissionMode?.let { permissionMode = it }
            "compact_boundary" -> {
                afterCompactBoundary = true
                cacheInfo = cacheInfo?.copy(compacted = true) ?: CacheInfo(System.currentTimeMillis(), 0, 0, compacted = true)
            }
            "permission_denied" -> {
                val text = s.message?.substringBefore(" IMPORTANT:")?.trim().orEmpty()
                if (text.isNotBlank()) addNotice(text)
            }
            "task_started" -> {
                val toolId = s.tool_use_id ?: return
                s.task_id?.let { taskToTool[it] = toolId }
                tools[toolId]?.let {
                    it.progress = "Running: ${s.description.orEmpty()}".trimEnd(':', ' ')
                    touch(it.turn)
                }
            }
            "task_notification" -> {
                val toolId = s.tool_use_id ?: s.task_id?.let { taskToTool[it] } ?: return
                tools[toolId]?.let {
                    it.progress = listOfNotNull(s.status?.replaceFirstChar { c -> c.uppercase() }, s.summary).joinToString(": ")
                    touch(it.turn)
                }
            }
        }
    }

    private fun onRateLimit(data: JsonObject) {
        val windows = (data["rate_limit_info"] as? JsonObject)?.get("unifiedWindows") as? JsonObject ?: return
        rateLimits = windows.mapNotNull { (k, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            k to ClaudeUnifiedWindow(
                utilization = o["utilization"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                resetsAt = o["resetsAt"]?.jsonPrimitive?.longOrNull
            )
        }.toMap()
    }

    private fun onControlRequest(c: ClaudeControlRequestEvent) {
        val req = c.request
        if (req.subtype != "can_use_tool") return
        val toolUseId = req.tool_use_id ?: return
        val toolName = req.tool_name ?: "tool"
        val input = req.input ?: JsonObject(emptyMap())
        val turn = activeTurn ?: beginAssistantTurn()
        val tool = ensureTool(toolUseId, toolName, input, turn)
        if (tool.turn.parts.none { it is Part.Tool && it.toolUseId == toolUseId }) {
            tool.turn.parts.add(Part.Tool(toolUseId))
        }
        tool.input = input
        tool.status = if (toolName == "AskUserQuestion") "AWAITING_CHOICE" else "PENDING_APPROVAL"
        pending[toolUseId] = PendingPermission(
            requestId = c.request_id,
            toolUseId = toolUseId,
            toolName = toolName,
            input = input,
            suggestions = req.permission_suggestions,
            reason = req.decision_reason ?: req.description,
            blockedPath = req.blocked_path
        )
        touch(tool.turn)
    }

    // ------------------------------------------------------------------ helpers

    private fun recordCache(u: ClaudeUsage, atMs: Long) {
        val cached = u.cache_read_input_tokens + u.cache_creation_input_tokens
        if (cached <= 0) return
        val cc = u.cache_creation
        val ttl = when {
            cc != null && cc.ephemeral_1h_input_tokens > 0 -> 3_600_000L
            cc != null && cc.ephemeral_5m_input_tokens > 0 -> 300_000L
            else -> cacheInfo?.ttlMs?.takeIf { it > 0 } ?: 300_000L
        }
        cacheInfo = CacheInfo(
            anchorMs = atMs,
            ttlMs = ttl,
            recacheTokens = (u.input_tokens + u.cache_read_input_tokens + u.cache_creation_input_tokens).toLong()
        )
    }

    private fun beginAssistantTurn(): AssistantTurn {
        val lastUser = turns.lastOrNull { it is UserTurn } as? UserTurn
        val base = "claude_asst_${lastUser?.uuid ?: "start"}"
        var id = base
        var n = 1
        while (turns.any { it is AssistantTurn && it.id == id }) id = "${base}_${n++}"
        val turn = AssistantTurn(id).also { it.isStreaming = true }
        turns.add(turn)
        activeTurn = turn
        return turn
    }

    private fun addUserTurn(uuid: String, text: String, attachments: List<ChatAttachment>, createdAt: Long, parentUuid: String?) {
        seenUuids.add(uuid)
        turns.add(UserTurn(uuid, text, attachments, createdAt, stepCounter++, parentUuid))
    }

    private fun ensureTool(id: String, name: String, input: JsonObject?, turn: AssistantTurn): ToolState {
        val existing = tools[id]
        if (existing != null) {
            if (input != null && input.isNotEmpty()) existing.input = input
            if (name.isNotBlank()) existing.name = name
            return existing
        }
        return ToolState(id, name, input, stepCounter++, turn).also { tools[id] = it }
    }

    private fun applyToolResult(r: ClaudeContentBlock, structured: JsonElement?) {
        val id = r.tool_use_id ?: return
        val tool = tools[id] ?: return
        val text = contentText(r.content)
        val isError = r.is_error == true
        tool.output = toolOutput(tool, text, structured)
        tool.progress = null
        tool.status = when {
            !isError -> "SUCCESS"
            text.contains("rejected", ignoreCase = true) || text.contains("denied", ignoreCase = true) ||
                text.contains("doesn't want to proceed", ignoreCase = true) -> "REJECTED"
            else -> "FAILED"
        }
        tool.exitCode = if (isError) 1 else 0
        tool.durationMs = System.currentTimeMillis() - tool.startedAt
        pending.remove(id)
        touch(tool.turn)
    }

    private fun addNotice(text: String) {
        val turn = activeTurn ?: (turns.lastOrNull() as? AssistantTurn) ?: beginAssistantTurn().also { it.isStreaming = false }
        turn.parts.add(Part.Text(StringBuilder("_${text}_")))
        touch(turn)
    }

    private fun addLocalCommandOutput(raw: String) {
        val out = raw.replace(Regex("</?local-command-(stdout|stderr)>"), "").trim()
        if (out.isBlank()) return
        // output outside a turn (e.g. a live model switch) is complete on arrival
        val turn = activeTurn ?: beginAssistantTurn().also {
            it.isStreaming = false
            activeTurn = null
        }
        turn.parts.add(Part.Text(StringBuilder(out)))
        touch(turn)
    }

    private fun touch(turn: Turn) {
        turn.version++
    }

    private fun blockKey(messageId: String?, index: Int) = "${messageId ?: "msg"}#$index"

    private inline fun <reified T> decode(obj: JsonObject): T? =
        runCatching { ClaudeJson.decodeFromJsonElement<T>(obj) }.getOrNull()

    // ------------------------------------------------------------------ output

    fun toChatMessages(): List<ChatMessage> = turns.map { turn ->
        val cached = turn.cached
        if (cached != null && turn.cachedVersion == turn.version) return@map cached
        val msg = when (turn) {
            is UserTurn -> ChatMessage(
                id = "user_${turn.uuid}",
                conversationId = conversationId,
                role = MessageRole.USER,
                content = turn.text,
                attachments = turn.attachments,
                stepIndex = turn.stepIndex,
                createdAt = turn.createdAt
            )
            is AssistantTurn -> assistantMessage(turn)
            is SummaryTurn -> ChatMessage(
                id = turn.id,
                conversationId = conversationId,
                role = MessageRole.SUMMARY,
                content = turn.text,
                contextSummary = turn.text
            )
        }
        turn.cached = msg
        turn.cachedVersion = turn.version
        msg
    }

    private fun assistantMessage(turn: AssistantTurn): ChatMessage {
        val contentParts = mutableListOf<String>()
        val thoughts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()
        for (part in turn.parts) {
            when (part) {
                is Part.Thinking -> {
                    val t = part.text.toString().trim()
                    if (t.isNotBlank()) {
                        val tag = if (part.streaming && turn.isStreaming) ":streaming" else ""
                        contentParts.add("<!-- thought$tag -->\n$t\n<!-- /thought -->")
                        thoughts.add(t)
                    }
                }
                is Part.Text -> part.text.toString().trim().takeIf { it.isNotBlank() }?.let { contentParts.add(it) }
                is Part.Tool -> {
                    val tool = tools[part.toolUseId] ?: continue
                    if (tool.name in HIDDEN_TOOLS) continue
                    if (toolCalls.none { it.id == tool.id }) {
                        toolCalls.add(tool.toToolCall())
                        contentParts.add("<!-- tool_call:${tool.id} -->")
                    }
                }
                is Part.Error -> {
                    val err = buildJsonObject {
                        put("title", part.title)
                        put("userMessage", part.message)
                        put("shortError", part.message)
                        put("fullError", "")
                    }
                    contentParts.add("<!-- error -->\n${ClaudeJson.encodeToString(err)}\n<!-- /error -->")
                }
            }
        }
        val usage = turn.resultUsage ?: turn.lastMessageUsage?.toTokenUsage()
        return ChatMessage(
            id = turn.id,
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = contentParts.joinToString("\n\n").trim(),
            thoughtText = thoughts.joinToString("\n\n").trim().takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
            isStreaming = turn.isStreaming,
            tokenUsage = if (turn.isStreaming) null else usage
        )
    }

    private fun ToolState.toToolCall(): ToolCall {
        val obj = input ?: JsonObject(emptyMap())
        val shownOutput = when {
            output.isNotBlank() -> output
            name == "TodoWrite" -> todoChecklist(obj)
            name == "ExitPlanMode" -> (obj["plan"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            else -> progress.orEmpty()
        }
        return ToolCall(
            id = id,
            name = name,
            toolType = toolTypeFor(name),
            command = describeTool(name, obj),
            status = status,
            output = shownOutput,
            exitCode = exitCode,
            durationMs = durationMs,
            stepIndex = stepIndex,
            interactionType = "claude",
            questionnaire = if (name == "AskUserQuestion") questionnaireFrom(obj) else null
        )
    }

    companion object {
        /** Internal tools that are not interesting to show as cards. */
        private val HIDDEN_TOOLS = setOf("ToolSearch")

        fun toolTypeFor(name: String): ToolType = when {
            name == "Bash" || name == "BashOutput" || name == "KillShell" -> ToolType.BASH
            name == "Read" -> ToolType.VIEW_FILE
            name == "Write" || name == "Edit" || name == "MultiEdit" || name == "NotebookEdit" -> ToolType.EDIT_FILE
            name == "Glob" -> ToolType.FIND
            name == "Grep" -> ToolType.GREP_SEARCH
            name == "LS" -> ToolType.LIST_DIR
            name == "WebSearch" -> ToolType.SEARCH_WEB
            name == "WebFetch" -> ToolType.READ_URL
            name == "AskUserQuestion" -> ToolType.ASK_CHOICE
            name.startsWith("mcp__") -> ToolType.MCP
            else -> ToolType.UNKNOWN
        }

        fun describeTool(name: String, input: JsonObject): String {
            fun str(key: String) = (input[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun file(key: String) = str(key).substringAfterLast('/').ifBlank { str(key) }
            return when (name) {
                "Bash" -> str("command")
                "Read" -> file("file_path")
                "Write", "Edit", "MultiEdit" -> file("file_path")
                "NotebookEdit" -> file("notebook_path")
                "Glob" -> str("pattern")
                "Grep" -> str("pattern") + str("path").takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
                "WebSearch" -> str("query")
                "WebFetch" -> str("url")
                "Agent", "Task" -> str("description").ifBlank { "Subagent" }
                "TodoWrite" -> {
                    val todos = (input["todos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    val done = todos.count { (it["status"] as? JsonPrimitive)?.contentOrNull == "completed" }
                    "Todo list ($done/${todos.size} done)"
                }
                "ExitPlanMode" -> "Plan ready for review"
                "AskUserQuestion" -> (input["questions"] as? JsonArray)?.firstOrNull()?.jsonObject
                    ?.get("question")?.jsonPrimitive?.contentOrNull ?: "Question"
                else -> if (name.startsWith("mcp__")) name.removePrefix("mcp__").replace("__", " · ") else name
            }.ifBlank { name }
        }

        fun todoChecklist(input: JsonObject): String =
            (input["todos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.joinToString("\n") { t ->
                val mark = when ((t["status"] as? JsonPrimitive)?.contentOrNull) {
                    "completed" -> "☑"
                    "in_progress" -> "◐"
                    else -> "☐"
                }
                "$mark ${(t["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()}"
            }

        fun questionnaireFrom(input: JsonObject): ChoiceQuestionnaire {
            val questions = (input["questions"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
                val q = el as? JsonObject ?: return@mapIndexedNotNull null
                ChoiceQuestion(
                    id = (i + 1).toString(),
                    prompt = q["question"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    description = q["header"]?.jsonPrimitive?.contentOrNull,
                    isMultiSelect = q["multiSelect"]?.jsonPrimitive?.contentOrNull == "true",
                    options = (q["options"] as? JsonArray).orEmpty().mapIndexedNotNull { j, o ->
                        val opt = o as? JsonObject ?: return@mapIndexedNotNull null
                        ChoiceOption(
                            id = (j + 1).toString(),
                            label = opt["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            description = opt["description"]?.jsonPrimitive?.contentOrNull
                        )
                    }
                )
            }
            val header = (input["questions"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("header")?.jsonPrimitive?.contentOrNull
            return ChoiceQuestionnaire(title = header ?: "Claude has a question", questions = questions)
        }

        fun contentText(content: JsonElement?): String = when (content) {
            null -> ""
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                when (o["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> o["text"]?.jsonPrimitive?.contentOrNull
                    "image" -> "[image]"
                    else -> null
                }
            }.joinToString("\n")
            else -> content.toString()
        }

        private fun toolOutput(tool: ToolState, text: String, structured: JsonElement?): String {
            val obj = structured as? JsonObject
            when (tool.name) {
                "Edit", "MultiEdit", "Write" -> {
                    val patch = obj?.get("structuredPatch") as? JsonArray
                    if (patch != null && patch.isNotEmpty()) {
                        return patch.joinToString("\n") { hunkEl ->
                            val h = hunkEl.jsonObject
                            fun n(k: String) = h[k]?.jsonPrimitive?.intOrNull ?: 0
                            val lines = (h["lines"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                            "@@ -${n("oldStart")},${n("oldLines")} +${n("newStart")},${n("newLines")} @@\n" + lines.joinToString("\n")
                        }
                    }
                    if (tool.name == "Write") {
                        val content = obj?.get("content")?.jsonPrimitive?.contentOrNull
                            ?: tool.input?.get("content")?.jsonPrimitive?.contentOrNull
                        if (!content.isNullOrEmpty()) return content.lines().joinToString("\n") { "+$it" }
                    }
                }
                "Bash" -> {
                    val stdout = obj?.get("stdout")?.jsonPrimitive?.contentOrNull
                    val stderr = obj?.get("stderr")?.jsonPrimitive?.contentOrNull
                    if (stdout != null || stderr != null) {
                        return listOfNotNull(stdout?.takeIf { it.isNotEmpty() }, stderr?.takeIf { it.isNotEmpty() })
                            .joinToString("\n").ifEmpty { text }
                    }
                }
                "TodoWrite" -> tool.input?.let { return todoChecklist(it) }
                "Agent", "Task" -> {
                    // drop the CLI's hand-back preamble so the card shows the subagent's report
                    if (text.startsWith("[Subagent hand-back]")) {
                        val body = text.substringAfter("\n\n", text)
                        return body.trim()
                    }
                }
            }
            return text
        }

        private fun errorTitle(subtype: String, msg: String): String = when {
            msg.contains("login", ignoreCase = true) || msg.contains("auth", ignoreCase = true) -> "Sign-in Required"
            msg.contains("rate", ignoreCase = true) || msg.contains("limit", ignoreCase = true) -> "Usage Limit Reached"
            subtype == "error_max_turns" -> "Turn Limit Reached"
            else -> "Claude Error"
        }

        private fun commandDisplay(text: String): String? {
            val name = Regex("<command-name>(.*?)</command-name>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()
                ?: return null
            val args = Regex("<command-args>(.*?)</command-args>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim().orEmpty()
            return if (args.isBlank()) name else "$name $args"
        }

        private val contextTagRe = Regex(
            "<(ide_selection|ide_opened_file|system-reminder)>.*?</(ide_selection|ide_opened_file|system-reminder)>",
            RegexOption.DOT_MATCHES_ALL
        )

        private fun stripContextTags(text: String) = text.replace(contextTagRe, "").trim()

        private fun parseTime(ts: String?): Long? = ts?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

        /** Claude reports cache reads separately; the token pill shows prompt (uncached) + cached + output. */
        private fun ClaudeUsage.toTokenUsage(durationMs: Long = 0, costUsd: Double? = null): TokenUsage? {
            val prompt = input_tokens + cache_creation_input_tokens
            if (prompt + cache_read_input_tokens + output_tokens == 0) return null
            return TokenUsage(
                promptTokens = prompt,
                outputTokens = output_tokens,
                cachedTokens = cache_read_input_tokens,
                cacheCreationTokens = cache_creation_input_tokens,
                totalTokens = prompt + cache_read_input_tokens + output_tokens,
                durationMs = durationMs,
                costUsd = costUsd
            )
        }

        private fun ClaudeContentBlock.toAttachment(uuid: String, index: Int): ChatAttachment? {
            val src = source ?: return null
            return when (type) {
                "image" -> ChatAttachment(
                    id = "${uuid}_att_$index",
                    name = "image_${index + 1}",
                    path = "",
                    isImage = true,
                    mimeType = src.media_type,
                    base64 = src.data
                )
                "document" -> ChatAttachment(
                    id = "${uuid}_att_$index",
                    name = title ?: "document_${index + 1}",
                    path = "",
                    mimeType = src.media_type,
                    base64 = if (src.type == "base64") src.data else null
                )
                else -> null
            }
        }
    }
}
