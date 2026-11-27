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
 * On app launch, while the app is shown in a community language: looks in the background for a newer version of that
 * language in the repository ([LanguageRepo]) and offers it ([offer]). MainActivity shows the offer only once the app's
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    /** Once per run (an activity rebuilt by a rotation doesn't check again); English and the user's own languages have no updates. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            runCatching { check(app) }.onFailure { android.util.Log.w("LanguageUpdateCheck", "check failed", it) }
        }
    }

    private suspend fun check(context: Context) {
        val id = LanguageManager.currentId
        if (!LanguageManager.isRepoId(id)) return
        delay(START_DELAY_MS)
        LanguageRepo.refresh().getOrNull() ?: return
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
