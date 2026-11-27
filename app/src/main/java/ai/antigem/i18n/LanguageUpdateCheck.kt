package ai.antigem.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Each time the app comes to the foreground (at most every [MIN_INTERVAL_MS]), while the app is shown in a community
 * language: looks in the background for a newer version of that language in the repository ([LanguageRepo]) and offers
 * it ([offer]). MainActivity shows the offer only once the app's
 * own update check is done and its dialog is closed, so the two never show at the same time. No notification: a
 * language update isn't worth one. "Not now" skips that version (a newer one is offered again); the Language page
 * still shows the update either way.
 */
object LanguageUpdateCheck {
    /** A newer version of the language on screen: [fromPercent] is how much of the installed one is translated. */
    class Offer(val pack: LanguageRepo.Pack, val fromPercent: Int?)

    /** The update to offer, or null. */
    var offer by mutableStateOf<Offer?>(null)
        private set

    private const val PREFS = "app_language"
    private const val SKIPPED = "skipped_update"
    /** The first screen draws before the check touches the disk and the network. */
    private const val START_DELAY_MS = 1500L
    /**
     * Checks at most this often. Android keeps the app's process alive after it's closed, so "once per run" meant
     * reopening the app never checked again (and a check made before the new list was on GitHub was never redone).
     * This also keeps a rotation or a quick return to the app from checking again.
     */
    private const val MIN_INTERVAL_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var checking = false
    @Volatile private var lastCheckAt = 0L

    /**
     * When the app comes to the foreground (MainActivity, ON_START). English and the user's own languages have no
     * updates; nothing is checked while an offer is still waiting to be shown.
     */
    @Synchronized
    fun start(context: Context) {
        val now = System.currentTimeMillis()
        if (checking || offer != null || (lastCheckAt != 0L && now - lastCheckAt < MIN_INTERVAL_MS)) return
        checking = true
        lastCheckAt = now
        val app = context.applicationContext
        scope.launch {
            try {
                runCatching { check(app) }.onFailure { android.util.Log.w("LanguageUpdateCheck", "check failed", it) }
            } finally {
                checking = false
            }
        }
    }

    private suspend fun check(context: Context) {
        val id = LanguageManager.currentId
        if (!LanguageManager.isRepoId(id)) return
        delay(START_DELAY_MS)
        // offline or GitHub unreachable: try again the next time the app comes to the foreground
        LanguageRepo.refresh().getOrNull() ?: run { lastCheckAt = 0L; return }
        val pack = wanted(id) ?: return
        if (prefs(context).getString(SKIPPED, null) == skipKey(pack)) return
        val from = LanguageManager.load(id)?.let { LanguageManager.info(it) }
            ?.let { if (it.total <= 0) null else (it.translated * 100L / it.total).toInt().coerceIn(0, 100) }
        // the user may have switched language or updated it from the Language page meanwhile
        if (wanted(LanguageManager.currentId)?.sha256 == pack.sha256) offer = Offer(pack, from)
    }

    /** The waiting update of [id] (a community language on screen), or null. */
    private fun wanted(id: String): LanguageRepo.Pack? {
        if (!LanguageManager.isRepoId(id)) return null
        val packId = id.removePrefix(LanguageManager.REPO_PREFIX)
        if (LanguageRepo.stateOf(packId) != LanguageRepo.State.UPDATE) return null
        return LanguageRepo.packs.firstOrNull { it.id == packId }
    }

    /** Whether [o] is still worth showing (not updated or switched away from since it was found). */
    fun stillWanted(o: Offer): Boolean = wanted(LanguageManager.currentId)?.id == o.pack.id

    /** "Not now": this version isn't offered again. */
    fun notNow(context: Context) {
        offer?.let { prefs(context).edit().putString(SKIPPED, skipKey(it.pack)).apply() }
        offer = null
    }

    /** Updated (or no longer wanted): nothing to offer. */
    fun clear() {
        offer = null
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun skipKey(p: LanguageRepo.Pack) = "${p.id}:${p.sha256}"
}
