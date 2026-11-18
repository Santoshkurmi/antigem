package com.example.gemini.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.example.gemini.BuildConfig
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
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE}")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine("Memory: used ${(rt.totalMemory() - rt.freeMemory()) / mb} MB of max ${rt.maxMemory() / mb} MB")
            appendLine()
            append(trace)
        }
        return if (report.length > MAX_REPORT_CHARS) report.take(MAX_REPORT_CHARS) + "\n… (cut)" else report
    }
}
