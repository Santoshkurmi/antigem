package com.example.gemini.data.changelog

import com.example.gemini.BuildConfig
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One released version, from version.json at the commit that last changed it (see the generateChangelog Gradle task). */
data class ChangelogEntry(
    val versionCode: Int,
    val versionName: String,
    /** yyyy-MM-dd of that commit, or null for a version that isn't committed yet. */
    val date: String?,
    val changes: List<String>
)

/** What a line of the changelog is, guessed from its first word. */
enum class ChangeKind(val label: String) { NEW("New"), FIXED("Fixed"), IMPROVED("Improved"), REMOVED("Removed") }

class ChangeLine(val text: String, val kind: ChangeKind)

/** A version ready to show: everything the page needs is worked out once, not while scrolling. */
class ChangelogVersion(
    val versionCode: Int,
    val versionName: String,
    /** "Nov 4, 2026", or null when not dated. */
    val dateLabel: String?,
    /** "Today", "3 days ago", "2 months ago"…, or null when not dated. */
    val relativeLabel: String?,
    /** "November 2026", or null when not dated; the page starts a month section when this changes. */
    val monthLabel: String?,
    /** "Nov 2026", or null when not dated. */
    val shortMonthLabel: String?,
    val lines: List<ChangeLine>,
    val counts: Map<ChangeKind, Int>,
    val isInstalled: Boolean,
    val isLatest: Boolean
)

object Changelog {
    /** Newest first. Built on first use (a few dozen entries, so it's cheap). */
    val versions: List<ChangelogVersion> by lazy { build(GeneratedChangelog.entries) }

    /** The date of the first release ("Sep 30, 2026"), for the end of the timeline. */
    val firstDateLabel: String? get() = versions.lastOrNull { it.dateLabel != null }?.dateLabel

    /** The month of the first release ("Sep 2026"), for the page header. */
    val firstMonthLabel: String? get() = versions.lastOrNull { it.shortMonthLabel != null }?.shortMonthLabel

    private fun build(entries: List<ChangelogEntry>): List<ChangelogVersion> {
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val day = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val month = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val shortMonth = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val today = Calendar.getInstance().apply { clearTime() }
        val newest = entries.maxOfOrNull { it.versionCode }
        return entries.sortedByDescending { it.versionCode }.map { e ->
            val parsed = e.date?.let { runCatching { iso.parse(it) }.getOrNull() }
            val lines = e.changes.map { ChangeLine(it.capitalizeFirst(), kindOf(it)) }
            ChangelogVersion(
                versionCode = e.versionCode,
                versionName = e.versionName,
                dateLabel = parsed?.let(day::format),
                relativeLabel = parsed?.let { relative(today, Calendar.getInstance().apply { time = it; clearTime() }) },
                monthLabel = parsed?.let(month::format),
                shortMonthLabel = parsed?.let(shortMonth::format),
                lines = lines,
                counts = lines.groupingBy { it.kind }.eachCount(),
                isInstalled = e.versionCode == BuildConfig.VERSION_CODE,
                isLatest = e.versionCode == newest
            )
        }
    }

    private fun kindOf(line: String): ChangeKind {
        val first = line.trim().lowercase().split(' ', '(', ':', ',').firstOrNull().orEmpty()
        return when {
            first.startsWith("add") || first.startsWith("new") || first.startsWith("introduc") || first == "initial" -> ChangeKind.NEW
            first.startsWith("fix") || first.startsWith("bug") || first == "resolved" -> ChangeKind.FIXED
            first.startsWith("remov") || first.startsWith("drop") || first.startsWith("delet") -> ChangeKind.REMOVED
            else -> ChangeKind.IMPROVED
        }
    }

    private fun relative(today: Calendar, date: Calendar): String {
        val days = ((today.timeInMillis - date.timeInMillis) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "Today"
            days == 1 -> "Yesterday"
            days < 7 -> "$days days ago"
            days < 14 -> "Last week"
            days < 31 -> "${days / 7} weeks ago"
            days < 61 -> "Last month"
            days < 365 -> "${days / 30} months ago"
            days < 730 -> "Last year"
            else -> "${days / 365} years ago"
        }
    }

    private fun Calendar.clearTime() {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun String.capitalizeFirst() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}
