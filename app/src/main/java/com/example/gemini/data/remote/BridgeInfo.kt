package com.example.gemini.data.remote

import android.content.Context
import com.example.gemini.BuildConfig
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * What the connected IDE bridge (local or on a server) says about itself:
 *  - its version, which must be the app's version (the bridge is built with the app's version.json),
 *  - the Antigravity CLI (`agy`) on its machine: where it is, its `agy --version`, and a way to install it.
 */
object BridgeInfo {

    data class AgyCli(val installed: Boolean, val path: String, val version: String)
    data class Job(val kind: String, val running: Boolean, val exitCode: Int?, val log: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    const val appVersion: String = BuildConfig.VERSION_NAME

    private val _version = MutableStateFlow<String?>(null)
    /** Version reported by the connected bridge, or null while unknown / not connected. */
    val version: StateFlow<String?> = _version.asStateFlow()

    private val _agyCli = MutableStateFlow<AgyCli?>(null)
    val agyCli: StateFlow<AgyCli?> = _agyCli.asStateFlow()

    private val _agyError = MutableStateFlow<String?>(null)
    val agyError: StateFlow<String?> = _agyError.asStateFlow()

    private val _job = MutableStateFlow<Job?>(null)
    val job: StateFlow<Job?> = _job.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private fun base() = AuthPreferences.currentBridgeHttpUrl.trimEnd('/')

    /** Forget what was learned (the bridge went away or is about to restart). */
    fun reset() {
        _version.value = null
    }

    /** Reads the bridge version (cheap; the health endpoint needs no token). */
    fun refreshVersion() {
        scope.launch {
            val v = runCatching {
                val req = Request.Builder().url("${base()}/api/health").get().build()
                AgyBridgeService.instance.fastClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optString("version").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (v != null) _version.value = v
        }
    }

    fun refreshVersionIfUnknown() {
        if (_version.value == null) refreshVersion()
    }

    private fun parseJob(o: JSONObject?): Job? = o?.let {
        Job(it.optString("kind"), it.optBoolean("running"), if (it.has("exit_code")) it.optInt("exit_code") else null, cleanAgyLog(it.optString("log")))
    }

    /**
     * agy writes a startup log line before it is set up, on Android "ERROR: logging before google.Init: I… Initialized
     * Android local timezone…". It is not an error; it is left out of what the app shows.
     */
    private fun isAgyStartupLog(line: String) = line.trimStart().startsWith("ERROR: logging before google.Init")

    private fun cleanAgyLog(log: String): String =
        if (!log.contains("logging before google.Init")) log else log.lines().filterNot(::isAgyStartupLog).joinToString("\n")

    private val versionRe = Regex("""^v?\d+(\.\d+)+\S*$""")

    /** The version from `agy --version` (an older bridge may hand over the startup log line instead: "" then). */
    private fun agyVersionOf(raw: String): String =
        raw.lines().map { it.trim() }.firstOrNull { versionRe.matches(it) } ?: ""

    /** Asks the bridge where `agy` is and which version it is (`agy --version`). */
    fun refreshAgyCli() {
        scope.launch {
            _checking.value = true
            try {
                val res = runCatching {
                    val req = Request.Builder().url("${base()}/api/agy/cli").get().build()
                    AgyBridgeService.instance.client.newCall(req).execute().use { r ->
                        when {
                            r.code == 404 -> error("The bridge is older than the app (no agy support). Update the bridge.")
                            !r.isSuccessful -> error("The bridge answered HTTP ${r.code}")
                            else -> JSONObject(r.body?.string().orEmpty())
                        }
                    }
                }
                res.onSuccess { o ->
                    _agyCli.value = AgyCli(o.optBoolean("installed"), o.optString("path"), agyVersionOf(o.optString("version")))
                    _agyError.value = null
                    parseJob(o.optJSONObject("job"))?.let { if (_job.value?.running != true) _job.value = it }
                }.onFailure {
                    _agyError.value = "Cannot reach the bridge: ${it.message ?: it.javaClass.simpleName}"
                }
            } finally {
                _checking.value = false
            }
        }
    }

    /** Runs the official installer on the bridge's machine and follows its output. */
    fun installAgy(onFinished: (Boolean) -> Unit = {}) = runAgyJob("install", onFinished)

    private val _agyUpdatedTo = MutableStateFlow<String?>(null)
    /** The agy version the last Check for updates installed (the server was restarted for it), or null. */
    val agyUpdatedTo: StateFlow<String?> = _agyUpdatedTo.asStateFlow()

    /**
     * Runs `agy update` on the bridge's machine (agy's own updater: Google's newest release for that platform, the
     * Android build on Termux) and follows its output. A running hub keeps the agy it started with, so when a newer
     * agy was installed the server restarts right away (chats running a reply are stopped); already up to date:
     * nothing restarts.
     */
    fun updateAgy(context: Context) {
        val app = context.applicationContext
        runAgyJob("update") { ok ->
            // the bridge writes "Updated agy <old> -> <new>." only when the version changed
            val to = _job.value?.log?.let { log ->
                Regex("""^Updated agy (\S+) -> (\S+)\.$""", RegexOption.MULTILINE).findAll(log).lastOrNull()?.groupValues?.get(2)
            }
            if (!ok || to == null) return@runAgyJob
            _agyUpdatedTo.value = to
            com.example.gemini.ui.components.AppToastHelper.showToast(
                "agy updated to $to. Restarting the server to use it…",
                com.example.gemini.ui.components.ChatToastType.SUCCESS
            )
            com.example.gemini.data.local.LocalServerManager.restartServer(app)
        }
    }

    private fun runAgyJob(action: String, onFinished: (Boolean) -> Unit) {
        val label = action.replaceFirstChar { it.uppercase() }
        if (action == "update") _agyUpdatedTo.value = null
        scope.launch {
            val started = runCatching {
                val req = Request.Builder().url("${base()}/api/agy/cli/$action").post("{}".toRequestBody(null)).build()
                AgyBridgeService.instance.client.newCall(req).execute().use { r ->
                    if (r.code == 404) error("The bridge is older than the app (no agy $action). Update the bridge.")
                    JSONObject(r.body?.string().orEmpty())
                }
            }
            val o = started.getOrElse { _agyError.value = "$label failed to start: ${it.message}"; return@launch }
            if (!o.optBoolean("success")) {
                _agyError.value = o.optString("error").ifBlank { "$label failed to start" }
                return@launch
            }
            _agyError.value = null
            _job.value = parseJob(o.optJSONObject("job"))
            while (true) {
                delay(1500)
                val snap = runCatching {
                    val req = Request.Builder().url("${base()}/api/agy/cli").get().build()
                    AgyBridgeService.instance.client.newCall(req).execute().use { r -> JSONObject(r.body?.string().orEmpty()) }
                }.getOrNull() ?: continue
                val j = parseJob(snap.optJSONObject("job")) ?: continue
                _job.value = j
                if (!j.running) {
                    _agyCli.value = AgyCli(snap.optBoolean("installed"), snap.optString("path"), agyVersionOf(snap.optString("version")))
                    onFinished(j.exitCode == 0)
                    break
                }
            }
        }
    }

    // -------------------------------------------------------------------------------- version comparison

    /** `-1` when the bridge is older than the app, `1` when newer, `0` when equal. */
    fun compareBridgeToApp(bridge: String): Int {
        if (bridge == appVersion) return 0
        // bridges built before versioning always reported a fixed "2.0.0": that is an old bridge, not a newer one
        if (bridge == LEGACY_VERSION) return -1
        fun parts(v: String) = v.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(bridge); val b = parts(appVersion)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    // -------------------------------------------------------------------------------- "Later"

    private const val LEGACY_VERSION = "2.0.0"

    private val _snoozedFor = MutableStateFlow<String?>(null)
    /** The bridge version whose update dialog was answered with "Later". Lives only until the app process ends, so the dialog comes back on the next launch. */
    val snoozedFor: StateFlow<String?> = _snoozedFor.asStateFlow()

    fun snooze(bridgeVersion: String) {
        _snoozedFor.value = bridgeVersion
    }
}
