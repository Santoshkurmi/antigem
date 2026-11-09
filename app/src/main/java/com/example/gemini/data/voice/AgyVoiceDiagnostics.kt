package com.example.gemini.data.voice

import android.os.Build
import com.example.gemini.BuildConfig
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyBridgeService
import com.example.gemini.data.remote.BridgeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The details of a failed voice call, for the call log (Copy, or a screenshot) so a failure can be looked into later:
 * the full error with its stack trace, how far the call got, the phone and app, and — asked right after the failure —
 * whether the hub still answers and what the hub itself logged about the call (through the bridge).
 */
internal object AgyVoiceDiagnostics {
    private const val MAX_STACK_LINES = 40

    /** What the call knew when it failed. */
    data class Facts(
        val error: String,
        val cause: Throwable?,
        val startedAtWall: Long,
        val failedAtWall: Long,
        val sinceStartMs: Long,
        /** ms between the hub accepting the call (HTTP 101) and the failure; null = it never accepted it. */
        val openForMs: Long?,
        val framesIn: Int,
        val lastFrame: String,
        val listened: Boolean,
        /** The hub's HTTP answer when the connection itself failed (code, message, headers). */
        val handshake: String?,
        val closeCode: Int,
        val closeReason: String?,
        val conversationId: String,
        val voice: String,
        val language: String,
        val planModel: Int,
        val linkRetries: Int
    )

    private fun localTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
    private fun utcTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    /** The details known right away (no network). */
    fun immediate(f: Facts): List<String> {
        val out = mutableListOf<String>()
        out += "──── Details of the failure (Copy, or screenshot this) ────"
        out += "Error shown: ${f.error}"
        val c = f.cause
        if (c != null) {
            out += "Exception: ${c.javaClass.name}${c.message?.let { ": $it" } ?: " (no message)"}"
            var cause = c.cause
            var depth = 0
            while (cause != null && cause !== c && depth < 5) {
                out += "Caused by: ${cause.javaClass.name}${cause.message?.let { ": $it" } ?: ""}"
                cause = cause.cause
                depth++
            }
            out += "Stack trace:"
            c.stackTraceToString().lines().drop(1).filter { it.isNotBlank() }.take(MAX_STACK_LINES).forEach { out += "  " + it.trim() }
        } else {
            out += "Exception: none (the app or the hub ended the call with the message above)"
        }
        out += "When: ${localTime(f.failedAtWall)} phone time (UTC ${utcTime(f.failedAtWall)}), " +
            "${"%.1f".format(Locale.US, f.sinceStartMs / 1000.0)} s after the call started at ${localTime(f.startedAtWall)}"
        out += "Hub link: " + when (val open = f.openForMs) {
            null -> "never accepted (no HTTP 101)" + (f.handshake?.let { "; the hub answered: $it" } ?: "")
            else -> "accepted (HTTP 101), ${"%.1f".format(Locale.US, open / 1000.0)} s before the failure"
        }
        out += "Messages from the hub: ${f.framesIn}" + (f.lastFrame.takeIf { it.isNotBlank() }?.let { ", last: $it" } ?: "")
        out += "Voice agent was listening: ${if (f.listened) "yes" else "no"}"
        if (f.closeCode != 0 || !f.closeReason.isNullOrBlank()) out += "Hub closed the link: code ${f.closeCode}${f.closeReason?.let { " ($it)" } ?: ""}"
        if (f.linkRetries > 0) out += "Reconnects before the failure: ${f.linkRetries}"
        out += "Call: chat ${f.conversationId.ifBlank { "(none yet)" }}, voice ${f.voice}, language ${AgyVoicePreferences.acceptLanguage(f.language)}, plan model ${f.planModel}"
        out += "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE})"
        out += "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString("/")}"
        out += "agy: ${BridgeInfo.agyCli.value?.version?.ifBlank { null } ?: "unknown"}"
        out += "Server: " + serverKind()
        return out
    }

    private fun serverKind(): String {
        val host = AuthPreferences.hostOf(AuthPreferences.currentHubUrl)
        return when {
            AuthPreferences.isLoopbackHost(host) && !AuthPreferences.currentRemoteMode -> "this phone (${AuthPreferences.currentHubUrl.substringAfter("://")})"
            AuthPreferences.currentRemoteTunnel -> "remote, through a tunnel"
            else -> "remote server" + (if (AuthPreferences.currentHubUrl.startsWith("https")) " (https)" else " (http)")
        }
    }

    /** What the hub says after the failure: does it still answer, and what it logged (through the bridge). */
    suspend fun fromHub(hubBase: String, startedAtWall: Long, healthClient: OkHttpClient): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        out += "Hub after the failure: " + runCatching {
            healthClient.newCall(Request.Builder().url("$hubBase/voice/health").agyHubHeaders().get().build()).execute().use { "answering (/voice/health HTTP ${it.code})" }
        }.getOrElse { "NOT answering (${it.javaClass.simpleName}${it.message?.let { m -> ": $m" } ?: ""})" }
        out += bridgeDiagnostics(startedAtWall)
        out += "──── End of details ────"
        out
    }

    private fun bridgeDiagnostics(startedAtWall: Long): List<String> {
        val out = mutableListOf<String>()
        val o = try {
            val url = AuthPreferences.currentBridgeHttpUrl.trimEnd('/') + "/api/agy/hub/diag?voice=25"
            AgyBridgeService.instance.client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                when {
                    r.code == 404 -> return listOf("Hub diagnostics: this bridge is older than the app (no /api/agy/hub/diag)")
                    !r.isSuccessful -> return listOf("Hub diagnostics: the bridge answered HTTP ${r.code}")
                    else -> JSONObject(r.body?.string().orEmpty())
                }
            }
        } catch (e: Exception) {
            return listOf("Hub diagnostics: couldn't reach the bridge (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})")
        }
        o.optJSONObject("hub")?.let { h ->
            val started = h.optLong("started_at_ms")
            val running = h.optBoolean("running")
            out += "Hub process: " + (if (running) "running" else "NOT running") +
                (if (started > 0) ", started ${localTime(started)}" else if (h.optBoolean("external")) ", started outside the app" else "") +
                h.optString("status").takeIf { it.isNotBlank() }?.let { ", status $it" }.orEmpty()
            if (started > startedAtWall) out += "  → the hub (re)started after this call began: the earlier hub process ended during the call"
            val exitAt = h.optLong("last_exit_at_ms")
            if (exitAt > 0) out += "Previous hub process ended ${localTime(exitAt)}: ${h.optString("last_exit_error")}"
            h.optString("last_error").takeIf { it.isNotBlank() }?.let { out += "Hub last error: $it" }
            val crash = h.optJSONArray("crash_lines")
            if (crash != null && crash.length() > 0) {
                out += "Hub crash output:"
                for (i in maxOf(0, crash.length() - 60) until crash.length()) out += "  " + crash.optString(i)
            }
            val recent = h.optJSONArray("recent_output")
            if (recent != null && recent.length() > 0) {
                out += "Hub output (last lines):"
                for (i in maxOf(0, recent.length() - 12) until recent.length()) out += "  " + recent.optString(i).take(300)
            }
        }
        val files = o.optJSONArray("voice_log")
        if (files == null || files.length() == 0) {
            out += "Hub voice log: none found" + (o.optString("log_dir").takeIf { it.isNotBlank() }?.let { " in $it" } ?: "") +
                (o.optString("log_error").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        } else {
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val lines = f.optJSONArray("lines")
                val n = lines?.length() ?: 0
                out += "Hub voice log ${f.optString("file")} (written ${clock(f.optLong("modified_ms"))}): " + if (n == 0) "no voice lines" else "last $n lines"
                for (j in 0 until n) out += "  " + lines!!.optString(j)
            }
        }
        return out
    }
}
