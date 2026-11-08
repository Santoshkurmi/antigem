package com.example.gemini.domain.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Locale

/** One plan-limit window as the pickers show it: share left (0..1) and when it resets. */
data class QuotaWindowUi(
    val label: String,
    val remaining: Float,
    val resetIso: String? = null,
    val countdown: String? = null
)

/**
 * Models that share the same plan limits: one of Antigravity's quota groups, or Claude's plan-wide / per-model limits.
 * [weekly] lists every weekly limit the group counts against, its own first (Fable: its share, then all models).
 */
data class QuotaGroup(
    val id: String,
    val title: String,
    val fiveHour: QuotaWindowUi? = null,
    val weekly: List<QuotaWindowUi> = emptyList(),
    val description: String = ""
) {
    /** The weekly limit that runs out first. */
    val tightestWeekly: QuotaWindowUi? get() = weekly.minByOrNull { it.remaining }

    /** The windows a section header shows: 5h, then the group's own weekly limit. */
    val headerWindows: List<QuotaWindowUi> get() = listOfNotNull(fiveHour, weekly.firstOrNull())

    /** The window the chat pill shows: 5h when asked for and the group has one, otherwise the tightest weekly limit. */
    fun pillWindow(pref: QuotaPillWindow): QuotaWindowUi? =
        if (pref == QuotaPillWindow.FIVE_HOUR) fiveHour ?: tightestWeekly else tightestWeekly ?: fiveHour

    /** The quota of [modelId] in this group, as the chat pill reads it. */
    fun toModelQuota(modelId: String, pref: QuotaPillWindow): ModelQuota {
        val week = tightestWeekly
        val pill = pillWindow(pref)
        return ModelQuota(
            modelId = modelId,
            remainingFraction = fiveHour?.remaining,
            resetTime = fiveHour?.resetIso,
            usedPercentage = fiveHour?.let { usedPct(it.remaining) },
            resetCountdown = fiveHour?.countdown,
            weeklyRemainingFraction = week?.remaining,
            weeklyUsedPercentage = week?.let { usedPct(it.remaining) },
            weeklyResetCountdown = week?.countdown,
            pillFraction = pill?.remaining,
            pillIsWeekly = pill != null && pill !== fiveHour
        )
    }

    private fun usedPct(remaining: Float) = String.format(Locale.US, "%.1f%%", (1f - remaining) * 100f)
}

/** Which limit the model pill in the chat shows. */
enum class QuotaPillWindow { FIVE_HOUR, WEEKLY }

enum class QuotaAgent(val prefKey: String) { AGY("agy"), CLAUDE("claude") }

/**
 * The user's choices, kept on this device: which group each model counts against (wins over the automatic one, so a
 * wrong guess or a renamed model can be fixed without an app update) and which window the chat pill shows.
 */
object ModelGroupPrefs {
    private const val FILE = "model_quota_groups"

    @Volatile
    private var prefs: SharedPreferences? = null
    private val overrideFlows = QuotaAgent.entries.associateWith { MutableStateFlow<Map<String, String>>(emptyMap()) }
    private val pillFlows = QuotaAgent.entries.associateWith { MutableStateFlow(QuotaPillWindow.FIVE_HOUR) }
    private val overrideViews = overrideFlows.mapValues { it.value.asStateFlow() }
    private val pillViews = pillFlows.mapValues { it.value.asStateFlow() }

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            QuotaAgent.entries.forEach { agent ->
                overrideFlows.getValue(agent).value = parse(p.getString("overrides_${agent.prefKey}", null))
                pillFlows.getValue(agent).value = p.getString("pill_${agent.prefKey}", null)
                    ?.let { runCatching { QuotaPillWindow.valueOf(it) }.getOrNull() } ?: QuotaPillWindow.FIVE_HOUR
            }
            prefs = p
        }
    }

    /** Model key → group id, for the models the user moved. */
    fun overrides(agent: QuotaAgent): StateFlow<Map<String, String>> = overrideViews.getValue(agent)

    fun pillWindow(agent: QuotaAgent): StateFlow<QuotaPillWindow> = pillViews.getValue(agent)

    /** Puts [modelKey] in group [groupId]; null puts it back in its automatic group. */
    fun setGroup(agent: QuotaAgent, modelKey: String, groupId: String?) {
        val flow = overrideFlows.getValue(agent)
        flow.value = if (groupId == null) flow.value - modelKey else flow.value + (modelKey to groupId)
        prefs?.edit()?.putString("overrides_${agent.prefKey}", JSONObject(flow.value).toString())?.apply()
    }

    fun setPillWindow(agent: QuotaAgent, window: QuotaPillWindow) {
        pillFlows.getValue(agent).value = window
        prefs?.edit()?.putString("pill_${agent.prefKey}", window.name)?.apply()
    }

    private fun parse(json: String?): Map<String, String> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())
}

/** The group a model counts against: the user's choice while that group still exists, otherwise [auto]. */
fun resolveQuotaGroup(groups: List<QuotaGroup>, modelKey: String, overrides: Map<String, String>, auto: () -> QuotaGroup?): QuotaGroup? =
    overrides[modelKey]?.let { id -> groups.firstOrNull { it.id == id } } ?: auto()

/** Antigravity's quota groups (RetrieveUserQuotaSummary) and which group each model falls in. */
object AgyQuotaGroups {
    private val TIER_SUFFIX = Regex("-(extra-low|low|medium|med|high)$", RegexOption.IGNORE_CASE)

    /** The model without its Low/Medium/High tier: all tiers of a model share one quota, so they move together. */
    fun modelKey(m: AiModel): String =
        m.baseName.ifBlank { (m.key.ifBlank { m.id }).replace(TIER_SUFFIX, "") }.trim().lowercase(Locale.ROOT)

    fun groups(summary: QuotaSummaryResponse?): List<QuotaGroup> = summary?.groups.orEmpty().map { g ->
        QuotaGroup(
            id = g.groupId.ifBlank { g.groupName.lowercase(Locale.ROOT).replace(" ", "_") },
            title = g.groupName.ifBlank { g.groupId },
            fiveHour = g.fiveHour?.toUi("5h"),
            // a bucket the old parser could not place keeps its own label
            weekly = listOfNotNull(g.weekly?.toUi("7d")),
            description = g.description
        )
    }

    fun groupOf(m: AiModel, groups: List<QuotaGroup>, overrides: Map<String, String>): QuotaGroup? =
        resolveQuotaGroup(groups, modelKey(m), overrides) { autoGroup(m, groups) }

    /**
     * Best guess from the server's own words, most specific first: a group named after the model ("Gemini 4 Argon"),
     * then a group whose description lists it ("Models within this group: Gemini Flash, Gemini Pro"), then the family.
     */
    fun autoGroup(m: AiModel, groups: List<QuotaGroup>): QuotaGroup? {
        if (groups.isEmpty()) return null
        val base = m.baseName.ifBlank { m.displayName }
        val words = words("$base ${m.displayName}")
        // "Gemini Models" is one word ("gemini") and fits every Gemini model, so the group naming the most words wins
        val byTitle = groups
            .map { g -> g to words(g.title) }
            .filter { (_, t) -> t.isNotEmpty() && words.containsAll(t) }
            .maxByOrNull { (_, t) -> t.size }
        if (byTitle != null && byTitle.second.size >= 2) return byTitle.first
        groups.firstOrNull { g ->
            g.description.contains("within this group", ignoreCase = true) &&
                g.description.substringAfter(':').split(',').any { item -> words(item).let { it.isNotEmpty() && words.containsAll(it) } }
        }?.let { return it }
        byTitle?.let { return it.first }
        val name = "${m.displayName} ${m.key} ${m.id}"
        fun has(s: String, w: String) = s.contains(w, ignoreCase = true)
        return when {
            has(name, "argon") -> groups.firstOrNull { has(it.title, "argon") || it.id == "argon" }
            has(name, "claude") || has(name, "gpt") -> groups.firstOrNull { has(it.title, "claude") || has(it.title, "gpt") || it.id == "claude_gpt" }
            else -> groups.firstOrNull { (has(it.title, "gemini") || it.id == "gemini") && !has(it.title, "argon") }
        } ?: groups.first()
    }

    private fun words(s: String): Set<String> =
        s.lowercase(Locale.ROOT).split(Regex("[^a-z0-9.\\-]+")).filter { it.isNotBlank() && it != "models" && it != "and" }.toSet()

    private fun QuotaWindowInfo.toUi(label: String) = QuotaWindowUi(label, remainingFraction, resetTime, countdown.ifBlank { null })
}
