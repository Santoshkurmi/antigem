package com.example.gemini.domain.usage

import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.domain.model.UsageCall
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a chat's model requests ([UsageCall]s on each reply) into the usage inspector's picture: what every request
 * weighed, how the context grew and why, what each addition costs on every later request, and what the turns took
 * from the plan's 5-hour limit.
 *
 * Weight is in **units**: 1 unit = one fresh input token of a Sonnet-class model. A cache read, a cache write and an
 * output token weigh what they cost relative to that (approximate price ratios, see [Prices]); no money is shown.
 */
object UsageAnalyzer {

    /** Relative weights of one token of each kind (fresh input = 1) and of the model against a Sonnet-class one. */
    data class Prices(
        val cacheRead: Double,
        val cacheWrite5m: Double,
        val cacheWrite1h: Double,
        val output: Double,
        val modelWeight: Double,
        val family: String
    )

    /**
     * Approximate ratios from the providers' public price lists (they change; this is for a sense of weight only).
     * Claude: read 0.1×, 5-min write 1.25×, 1-hour write 2×, output 5×; Haiku ⅓, Opus/Fable 5⁄3 of Sonnet.
     * OpenAI: cached input 0.1×, output 8×. Gemini: cached 0.1×, output 6×.
     */
    fun pricesFor(model: String?): Prices {
        val m = model.orEmpty().lowercase(Locale.ROOT)
        return when {
            "haiku" in m -> Prices(0.1, 1.25, 2.0, 5.0, 1.0 / 3.0, "Haiku")
            "opus" in m -> Prices(0.1, 1.25, 2.0, 5.0, 5.0 / 3.0, "Opus")
            "fable" in m -> Prices(0.1, 1.25, 2.0, 5.0, 5.0 / 3.0, "Fable")
            "sonnet" in m || "claude" in m -> Prices(0.1, 1.25, 2.0, 5.0, 1.0, "Sonnet")
            "gpt" in m || "codex" in m || Regex("\\bo[1-9]").containsMatchIn(m) -> Prices(0.1, 1.0, 1.0, 8.0, 1.0, "GPT")
            "gemini" in m || "flash" in m || "argon" in m || "model_placeholder" in m -> Prices(0.1, 1.0, 1.0, 6.0, 1.0, "Gemini")
            else -> Prices(0.1, 1.25, 2.0, 5.0, 1.0, "Model")
        }
    }

    enum class CauseKind { START, PROMPT, REPLY, TOOL, COMPACTED, OTHER }

    /** One reason the context grew before a request. [tokens] is measured (reply) or estimated (by text size). */
    data class Cause(
        val kind: CauseKind,
        val label: String,
        val tokens: Int,
        val toolType: ToolType? = null,
        val toolId: String? = null,
        /** Group name for the "where it came from" view (tool name, "Your messages"…). */
        val group: String
    )

    enum class EventKind { COMPACTION, CACHE_MISS, CACHE_EXPIRED, BIG_ADDITION, NEAR_FULL }

    data class CallStat(
        val index: Int,
        val call: UsageCall,
        val turnIndex: Int,
        val messageId: String,
        val prices: Prices,
        val unitsInput: Double,
        val unitsCacheWrite: Double,
        val unitsCacheRead: Double,
        /** Output units, reasoning included. */
        val unitsOutput: Double,
        val unitsReasoning: Double,
        val units: Double,
        /** The same request with no cache at all (whole context at the input price). */
        val unitsNoCache: Double,
        val cumulativeUnits: Double,
        val context: Int,
        /** Context change from the previous request (0 for the first). */
        val delta: Int,
        val gapMs: Long?,
        val causes: List<Cause>,
        val events: Set<EventKind>,
        /** A reply's total, not one request (the agent kept no per-request record for it). */
        val aggregated: Boolean
    ) {
        val hitRate: Double get() = if (context > 0) call.cacheRead.toDouble() / context else 0.0
        val tokens: Int get() = context + call.output
        val answerOutput: Int get() = call.output - (call.reasoning ?: 0).coerceAtMost(call.output)
    }

    data class TurnStat(
        val index: Int,
        val messageId: String,
        val prompt: String,
        val firstCall: Int,
        val lastCall: Int,
        val units: Double,
        val tokens: Long,
        val startMs: Long,
        val endMs: Long,
        /** Share of the 5-hour limit this turn took (0..1), when readings around it exist. */
        val limitDelta: Double?,
        val streaming: Boolean
    ) {
        val callCount: Int get() = lastCall - firstCall + 1
    }

    /** A context addition and what carrying it has cost so far: it is re-read by every later request. */
    data class LedgerEntry(val cause: Cause, val callIndex: Int, val rereadCalls: Int, val rereadUnits: Double, val perCallUnits: Double)

    data class GroupStat(val name: String, val kind: CauseKind, val count: Int, val tokens: Long, val rereadUnits: Double)

    data class LimitInfo(
        val scope: String,
        val used: Double,
        val resetAtMs: Long?,
        /** Share used per hour, from the last hour's readings. */
        val burnPerHour: Double?,
        /** When the limit runs out at that pace (null: not before it resets / no pace yet). */
        val emptyAtMs: Long?,
        /** Units that took 1% of the limit in this chat (learned from the turns with readings around them). */
        val unitsPerPercent: Double?,
        val samples: List<UsageLimitTracker.Sample>
    )

    data class Report(
        val agent: AgentKind,
        val calls: List<CallStat>,
        val turns: List<TurnStat>,
        val ledger: List<LedgerEntry>,
        val groups: List<GroupStat>,
        val totalUnits: Double,
        val totalUnitsNoCache: Double,
        val totalInput: Long,
        val totalCacheRead: Long,
        val totalCacheWrite: Long,
        val totalOutput: Long,
        val totalReasoning: Long,
        val window: Int,
        val fiveHour: LimitInfo?,
        val weekly: LimitInfo?,
        val tips: List<String>
    ) {
        val last: CallStat? get() = calls.lastOrNull()
        val contextNow: Int get() = last?.context ?: 0
        val fullness: Double get() = if (window > 0) contextNow.toDouble() / window else 0.0
        val savedShare: Double get() = if (totalUnitsNoCache > 0) 1.0 - totalUnits / totalUnitsNoCache else 0.0
        val cacheHitRate: Double get() {
            val ctx = totalInput + totalCacheRead + totalCacheWrite
            return if (ctx > 0) totalCacheRead.toDouble() / ctx else 0.0
        }
        val isEmpty: Boolean get() = calls.isEmpty()
    }

    private const val CHARS_PER_TOKEN = 3.6
    private const val IMAGE_TOKENS = 1600

    fun estimateTokens(text: String?): Int = ((text?.length ?: 0) / CHARS_PER_TOKEN).toInt()

    fun analyze(
        agent: AgentKind,
        messages: List<ChatMessage>,
        limits: Map<String, UsageLimitTracker.Series>,
        nowMs: Long = System.currentTimeMillis()
    ): Report {
        val tools = HashMap<String, ToolCall>()
        for (m in messages) for (t in m.toolCalls) tools.putIfAbsent(t.id, t)

        // ---- requests in order, with the turn and prompt they belong to
        data class Raw(val call: UsageCall, val messageId: String, val turn: Int, val firstInTurn: Boolean, val prompt: ChatMessage?, val aggregated: Boolean)
        val raws = mutableListOf<Raw>()
        val turnMeta = mutableListOf<Triple<ChatMessage, ChatMessage?, Boolean>>() // reply, its prompt, streaming
        val seen = HashSet<String>()
        var lastPrompt: ChatMessage? = null
        var promptUsed = false
        for (m in messages) {
            if (m.role == MessageRole.USER) {
                lastPrompt = m
                promptUsed = false
                continue
            }
            if (m.role != MessageRole.ASSISTANT) continue
            val u = m.tokenUsage ?: continue
            val calls = u.calls.ifEmpty {
                // no per-request record (an old reply, a history the agent keeps only totals for): the reply as one
                if (u.totalTokens == 0 && u.promptTokens + u.cachedTokens + u.outputTokens == 0) emptyList()
                else listOf(aggregateCall(m, agent))
            }.filter { seen.add(it.id) }
            if (calls.isEmpty()) continue
            val turn = turnMeta.size
            turnMeta.add(Triple(m, lastPrompt.takeIf { !promptUsed }, m.isStreaming))
            for ((i, c) in calls.withIndex()) {
                val first = i == 0 && !promptUsed
                raws.add(Raw(if (c.atMs > 0) c else c.copy(atMs = m.createdAt), m.id, turn, first, lastPrompt.takeIf { first }, u.calls.isEmpty()))
            }
            promptUsed = true
        }

        // ---- per request: weight, growth and its causes, events
        val positiveDeltas = mutableListOf<Int>()
        for (i in 1 until raws.size) (raws[i].call.context - raws[i - 1].call.context).takeIf { it > 0 }?.let(positiveDeltas::add)
        val medianDelta = positiveDeltas.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val bigThreshold = max(10_000, medianDelta * 3)
        val window = contextWindow(raws.map { it.call })

        val stats = ArrayList<CallStat>(raws.size)
        var cumulative = 0.0
        var cacheTtl = 300_000L
        for ((i, r) in raws.withIndex()) {
            val c = r.call
            val p = pricesFor(c.model)
            val w = p.modelWeight
            val write5m = (c.cacheWrite - c.cacheWrite1h).coerceAtLeast(0)
            val uIn = c.input * w
            val uWrite = (write5m * p.cacheWrite5m + c.cacheWrite1h * p.cacheWrite1h) * w
            val uRead = c.cacheRead * p.cacheRead * w
            val uOut = c.output * p.output * w
            val uReason = (c.reasoning ?: 0).coerceAtMost(c.output) * p.output * w
            val units = uIn + uWrite + uRead + uOut
            val noCache = (c.context * 1.0 + c.output * p.output) * w
            cumulative += units

            val prev = raws.getOrNull(i - 1)
            val prevCtx = prev?.call?.context ?: 0
            val delta = if (prev == null) 0 else c.context - prevCtx
            val gap = prev?.let { (c.atMs - it.call.atMs).takeIf { g -> g >= 0 } }
            val events = mutableSetOf<EventKind>()
            val compacted = prev != null && !r.aggregated && !prev.aggregated && prevCtx > 0 && c.context < prevCtx * 0.7
            if (compacted) events.add(EventKind.COMPACTION)
            if (prev != null && !compacted && !r.aggregated && prevCtx > 5_000 &&
                c.cacheRead < prevCtx * 0.5 && c.cacheWrite + c.input > prevCtx * 0.5
            ) events.add(if (gap != null && gap > cacheTtl) EventKind.CACHE_EXPIRED else EventKind.CACHE_MISS)
            if (prev != null && !compacted && delta > bigThreshold) events.add(EventKind.BIG_ADDITION)
            if (window > 0 && c.context > window * 0.8) events.add(EventKind.NEAR_FULL)
            if (c.cacheWrite1h > 0) cacheTtl = 3_600_000L else if (c.cacheWrite > 0) cacheTtl = 300_000L

            val causes = when {
                r.aggregated -> listOf(Cause(CauseKind.OTHER, "Whole reply (no per-request record)", c.context, group = "Reply totals"))
                prev == null || prev.aggregated -> startCauses(r.prompt, c.context, first = prev == null)
                compacted -> listOf(Cause(CauseKind.COMPACTED, "Context compacted", delta, group = "Compaction"))
                else -> growthCauses(delta, prev.call, r.prompt, tools)
            }
            stats.add(CallStat(
                index = i, call = c, turnIndex = r.turn, messageId = r.messageId, prices = p,
                unitsInput = uIn, unitsCacheWrite = uWrite, unitsCacheRead = uRead, unitsOutput = uOut, unitsReasoning = uReason,
                units = units, unitsNoCache = noCache, cumulativeUnits = cumulative,
                context = c.context, delta = delta, gapMs = gap, causes = causes, events = events, aggregated = r.aggregated
            ))
        }

        // ---- ledger: every addition is re-read by each later request until a compaction drops it
        val ledgerAll = mutableListOf<LedgerEntry>()
        val groupTokens = LinkedHashMap<String, Pair<CauseKind, Long>>()
        val groupCount = HashMap<String, Int>()
        val groupReread = HashMap<String, Double>()
        for (s in stats) {
            var end = stats.size
            for (k in s.index + 1 until stats.size) if (EventKind.COMPACTION in stats[k].events) { end = k; break }
            for (cause in s.causes) {
                if (cause.tokens <= 0 || cause.kind == CauseKind.COMPACTED) continue
                var reread = 0.0
                for (k in s.index + 1 until end) stats[k].prices.let { reread += cause.tokens * it.cacheRead * it.modelWeight }
                val perCall = cause.tokens * s.prices.cacheRead * s.prices.modelWeight
                ledgerAll.add(LedgerEntry(cause, s.index, end - s.index - 1, reread, perCall))
                val g = groupTokens[cause.group]
                groupTokens[cause.group] = cause.kind to ((g?.second ?: 0L) + cause.tokens)
                groupCount[cause.group] = (groupCount[cause.group] ?: 0) + 1
                groupReread[cause.group] = (groupReread[cause.group] ?: 0.0) + reread
            }
        }
        val ledger = ledgerAll.filter { it.cause.kind != CauseKind.START || it.cause.tokens > 0 }
            .sortedByDescending { it.rereadUnits + it.cause.tokens * 1.25 }.take(12)
        val groups = groupTokens.map { (name, v) -> GroupStat(name, v.first, groupCount[name] ?: 0, v.second, groupReread[name] ?: 0.0) }
            .sortedByDescending { it.tokens }

        // ---- plan limits
        val five = pickSeries(limits, agent, UsageLimitTracker.FIVE_HOUR, stats)
        val week = pickSeries(limits, agent, UsageLimitTracker.WEEKLY, stats)
        val turns = turnMeta.mapIndexedNotNull { ti, (reply, prompt, streaming) ->
            val idx = stats.filter { it.turnIndex == ti }
            if (idx.isEmpty()) return@mapIndexedNotNull null
            TurnStat(
                index = ti,
                messageId = reply.id,
                prompt = prompt?.content?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "(continued)",
                firstCall = idx.first().index,
                lastCall = idx.last().index,
                units = idx.sumOf { it.units },
                tokens = idx.sumOf { it.tokens.toLong() },
                startMs = prompt?.createdAt?.takeIf { it > 0 } ?: idx.first().call.atMs,
                endMs = idx.last().call.atMs,
                limitDelta = null,
                streaming = streaming
            )
        }.let { list -> withLimitDeltas(list, five?.samples.orEmpty(), nowMs) }

        val fiveInfo = five?.let { limitInfo(it, turns, nowMs) }
        val weekInfo = week?.let { limitInfo(it, emptyList(), nowMs) }

        val report = Report(
            agent = agent, calls = stats, turns = turns, ledger = ledger, groups = groups,
            totalUnits = cumulative, totalUnitsNoCache = stats.sumOf { it.unitsNoCache },
            totalInput = stats.sumOf { it.call.input.toLong() }, totalCacheRead = stats.sumOf { it.call.cacheRead.toLong() },
            totalCacheWrite = stats.sumOf { it.call.cacheWrite.toLong() }, totalOutput = stats.sumOf { it.call.output.toLong() },
            totalReasoning = stats.sumOf { (it.call.reasoning ?: 0).toLong() },
            window = window, fiveHour = fiveInfo, weekly = weekInfo, tips = emptyList()
        )
        return report.copy(tips = tips(report))
    }

    private fun aggregateCall(m: ChatMessage, agent: AgentKind): UsageCall {
        val u = m.tokenUsage!!
        // Claude's promptTokens already holds the cache writes; the others report writes on their own
        val input = if (agent == AgentKind.CLAUDE) (u.promptTokens - u.cacheCreationTokens).coerceAtLeast(0) else u.promptTokens
        return UsageCall(
            id = "turn_${m.id}", atMs = m.createdAt + u.durationMs, model = u.model ?: m.modelId,
            input = input, cacheRead = u.cachedTokens, cacheWrite = u.cacheCreationTokens, output = u.outputTokens
        )
    }

    private fun promptTokens(prompt: ChatMessage?): Int {
        if (prompt == null) return 0
        val text = estimateTokens(prompt.rawContent ?: prompt.content)
        val files = prompt.attachments.sumOf { a ->
            when {
                a.isImage -> IMAGE_TOKENS
                a.reference -> 20
                else -> (a.size / CHARS_PER_TOKEN).toInt().coerceAtMost(50_000)
            }
        }
        return text + files
    }

    private fun startCauses(prompt: ChatMessage?, context: Int, first: Boolean): List<Cause> {
        val pt = min(promptTokens(prompt), context)
        val out = mutableListOf<Cause>()
        if (pt > 0) out.add(Cause(CauseKind.PROMPT, promptLabel(prompt), pt, group = "Your messages"))
        val rest = context - pt
        if (rest > 0) out.add(Cause(
            CauseKind.START,
            if (first) "System prompt, tools, memory & earlier history" else "Context before this point",
            rest, group = "System prompt & tools"
        ))
        return out
    }

    /**
     * Splits a growth of [delta] tokens: the previous request's own output first (measured), then its tools' results
     * and a new prompt, shared by their size; anything with no visible source is "hidden context".
     */
    private fun growthCauses(delta: Int, prev: UsageCall, prompt: ChatMessage?, tools: Map<String, ToolCall>): List<Cause> {
        if (delta <= 0) return if (delta < 0) listOf(Cause(CauseKind.OTHER, "Context shrank (cleared tool results / thinking)", delta, group = "Trimmed")) else emptyList()
        val out = mutableListOf<Cause>()
        // reasoning of earlier requests is usually dropped from later context; the answer part stays
        val replyTokens = min(prev.output - (prev.reasoning ?: 0).coerceAtMost(prev.output), delta).coerceAtLeast(0)
        if (replyTokens > 0) out.add(Cause(CauseKind.REPLY, "Model's previous output", replyTokens, group = "Model output"))
        var rest = delta - replyTokens
        if (rest <= 0) return out
        data class Est(val cause: Cause, val weight: Int)
        val ests = mutableListOf<Est>()
        for (id in prev.toolIds) {
            val t = tools[id]
            val size = if (t == null) 0 else estimateTokens(t.command) + estimateTokens(t.output) + 12
            val name = t?.let { toolGroup(it) } ?: "Tool"
            val label = t?.let { toolLabel(it) } ?: "Tool result"
            ests.add(Est(Cause(CauseKind.TOOL, label, 0, t?.toolType, id, name), max(size, 1)))
        }
        val pt = promptTokens(prompt)
        if (prompt != null) ests.add(Est(Cause(CauseKind.PROMPT, promptLabel(prompt), 0, group = "Your messages"), max(pt, 1)))
        if (ests.isEmpty()) {
            out.add(Cause(CauseKind.OTHER, "Hidden context (system reminders, tool schemas…)", rest, group = "Hidden context"))
            return out
        }
        val estSum = ests.sumOf { it.weight }
        // a source far smaller than the growth leaves the rest unexplained instead of inflating it
        val explained = if (estSum * 4 < rest && estSum > 50) min(rest, estSum * 2) else rest
        var given = 0
        for ((k, e) in ests.withIndex()) {
            val share = if (k == ests.lastIndex) explained - given else (explained.toLong() * e.weight / estSum).toInt()
            given += share
            if (share > 0) out.add(e.cause.copy(tokens = share))
        }
        rest -= explained
        if (rest > 0) out.add(Cause(CauseKind.OTHER, "Hidden context (system reminders, tool schemas…)", rest, group = "Hidden context"))
        return out
    }

    private fun promptLabel(prompt: ChatMessage?): String {
        val text = prompt?.content?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val files = prompt?.attachments?.size ?: 0
        val base = if (text.isBlank()) "Your message" else "You: \"${text.take(60)}${if (text.length > 60) "…" else ""}\""
        return if (files > 0) "$base + $files file${if (files > 1) "s" else ""}" else base
    }

    fun toolGroup(t: ToolCall): String = when (t.toolType) {
        ToolType.BASH -> "Shell"
        ToolType.VIEW_FILE -> "Read file"
        ToolType.EDIT_FILE -> "Edit file"
        ToolType.LIST_DIR -> "List dir"
        ToolType.GREP_SEARCH -> "Search"
        ToolType.FIND -> "Find"
        ToolType.SEARCH_WEB -> "Web search"
        ToolType.READ_URL -> "Read URL"
        ToolType.GENERATE_IMAGE -> "Image gen"
        ToolType.MCP -> "MCP · " + t.name.removePrefix("mcp__").substringBefore("__").take(18)
        ToolType.ASK_CHOICE -> "Questions"
        else -> t.name.ifBlank { "Tool" }.take(22)
    }

    private fun toolLabel(t: ToolCall): String {
        val cmd = t.command.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val tail = if (cmd.isBlank()) "" else ": " + (if (cmd.length > 48) cmd.take(48) + "…" else cmd)
        return toolGroup(t) + tail
    }

    private fun contextWindow(calls: List<UsageCall>): Int {
        val maxCtx = calls.maxOfOrNull { it.context } ?: 0
        val model = calls.lastOrNull { it.model != null }?.model.orEmpty().lowercase(Locale.ROOT)
        val base = when {
            "[1m]" in model || "1m" in model -> 1_000_000
            "gemini" in model || "flash" in model -> 1_048_576
            "gpt" in model || "codex" in model -> 272_000
            else -> 200_000
        }
        return listOf(base, 272_000, 400_000, 1_000_000, 2_000_000).filter { it >= base }.firstOrNull { it >= maxCtx } ?: maxCtx
    }

    /** The limit this chat draws on: the series that moved most while it ran (Antigravity has one per model group). */
    private fun pickSeries(all: Map<String, UsageLimitTracker.Series>, agent: AgentKind, window: String, calls: List<CallStat>): UsageLimitTracker.Series? {
        val list = UsageLimitTracker.forAgent(all, agent, window).filter { it.samples.isNotEmpty() }
        if (list.size <= 1) return list.firstOrNull()
        val from = calls.firstOrNull()?.call?.atMs ?: 0L
        return list.maxByOrNull { s ->
            val inRange = s.samples.filter { it.atMs >= from - 60_000 }
            if (inRange.size < 2) 0.0 else inRange.maxOf { it.used } - inRange.minOf { it.used }
        }
    }

    private fun valueAt(samples: List<UsageLimitTracker.Sample>, t: Long) = samples.lastOrNull { it.atMs <= t }

    /** Each turn's share of the limit: the reading after it minus the reading after the turn before it. */
    private fun withLimitDeltas(turns: List<TurnStat>, samples: List<UsageLimitTracker.Sample>, nowMs: Long): List<TurnStat> {
        if (samples.isEmpty()) return turns
        val grace = 20_000L
        return turns.mapIndexed { i, t ->
            val fromT = turns.getOrNull(i - 1)?.endMs?.plus(grace) ?: (t.startMs - 1_000)
            val toT = if (i == turns.lastIndex) nowMs else min(t.endMs + grace, turns[i + 1].startMs + grace)
            val before = valueAt(samples, fromT) ?: return@mapIndexed t
            val after = valueAt(samples, toT) ?: return@mapIndexed t
            // no new reading since the turn before: unknown, not zero
            if (samples.none { it.atMs in (fromT + 1)..toT }) return@mapIndexed t
            val reset = before.resetAtMs != null && after.resetAtMs != null && after.resetAtMs - before.resetAtMs > 60_000
            if (reset || after.used < before.used) return@mapIndexed t
            t.copy(limitDelta = after.used - before.used)
        }
    }

    private fun limitInfo(s: UsageLimitTracker.Series, turns: List<TurnStat>, nowMs: Long): LimitInfo {
        val latest = s.samples.last()
        val hourAgo = nowMs - 3_600_000
        // the pace since an hour ago (or since the window last started over)
        var base = s.samples.lastOrNull { it.atMs <= hourAgo } ?: s.samples.first()
        for (smp in s.samples) if (smp.atMs > base.atMs && smp.used < base.used) base = smp
        val hours = (latest.atMs - base.atMs) / 3_600_000.0
        val burn = if (hours > 1.0 / 60 && latest.used > base.used) (latest.used - base.used) / hours else null
        val empty = burn?.let { b ->
            val at = latest.atMs + ((1.0 - latest.used) / b * 3_600_000).toLong()
            if (latest.resetAtMs != null && at >= latest.resetAtMs) null else at
        }
        val measured = turns.filter { (it.limitDelta ?: 0.0) > 0.0 }
        val upp = if (measured.isEmpty()) null else measured.sumOf { it.units } / (measured.sumOf { it.limitDelta!! } * 100.0)
        return LimitInfo(s.scope, latest.used, latest.resetAtMs?.takeIf { it > nowMs }, burn, empty, upp, s.samples)
    }

    private fun tips(r: Report): List<String> {
        if (r.calls.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val k = { n: Double -> compact(n) }
        r.ledger.firstOrNull { it.cause.kind == CauseKind.TOOL && it.cause.tokens > 8_000 }?.let { e ->
            out.add("“${e.cause.label}” added ${compact(e.cause.tokens.toDouble())} tokens; every later request re-reads it (~${k(e.perCallUnits)} units each, ${k(e.rereadUnits)} so far). Narrower reads/greps keep this down.")
        }
        val misses = r.calls.filter { EventKind.CACHE_EXPIRED in it.events }
        if (misses.isNotEmpty()) {
            val rewritten = misses.sumOf { it.call.cacheWrite + it.call.input }
            out.add("The cache expired ${misses.size}× after idle gaps; each time the whole context was written again (${compact(rewritten.toDouble())} tokens). Replying within the cache time avoids it.")
        }
        val miss2 = r.calls.count { EventKind.CACHE_MISS in it.events }
        if (miss2 > 0) out.add("$miss2 request${if (miss2 > 1) "s" else ""} missed the cache without an idle gap (model / tools / system prompt changed, or a switch of settings).")
        if (r.fullness > 0.6) out.add("Context is ${(r.fullness * 100).toInt()}% full: each request now re-reads ${compact(r.contextNow.toDouble())} tokens. Compacting or a new chat makes the next requests much lighter.")
        val outShare = r.calls.sumOf { it.unitsOutput } / r.totalUnits.coerceAtLeast(1.0)
        if (outShare > 0.45) out.add("Output is ${(outShare * 100).toInt()}% of the weight here${if (r.totalReasoning > 0) " (reasoning ${compact(r.totalReasoning.toDouble())} tokens)" else ""}: lower effort / less thinking saves the most.")
        val readShare = r.calls.sumOf { it.unitsCacheRead } / r.totalUnits.coerceAtLeast(1.0)
        if (readShare > 0.5) out.add("Re-reading the context is ${(readShare * 100).toInt()}% of the weight: long tool loops on a big context cost more per step than they look.")
        r.fiveHour?.let { l ->
            if (l.emptyAtMs != null) {
                val mins = ((l.emptyAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
                out.add("At the last hour's pace the 5-hour limit runs out in ~${mins / 60}h ${mins % 60}m.")
            }
        }
        if (r.savedShare > 0.3) out.add("Caching made this chat ${(r.savedShare * 100).toInt()}% lighter than sending the full context fresh every time.")
        return out
    }

    fun compact(n: Double): String {
        val a = kotlin.math.abs(n)
        val s = when {
            a >= 1_000_000 -> String.format(Locale.US, "%.2fM", a / 1_000_000)
            a >= 10_000 -> String.format(Locale.US, "%.0fk", a / 1_000)
            a >= 1_000 -> String.format(Locale.US, "%.1fk", a / 1_000)
            a >= 10 -> String.format(Locale.US, "%.0f", a)
            else -> String.format(Locale.US, "%.1f", a)
        }
        return if (n < 0) "−$s" else s
    }
}
