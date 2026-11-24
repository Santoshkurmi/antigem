package ai.antigem.data.remote

import ai.antigem.data.preferences.AuthPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ai.antigem.i18n.L

/**
 * Antigravity account switcher (Settings > Antigravity > Account & CLI). The bridge does the work: it keeps copies of
 * agy's login file in ~/.gemini/antigem-accounts and swaps them in (see agy_accounts.go). The app only gets emails
 * and dates, never tokens. After a switch the app re-checks the sign-in right away ([AgyBridgeService.agyAccountEvents]).
 */
object AgyAccounts {

    data class Account(val name: String, val email: String, val authMethod: String, val savedAt: Long, val usedAt: Long, val active: Boolean)
    data class Current(val signedIn: Boolean, val email: String, val saved: Boolean, val error: String?)
    data class State(val supported: Boolean, val current: Current, val accounts: List<Account>)

    /** The signed-in login has no saved copy: the caller asks to save it first or to go on without it. */
    class NotSavedException(val email: String) : Exception("$email is not saved")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonType = "application/json".toMediaType()

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Set by the Antigravity backend while it lives (reset in its dispose): a reply is running right now. */
    @Volatile
    var replyRunning: () -> Boolean = { false }

    /**
     * After "Add account" cleared the login: the next account that signs in is saved on its own. Only for that
     * sign-in; forgotten once used.
     */
    @Volatile
    var saveNextSignIn = false

    private fun base() = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')

    private fun parse(o: JSONObject): State {
        val cur = o.optJSONObject("current") ?: JSONObject()
        val arr = o.optJSONArray("accounts")
        val list = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                add(Account(a.optString("name"), a.optString("email"), a.optString("authMethod"), a.optLong("savedAt"), a.optLong("usedAt"), a.optBoolean("active")))
            }
        }
        return State(
            supported = o.optBoolean("supported", true),
            current = Current(cur.optBoolean("signedIn"), cur.optString("email"), cur.optBoolean("saved"), cur.optString("error").takeIf { it.isNotBlank() }),
            accounts = list
        )
    }

    private fun call(action: String?, body: JSONObject? = null): State {
        val url = if (action == null) "${base()}/api/agy/accounts" else "${base()}/api/agy/accounts/$action"
        val req = Request.Builder().url(url).apply {
            if (body != null) post(body.toString().toRequestBody(jsonType)) else get()
        }.build()
        AgyBridgeService.instance.client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.code == 404 && action == null) error("The bridge is older than the app (no account switching). Update the bridge.")
            val o = runCatching { JSONObject(text) }.getOrElse { error("The bridge answered HTTP ${r.code}") }
            if (r.code == 409 && o.optString("error") == "current_not_saved") throw NotSavedException(o.optString("email"))
            if (!r.isSuccessful || !o.optBoolean("success")) error(o.optString("error").ifBlank { L.remote.agyAccounts.theBridgeAnsweredHttpCode(r.code) })
            return parse(o)
        }
    }

    fun refresh() {
        scope.launch {
            runCatching { call(null) }
                .onSuccess { _state.value = it; _error.value = null }
                .onFailure { _error.value = it.message ?: it.javaClass.simpleName }
        }
    }

    /**
     * Runs one action on the bridge, one at a time. [onDone] gets null on success, the failure otherwise
     * ([NotSavedException] when the signed-in login would be lost). [accountChanged]: agy's login was swapped.
     */
    private fun perform(action: String, body: JSONObject, accountChanged: Boolean, onDone: (Throwable?) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            val res = runCatching { call(action, body) }
            res.onSuccess { _state.value = it; _error.value = null }
            if (res.isSuccess && accountChanged) AgyBridgeService.instance.notifyAgyAccountChanged()
            _busy.value = false
            val err = res.exceptionOrNull()
            if (err != null && err !is NotSavedException) _error.value = err.message ?: err.javaClass.simpleName
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(err) }
        }
    }

    fun saveCurrent(onDone: (Throwable?) -> Unit = {}) = perform("save", JSONObject(), accountChanged = false, onDone)

    fun switchTo(email: String, saveCurrent: Boolean = false, force: Boolean = false, onDone: (Throwable?) -> Unit = {}) =
        perform("switch", JSONObject().put("email", email).put("saveCurrent", saveCurrent).put("force", force), accountChanged = true, onDone)

    /** Add account: agy is signed out here (no logout call, so saved copies stay valid), ready for another sign-in. */
    fun clearForNewSignIn(saveCurrent: Boolean = false, force: Boolean = false, onDone: (Throwable?) -> Unit = {}) =
        perform("clear", JSONObject().put("saveCurrent", saveCurrent).put("force", force), accountChanged = true) { err ->
            if (err == null) saveNextSignIn = true
            onDone(err)
        }

    fun delete(email: String, onDone: (Throwable?) -> Unit = {}) =
        perform("delete", JSONObject().put("email", email), accountChanged = false, onDone)
}
