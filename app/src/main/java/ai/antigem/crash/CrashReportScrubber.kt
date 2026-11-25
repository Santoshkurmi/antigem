package ai.antigem.crash

import android.content.Context

/**
 * Takes private details out of a crash report before it is shown and can leave the phone (a public GitHub issue, a
 * copy, a share): the app's folders, IP addresses, the hosts in URLs, e-mail and user@host names, and anything that
 * looks like a token or key. The stack trace (classes, methods, lines) stays as it is.
 */
object CrashReportScrubber {
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b""")
    private val urlHost = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*://)(?:[^/\s@]+@)?[^/\s:"'?#]+""")
    private val email = Regex("""\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b""")
    private val quotedHost = Regex("""(?i)(\bhost\s+")([^"]+)(")""")
    private val keyValue = Regex("""(?i)\b(token|access_token|refresh_token|key|apikey|api_key|secret|password|passwd|pass|auth|sig|signature|code|session|cookie|csrf)(\s*[=:]\s*)([^\s&,;"']+)""")
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val longSecret = Regex("""\b[A-Za-z0-9_\-+/=]{32,}\b""")
    private val secretHeader = Regex("""x-ag[a-z0-9 ]{12}_9qx""", RegexOption.IGNORE_CASE)

    fun scrub(context: Context, report: String): String {
        var s = report
        val pkg = Regex.escape(context.packageName)
        // the app's home first (the common case), then the rest of its data folder
        s = s.replace(Regex("""/data/(?:data|user/\d+)/$pkg/files/home"""), "~")
        s = s.replace(Regex("""/data/(?:data|user/\d+|user_de/\d+)/$pkg"""), "<app>")
        s = s.replace(Regex("""/storage/emulated/\d+"""), "/sdcard")
        s = s.replace(secretHeader, "<secret header>")
        s = s.replace(bearer) { "${it.groupValues[1]} <hidden>" }
        s = s.replace(keyValue) { "${it.groupValues[1]}${it.groupValues[2]}<hidden>" }
        s = s.replace(urlHost) { "${it.groupValues[1]}<host>" }
        s = s.replace(email, "<user@host>")
        s = s.replace(quotedHost) { "${it.groupValues[1]}<host>${it.groupValues[3]}" }
        s = s.replace(ipv4, "<ip>")
        s = s.replace(ipv6, "<ip>")
        s = s.replace(longSecret, "<hidden>")
        return s
    }
}
