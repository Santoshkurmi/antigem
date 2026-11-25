package ai.antigem.data.homescreen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import ai.antigem.MainActivity
import ai.antigem.TerminalActivity
import ai.antigem.data.jarvis.JarvisLauncher
import ai.antigem.data.security.AppIntentKey
import ai.antigem.i18n.L
import ai.antigem.data.jarvis.JarvisTalkActivity
import ai.antigem.data.voice.VoiceCallLaunchActivity
import ai.antigem.data.voice.VoiceCallLauncher
import ai.antigem.ui.bubble.FloatingChatActivity
import ai.antigem.ui.chat.ChatViewModelHolder
import ai.antigem.ui.miniapps.MiniAppsActivity
import java.io.File

/**
 * The invisible screen every home-screen shortcut, app-icon menu entry and widget tap opens (see [HomeLinks]). It
 * hands the link on and closes at once: the voice call, Jarvis, the floating chat, the terminal window, the mini apps
 * and background scripts open on their own; everything else opens in the app ([HomeRouter]).
 */
class HomeLaunchActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        runCatching { open(intent) }.onFailure { Log.w(TAG, "open: ${it.message}", it) }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun open(intent: Intent) {
        val target = HomeLinks.parse(intent.data)
        // only the app's own shortcuts and widgets carry its signature of the link: another app (or a link from a web
        // page) can't start the microphone, the call or a script through here, it only opens the app
        val trusted = HomeLinks.isTrusted(this, intent)
        if (!trusted && HomeLinks.isOldLink(this, intent)) {
            Toast.makeText(this, L.homescreen.homeLaunchActivity.thisShortcutIsFromAnOlder, Toast.LENGTH_LONG).show()
        }
        when (target) {
            null -> openApp(intent)
            HomeLinks.Target.VoiceCall ->
                if (!trusted) openApp(intent)
                else startActivity(Intent(this, VoiceCallLaunchActivity::class.java).setAction(VoiceCallLauncher.ACTION_CALL))
            HomeLinks.Target.Jarvis ->
                if (!trusted) openApp(intent)
                else startActivity(Intent(this, JarvisTalkActivity::class.java).setAction(JarvisLauncher.ACTION_TALK))
            HomeLinks.Target.TerminalWindow ->
                startActivity(Intent(this, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            HomeLinks.Target.MiniApps ->
                startActivity(Intent(this, MiniAppsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            HomeLinks.Target.FloatingChat -> if (trusted && appRunning()) openFloating() else openApp(intent)
            is HomeLinks.Target.Reply -> {
                // the floating window shows the chat on screen: open this one first (the app opens it instead when it
                // is not running yet: the floating chat needs its server)
                val vm = ChatViewModelHolder.peek()
                if (trusted && vm != null && appRunning()) {
                    vm.openChatFromOutside(target.id, target.agent)
                    HomeSnapshot.markSeen(target.id)
                    openFloating()
                } else {
                    openApp(intent)
                }
            }
            is HomeLinks.Target.Script ->
                if (!target.background) openApp(intent)
                else if (trusted) HomeScripts.runInBackground(this, File(target.path))
            else -> openApp(intent)
        }
    }

    private fun appRunning(): Boolean = MainActivity.currentInstance != null

    private fun openFloating() {
        startActivity(
            Intent(this, FloatingChatActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        )
    }

    /** The app, showing the link (MainActivity checks the signature again for scripts and web pages). */
    private fun openApp(from: Intent) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (from.data != null) {
            i.setAction(HomeLinks.ACTION_SHOW).setData(from.data)
            from.getStringExtra(AppIntentKey.EXTRA_SIG)?.let { i.putExtra(AppIntentKey.EXTRA_SIG, it) }
        }
        startActivity(i)
    }

    private companion object {
        const val TAG = "HomeLaunch"
    }
}
