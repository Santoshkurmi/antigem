package com.example.gemini.data.agent.claude

import com.example.gemini.domain.model.QuotaGroup
import com.example.gemini.domain.model.QuotaWindowUi
import com.example.gemini.domain.model.resolveQuotaGroup
import java.util.Locale

/**
 * Claude's plan limits as quota groups. The 5-hour limit and the weekly limit cover every model ("All models"); a
 * model with its own weekly share (Fable on Max: at most half of the weekly limit) gets its own group, which counts
 * against the 5-hour limit, its own share and the all-models weekly limit.
 */
object ClaudeQuotaGroups {
    const val ALL = "all"

    /** A model's own weekly limit: name ("Fable"), share used (0..1), reset time. */
    data class ScopedLimit(val name: String, val used: Double, val resetsAt: String?)

    /** The per-model weekly limits: `limits[]` rows of kind weekly_scoped, else `model_scoped[]`, else the older fields. */
    fun scopedLimits(rl: ClaudeRateLimits?): List<ScopedLimit> {
        if (rl == null) return emptyList()
        val fromLimits = rl.limits.mapNotNull { e ->
            val name = e.scope?.model?.display_name?.takeIf { it.isNotBlank() }
            if (e.kind == "weekly_scoped" && name != null) ScopedLimit(name, e.percent / 100.0, e.resets_at) else null
        }
        val fromModelScoped = rl.model_scoped.mapNotNull { s ->
            val name = s.display_name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ScopedLimit(name, (s.utilization ?: 0.0) / 100.0, s.resets_at)
        }
        val legacy = listOfNotNull(
            rl.seven_day_opus?.let { ScopedLimit("Opus", (it.utilization ?: 0.0) / 100.0, it.resets_at) },
            rl.seven_day_sonnet?.let { ScopedLimit("Sonnet", (it.utilization ?: 0.0) / 100.0, it.resets_at) }
        )
        val merged = LinkedHashMap<String, ScopedLimit>()
        (fromLimits + fromModelScoped + legacy).forEach { merged.putIfAbsent(it.name.lowercase(Locale.ROOT), it) }
        return merged.values.toList()
    }

    /**
     * The groups for the plan, or none when there are no plan limits (API billing, not loaded yet).
     * [live] (the chat stream's windows, fresher than the cached usage call) wins for the 5h / 7d numbers.
     */
    fun groups(usage: ClaudeUsageInfo?, live: Map<String, ClaudeUnifiedWindow>): List<QuotaGroup> {
        val rl = usage?.rate_limits
        val five = live["five_hour"]?.let { QuotaWindowUi("5h", left(it.utilization), it.resetsAt?.let(::isoUtc)) }
            ?: rl?.five_hour?.let { QuotaWindowUi("5h", left((it.utilization ?: 0.0) / 100.0), it.resets_at) }
        val week = live["seven_day"]?.let { QuotaWindowUi("7d", left(it.utilization), it.resetsAt?.let(::isoUtc)) }
            ?: rl?.seven_day?.let { QuotaWindowUi("7d", left((it.utilization ?: 0.0) / 100.0), it.resets_at) }
        val scoped = scopedLimits(rl)
        if (five == null && week == null && scoped.isEmpty()) return emptyList()
        return listOf(QuotaGroup(ALL, "All models", five, listOfNotNull(week))) + scoped.map { s ->
            QuotaGroup(
                id = groupId(s.name),
                title = s.name,
                fiveHour = five,
                // its own share first (shown in the header), then the all-models limit it also counts against
                weekly = listOfNotNull(QuotaWindowUi("7d", left(s.used), s.resetsAt), week?.copy(label = "7d all"))
            )
        }
    }

    fun groupId(name: String) = "model:" + name.lowercase(Locale.ROOT)

    /** The group of model [value] ("fable", "claude-fable-5", "opus"…): the user's choice, else by name. */
    fun groupOf(value: String, displayName: String?, groups: List<QuotaGroup>, overrides: Map<String, String>): QuotaGroup? =
        resolveQuotaGroup(groups, value, overrides) { autoGroup(value, displayName, groups) }

    fun autoGroup(value: String, displayName: String?, groups: List<QuotaGroup>): QuotaGroup? {
        val name = "$value ${displayName.orEmpty()}".lowercase(Locale.ROOT)
        return groups.firstOrNull { it.id != ALL && name.contains(it.title.lowercase(Locale.ROOT)) }
            ?: groups.firstOrNull { it.id == ALL }
    }

    private fun left(used: Double) = (1.0 - used).toFloat().coerceIn(0f, 1f)

    private fun isoUtc(epochSeconds: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(epochSeconds * 1000))
}
