package com.example.gemini.domain.usage

import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plan-limit readings over time, as each agent reports them (Claude: every `rate_limit_event`; Codex:
 * `account/rateLimits/updated`; Antigravity: each quota refresh). The usage inspector lines them up with the model
 * requests to show what a turn took from the 5-hour limit and how fast it is burning. In memory only.
 */
object UsageLimitTracker {

    /** One reading of a limit: share used (0..1) at [atMs]; [resetAtMs] when the window starts over. */
    data class Sample(val atMs: Long, val used: Double, val resetAtMs: Long?)

    /** A limit's readings. [scope]: the group it covers ("All models", an Antigravity quota group…). */
    data class Series(val agent: AgentKind, val scope: String, val window: String, val samples: List<Sample>) {
        val latest: Sample? get() = samples.lastOrNull()
    }

    const val FIVE_HOUR = "5h"
    const val WEEKLY = "7d"
    private const val MAX_SAMPLES = 4000

    private val _series = MutableStateFlow<Map<String, Series>>(emptyMap())
    val series: StateFlow<Map<String, Series>> = _series.asStateFlow()

    /** Adds a reading; one equal to the last one only moves its time forward (keeps the list short). */
    @Synchronized
    fun record(agent: AgentKind, scope: String, window: String, used: Double, resetAtMs: Long?, atMs: Long = System.currentTimeMillis()) {
        if (used.isNaN()) return
        val key = "${agent.name}|$scope|$window"
        val cur = _series.value[key]
        val sample = Sample(atMs, used.coerceIn(0.0, 1.0), resetAtMs)
        val samples = cur?.samples.orEmpty()
        val last = samples.lastOrNull()
        val next = when {
            last != null && atMs < last.atMs -> return
            // two equal readings in a row: keep the first (when it started) and the newest (still true now)
            samples.size >= 2 && last != null && last.used == sample.used && samples[samples.size - 2].used == sample.used ->
                samples.dropLast(1) + sample
            else -> samples + sample
        }.takeLast(MAX_SAMPLES)
        _series.value = _series.value + (key to Series(agent, scope, window, next))
    }

    fun forAgent(all: Map<String, Series>, agent: AgentKind, window: String): List<Series> =
        all.values.filter { it.agent == agent && it.window == window }
}
