package ai.antigem.crash

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import ai.antigem.BuildConfig
import ai.antigem.data.local.chroot.ChrootManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Catches crashes (uncaught exceptions on any thread) and, instead of the app just vanishing, saves a report and opens
 * [CrashActivity] in its own process (`:crash`, fresh memory, so it works after an out-of-memory crash too).
 * Native crashes, freezes and Android killing the app for memory never reach here.
 */
object CrashHandler {
    private const val CRASH_PROCESS_SUFFIX = ":crash"
    private const val MAX_REPORT_CHARS = 200_000

    /** Call first thing in Application.onCreate (in every process). */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        // A crash of the crash screen itself goes to Android's normal handling (no loop of crash screens).
        if (isCrashProcess(app)) return
        // the screens shown before a crash, for the report (memory only, nothing written)
        runCatching { app.registerActivityLifecycleCallbacks(CrashBreadcrumbs.activityCallbacks) }
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val shown = runCatching {
                reportFile(app).apply { parentFile?.mkdirs() }.writeText(buildReport(app, thread, error))
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }.isSuccess
            if (!shown) {
                previous?.uncaughtException(thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    /** True in the `:crash` process, which only shows [CrashActivity] (the app's normal startup is skipped there). */
    fun isCrashProcess(context: Context): Boolean = processName(context).endsWith(CRASH_PROCESS_SUFFIX)

    fun reportFile(context: Context): File = File(context.filesDir, "crash/last_crash.txt")

    private fun processName(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault(context.packageName)

    private fun buildReport(context: Context, thread: Thread, error: Throwable): String {
        val trace = runCatching {
            StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        }.getOrElse { error.toString() }
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        val report = buildString {
            appendLine("AntiGem crash report")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.APPLICATION_ID} ${BuildConfig.BUILD_TYPE}")
            // which release APK it is (normal, -chroot or -x86_64): each has its own R8 mapping
            val variant = when {
                BuildConfig.TARGET_ABI == "x86_64" -> "x86_64"
                BuildConfig.HAS_CHROOT -> "chroot"
                else -> "normal"
            }
            appendLine("Variant: $variant (${BuildConfig.TARGET_ABI})")
            // the Ubuntu chroot is the runtime right now (a chroot APK can run the normal Termux one too)
            val chrootOn = runCatching { ChrootManager.isActive(context).toString() }.getOrDefault("unknown")
            appendLine("isChrootEnabled: $chrootOn")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            // Each line below is read on its own: one that fails says "unknown" and the rest of the report is kept.
            // No blank lines before the trace: CrashActivity takes the error line from after the first blank line.
            appendLine("CPU: ${safe { Build.SUPPORTED_ABIS.joinToString(", ") }}")
            appendLine("Thread: ${thread.name}")
            appendLine("Uptime: ${safe { duration(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) }}")
            appendLine("On screen: ${safe { if (CrashBreadcrumbs.isInForeground) "yes" else "no (in background)" }}")
            appendLine("Open chat agent: ${safe { CrashBreadcrumbs.chatAgent ?: "none" }}")
            appendLine("Memory: used ${(rt.totalMemory() - rt.freeMemory()) / mb} MB of max ${rt.maxMemory() / mb} MB")
            appendLine("Phone memory: ${safe {
                val info = ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
                "${info.availMem / mb} MB free of ${info.totalMem / mb} MB, low memory: ${if (info.lowMemory) "yes" else "no"}"
            }}")
            appendLine("Storage: ${safe { "${context.filesDir.usableSpace / mb} MB free of ${context.filesDir.totalSpace / mb} MB" }}")
            appendLine("Last screens (oldest first):")
            safe { CrashBreadcrumbs.lines().ifEmpty { listOf("none") }.joinToString("\n") { "  $it" } }
                .let { appendLine(it) }
            appendLine()
            append(trace)
        }
        return if (report.length > MAX_REPORT_CHARS) report.take(MAX_REPORT_CHARS) + "\n… (cut)" else report
    }

    /** [read]'s text, or "unknown" when it fails (a crash report must never fail because of one detail). */
    private inline fun safe(read: () -> String): String = runCatching(read).getOrDefault("unknown")

    /** Like "45 s", "12 min 4 s" or "3 h 20 min". */
    private fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }
}
