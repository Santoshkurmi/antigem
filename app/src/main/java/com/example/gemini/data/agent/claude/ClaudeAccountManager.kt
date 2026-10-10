package com.example.gemini.data.agent.claude

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Claude Code account: CLI/auth status, plan usage, OAuth sign-in and sign-out (all through the bridge). */
class ClaudeAccountManager(
    private val scope: CoroutineScope,
    private val client: ClaudeBridgeClient
) {
    private val _status = MutableStateFlow<ClaudeCliStatus?>(null)
    /** `claude --version` + `claude auth status --json`; null until the first check. */
    val status: StateFlow<ClaudeCliStatus?> = _status.asStateFlow()

    private val _statusError = MutableStateFlow<String?>(null)
    /** Why the last check failed (bridge unreachable, outdated, `auth status` error); null when it worked. */
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    /** Set when the last check failed: BRIDGE_OFFLINE, BRIDGE_OUTDATED or ERROR. */
    private val _failure = MutableStateFlow<ClaudeStatus?>(null)
    private val _checking = MutableStateFlow(false)

    private val _usage = MutableStateFlow<ClaudeUsageInfo?>(null)
    val usage: StateFlow<ClaudeUsageInfo?> = _usage.asStateFlow()

    private val _login = MutableStateFlow<ClaudeLoginState?>(null)
    /** In-progress sign-in (null when none). */
    val login: StateFlow<ClaudeLoginState?> = _login.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off feedback for toasts. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Called after sign-in / sign-out so model info and chats refresh. */
    var onAccountChanged: () -> Unit = {}

    /** One status for the UI: the last check plus any sign-in in progress. */
    val state: StateFlow<ClaudeStatus> = combine(_status, _failure, _login, _checking) { st, failure, login, checking ->
        when {
            login != null -> ClaudeStatus.SIGNING_IN
            // re-checking after a failure (or before the first answer) shows progress; a periodic re-check does not
            checking && (failure != null || st == null) -> ClaudeStatus.CHECKING
            failure != null -> failure
            st == null -> ClaudeStatus.CHECKING
            !st.installed -> ClaudeStatus.NOT_INSTALLED
            st.auth == null && st.auth_error != null -> ClaudeStatus.ERROR
            st.auth?.loggedIn == true -> ClaudeStatus.READY
            else -> ClaudeStatus.SIGNED_OUT
        }
    }.stateIn(scope, SharingStarted.Eagerly, ClaudeStatus.CHECKING)

    private var pollJob: Job? = null
    private var statusJob: Job? = null

    val isLoggedIn: Boolean get() = _status.value?.auth?.loggedIn == true

    fun refreshStatus() {
        statusJob?.cancel()
        _checking.value = true
        statusJob = scope.launch {
            var result = client.status()
            // right after launch the local server is still coming up: keep "Checking…" and look again
            while (result.exceptionOrNull()?.isBridgeUnreachable() == true && BridgeStartup.isStartingUp()) {
                delay(2_000)
                if (!isActive) return@launch
                result = client.status()
            }
            if (!isActive) return@launch
            if (result.isSuccess || result.exceptionOrNull() is ClaudeBridgeOutdatedException) BridgeStartup.markReached()
            result
                .onSuccess {
                    _status.value = it
                    _failure.value = null
                    _statusError.value = it.auth_error?.let { e -> "Could not read the sign-in state: $e" }
                    if (it.auth?.loggedIn == true) refreshUsage(false) else _usage.value = null
                }
                .onFailure {
                    val failure = classify(it)
                    _failure.value = failure
                    _statusError.value = when (failure) {
                        ClaudeStatus.BRIDGE_OUTDATED -> it.message
                        ClaudeStatus.BRIDGE_OFFLINE -> "Could not reach the bridge" + (it.message?.let { m -> " ($m)." } ?: ".")
                        else -> it.message ?: "Status check failed"
                    }
                }
            _checking.value = false
        }
    }

    /** The AGY connection saw the bridge go away; Claude goes through the same bridge. */
    fun onBridgeOffline() {
        statusJob?.cancel()
        _checking.value = false
        _failure.value = ClaudeStatus.BRIDGE_OFFLINE
        _statusError.value = "The bridge is not running."
    }

    private fun classify(error: Throwable): ClaudeStatus = when {
        error is ClaudeBridgeOutdatedException -> ClaudeStatus.BRIDGE_OUTDATED
        error.isBridgeUnreachable() -> ClaudeStatus.BRIDGE_OFFLINE
        else -> ClaudeStatus.ERROR
    }

    private val _isRefreshingUsage = MutableStateFlow(false)
    val isRefreshingUsage: StateFlow<Boolean> = _isRefreshingUsage.asStateFlow()

    private val _usageError = MutableStateFlow<String?>(null)
    /** Why the last plan-usage refresh failed (null after a success). */
    val usageError: StateFlow<String?> = _usageError.asStateFlow()

    private val _usageUpdatedAt = MutableStateFlow(0L)
    /** When plan usage was last loaded (ms). */
    val usageUpdatedAt: StateFlow<Long> = _usageUpdatedAt.asStateFlow()

    fun refreshUsage(force: Boolean = true) {
        if (_isRefreshingUsage.value) return
        _isRefreshingUsage.value = true
        scope.launch {
            client.usage(force)
                .onSuccess { resp ->
                    if (resp.success) {
                        _usage.value = resp.usage
                        _usageError.value = null
                        _usageUpdatedAt.value = System.currentTimeMillis()
                    } else _usageError.value = resp.error ?: "Claude Code did not return usage"
                }
                .onFailure { _usageError.value = it.message ?: "Cannot reach the bridge" }
            _isRefreshingUsage.value = false
        }
    }

    /** method: "claudeai" (Pro/Max subscription) or "console" (API billing). */
    fun startLogin(method: String) {
        if (_isBusy.value) return
        _isBusy.value = true
        scope.launch {
            client.startLogin(method)
                .onSuccess { resp ->
                    val st = resp.login
                    if (resp.success && st != null) {
                        _login.value = st
                        pollLogin(st.login_id)
                    } else {
                        _messages.tryEmit("Sign-in could not start: ${resp.error ?: "unknown error"}")
                        _isBusy.value = false
                    }
                }
                .onFailure {
                    _messages.tryEmit("Sign-in could not start: ${it.message}")
                    _isBusy.value = false
                }
        }
    }

    /** The code shown on platform.claude.com after a manual sign-in (`code#state`). */
    fun submitCode(code: String) {
        val id = _login.value?.login_id ?: return
        scope.launch {
            client.submitLoginCode(id, code)
                .onSuccess { if (!it.success) _messages.tryEmit("Code rejected: ${it.error}") }
                .onFailure { _messages.tryEmit("Could not send code: ${it.message}") }
        }
    }

    fun cancelLogin() {
        val id = _login.value?.login_id
        pollJob?.cancel()
        _login.value = null
        _isBusy.value = false
        if (id != null) scope.launch { client.cancelLogin(id) }
    }

    fun logout() {
        scope.launch {
            _isBusy.value = true
            client.logout()
                .onSuccess {
                    if (it.success) _messages.tryEmit("Signed out of Claude") else _messages.tryEmit("Sign-out failed: ${it.error}")
                }
                .onFailure { _messages.tryEmit("Sign-out failed: ${it.message}") }
            _isBusy.value = false
            _usage.value = null
            refreshStatus()
            onAccountChanged()
        }
    }

    private fun pollLogin(id: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(1500)
                val st = client.loginState(id).getOrNull()?.login ?: continue
                _login.value = st
                when (st.state) {
                    "success" -> {
                        _messages.tryEmit("Signed in to Claude")
                        finishLogin()
                        return@launch
                    }
                    "error", "cancelled" -> {
                        _messages.tryEmit("Sign-in failed: ${st.error ?: st.state}")
                        _login.value = null
                        _isBusy.value = false
                        return@launch
                    }
                }
            }
        }
    }

    private fun finishLogin() {
        _login.value = null
        _isBusy.value = false
        refreshStatus()
        onAccountChanged()
    }
}
