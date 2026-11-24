package ai.antigem.data.homescreen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import ai.antigem.R
import ai.antigem.data.local.LocalEnvironmentManager
import ai.antigem.data.local.LocalTerminalManager
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.run.RunManager
import ai.antigem.data.run.RunStatus
import ai.antigem.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import ai.antigem.i18n.L

/**
 * Scripts on the home screen, the way Termux:Widget does it: every file in `~/.shortcuts` runs in a new terminal tab,
 * files in `~/.shortcuts/tasks` run in the background (as a Run, with a notification when they end), and files in other
 * folders of `~/.shortcuts` show as "folder/name". Only files inside `~/.shortcuts` ever run.
 *
 * A script runs with the interpreter its first line names (`#!/usr/bin/env python3` → python3), else bash: Android
 * has no /usr/bin/env, so the `#!` line itself would not work.
 */
object HomeScripts {
    data class Script(val file: File, val background: Boolean, val label: String)

    private const val CHANNEL = "home_scripts"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _changes = MutableStateFlow(0)
    /** Bumped when the list may have changed (the Scripts widget reads the folder again). */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun touch() {
        _changes.value++
    }

    fun dir(context: Context): File = File(LocalEnvironmentManager.getHomeDir(context), ".shortcuts")

    fun list(context: Context): List<Script> {
        val root = dir(context)
        val entries = root.listFiles()?.filter { !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: return emptyList()
        val out = mutableListOf<Script>()
        fun filesOf(d: File) = d.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }.orEmpty()
        entries.filter { it.isFile }.forEach { out += Script(it, false, it.name) }
        entries.filter { it.isDirectory && it.name != "tasks" && it.name != "icons" }.forEach { d ->
            filesOf(d).forEach { out += Script(it, false, "${d.name}/${it.name}") }
        }
        File(root, "tasks").takeIf { it.isDirectory }?.let { filesOf(it).forEach { f -> out += Script(f, true, f.name) } }
        return out
    }

    /** Makes ~/.shortcuts and ~/.shortcuts/tasks. */
    fun ensureFolders(context: Context): Boolean = runCatching {
        File(dir(context), "tasks").mkdirs()
        touch()
        dir(context).isDirectory
    }.getOrDefault(false)

    /** A file inside ~/.shortcuts (no other path may run through a link). */
    fun isAllowed(context: Context, file: File): Boolean = runCatching {
        val root = dir(context).canonicalPath + File.separator
        file.isFile && file.canonicalPath.startsWith(root)
    }.getOrDefault(false)

    fun command(file: File): String {
        val first = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull().orEmpty()
        val interp = if (first.startsWith("#!")) {
            val parts = first.removePrefix("#!").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val exe = parts.firstOrNull()?.substringAfterLast('/').orEmpty()
            // "#!/usr/bin/env python3" → python3; "#!/bin/bash -e" → bash -e
            val chosen = if (exe == "env") parts.drop(1).filterNot { it.startsWith("-") } else listOf(exe) + parts.drop(1)
            chosen.joinToString(" ").ifBlank { "bash" }
        } else "bash"
        return "$interp ${quote(file.absolutePath)}"
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun remoteTerminal(): Boolean = AuthPreferences.remoteShell

    /** A new terminal tab running the script; the terminal page comes up. */
    fun runInTerminal(activity: ComponentActivity, vm: ChatViewModel, file: File) {
        if (!isAllowed(activity, file)) {
            toast(activity, L.homescreen.homeScripts.onlyScriptsInShortcutsCanRun)
            return
        }
        activity.lifecycleScope.launch {
            if (remoteTerminal() || vm.useSshTerminal.first()) {
                toast(activity, L.homescreen.homeScripts.scriptsRunInThisPhonesTerminal)
                return@launch
            }
            if (!LocalEnvironmentManager.isInstalled(activity)) {
                toast(activity, L.homescreen.homeScripts.setUpTheTerminalFirstOpen)
                return@launch
            }
            if (!vm.isLocalToolsEnabled.first()) vm.setLocalToolsEnabled(true)
            // the terminal's own tabs first, so the new one is not replaced while they are restored
            LocalTerminalManager.getOrCreateOrRestoreSessions(activity)
            LocalTerminalManager.createNewSession(
                activity.applicationContext,
                workingDir = file.parent,
                initialCommand = command(file),
                sessionTitle = file.nameWithoutExtension.ifBlank { file.name }
            )
            vm.requestViewMode("TERMINAL")
        }
    }

    /** Runs the script without a terminal (on the Run page) and says when it is done. Main thread. */
    fun runInBackground(context: Context, file: File) {
        val app = context.applicationContext
        if (!isAllowed(app, file)) {
            toast(app, L.homescreen.homeScripts.onlyScriptsInShortcutsCanRun)
            return
        }
        if (remoteTerminal()) {
            toast(app, L.homescreen.homeScripts.scriptsRunOnThisPhoneTurn)
            return
        }
        if (!LocalEnvironmentManager.isInstalled(app)) {
            toast(app, L.homescreen.homeScripts.setUpTheTerminalFirstOpen)
            return
        }
        // the app's service keeps the process (and the script) alive in the background
        runCatching { ai.antigem.data.service.AntiGemService.start(app) }
        val run = runCatching {
            RunManager.start(app, name = file.name, command = command(file), folder = file.parent ?: dir(app).absolutePath, origin = "home")
        }.getOrElse {
            toast(app, L.homescreen.homeScripts.couldNotStartNameMessage(file.name, it.message))
            return
        }
        toast(app, "Running ${file.name}…")
        scope.launch {
            val end = RunManager.runs.map { list -> list.firstOrNull { it.id == run.id } }
                .first { it == null || it.status != RunStatus.RUNNING } ?: return@launch
            notifyDone(app, end.id, file.name, end.status, end.exitCode)
        }
    }

    private fun notifyDone(context: Context, runId: String, name: String, status: RunStatus, exit: Int?) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Home screen scripts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = L.homescreen.homeScripts.whenAScriptStartedFromThe
                    })
                }
            }
            val ok = status == RunStatus.SUCCEEDED
            val title = when (status) {
                RunStatus.SUCCEEDED -> "✓ $name finished"
                RunStatus.STOPPED -> L.homescreen.homeScripts.nameWasStopped(name)
                else -> "✗ $name failed" + (exit?.let { " (exit $it)" } ?: "")
            }
            val tail = runCatching { RunManager.recentOutput(runId, 4) }.getOrDefault("").trim()
            val open = PendingIntent.getActivity(
                context, runId.hashCode(), HomeLinks.intent(context, HomeLinks.Target.RunEntry(runId)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(if (ok) R.drawable.ic_home_check else R.drawable.ic_home_task)
                .setContentTitle(title)
                .setContentText(tail.lineSequence().lastOrNull { it.isNotBlank() } ?: L.homescreen.homeScripts.tapToSeeTheOutput)
                .setStyle(NotificationCompat.BigTextStyle().bigText(tail.ifBlank { L.homescreen.homeScripts.tapToSeeTheOutput }))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(runId.hashCode(), n)
        }
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
    }
}
