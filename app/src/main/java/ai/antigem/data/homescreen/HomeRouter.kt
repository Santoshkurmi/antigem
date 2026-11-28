package ai.antigem.data.homescreen

import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import ai.antigem.data.preferences.AuthPreferences
import ai.antigem.data.run.RunPanel
import ai.antigem.ui.browser.BrowserSessionManager
import ai.antigem.ui.chat.ChatViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ai.antigem.i18n.L

/** MainActivity's side of a home-screen link ([HomeLinks.ACTION_SHOW], forwarded by [HomeLaunchActivity]). */
object HomeRouter {

    /** Shows the link in [intent] if it has one. Each intent is handled once (not again when the screen is recreated). */
    fun handle(activity: ComponentActivity, vm: ChatViewModel, intent: Intent?) {
        if (intent?.action != HomeLinks.ACTION_SHOW) return
        val target = HomeLinks.parse(intent.data)
        val trusted = HomeLinks.isTrusted(activity, intent)
        intent.action = Intent.ACTION_MAIN
        intent.data = null
        intent.removeExtra(ai.antigem.data.security.AppIntentKey.EXTRA_SIG)
        intent.removeExtra(HomeLinks.EXTRA_KEY)
        target ?: return
        when (target) {
            is HomeLinks.Target.Chat -> openChat(activity, vm, target.agent, target.id)
            is HomeLinks.Target.Reply -> openChat(activity, vm, target.agent, target.id)
            is HomeLinks.Target.NewChat -> {
                vm.requestViewMode("CHAT")
                vm.startNewChatFromOutside(target.agent)
            }
            is HomeLinks.Target.OpenPage -> when (target.page) {
                HomeLinks.Page.TERMINAL -> openTerminal(activity, vm)
                else -> vm.requestViewMode(target.page.viewMode)
            }
            is HomeLinks.Target.Web -> {
                // only the app's own web shortcuts, and only web pages (no intent:, javascript:, file: ...): the
                // in-app browser shares the app's cookies and agents read its pages
                val scheme = runCatching { android.net.Uri.parse(target.url).scheme?.lowercase() }.getOrNull()
                if (trusted && (scheme == "http" || scheme == "https")) {
                    runCatching { BrowserSessionManager.instance.openUrl(target.url, newTab = true) }
                    vm.requestViewMode("BROWSER")
                }
            }
            is HomeLinks.Target.Script -> {
                if (!trusted) return
                if (target.background) HomeScripts.runInBackground(activity, target) else HomeScripts.runInTerminal(activity, vm, target)
            }
            is HomeLinks.Target.RunEntry -> {
                RunPanel.show(target.id)
                vm.requestViewMode("RUN")
            }
            is HomeLinks.Target.Settings -> {
                vm.requestViewMode("CHAT")
                vm.requestSettingsSection(target.section ?: "MAIN")
            }
            // these open without the app (HomeLaunchActivity); reaching here means the app was asked to show itself
            HomeLinks.Target.FloatingChat -> vm.requestViewMode("CHAT")
            HomeLinks.Target.TerminalWindow, HomeLinks.Target.VoiceCall, HomeLinks.Target.Jarvis, HomeLinks.Target.MiniApps -> Unit
        }
    }

    private fun openChat(activity: ComponentActivity, vm: ChatViewModel, agent: ai.antigem.domain.model.AgentKind, id: String) {
        vm.requestViewMode("CHAT")
        vm.openChatFromOutside(id, agent)
        HomeSnapshot.markSeen(id)
        HomeShortcuts.reportUsed(activity, id)
    }

    /** The terminal page, the way the page switcher opens it (the local terminal set up first). */
    private fun openTerminal(activity: ComponentActivity, vm: ChatViewModel) {
        activity.lifecycleScope.launch {
            val installed = vm.isLocalToolsInstalled.first()
            val ready = vm.useSshTerminal.first() || AuthPreferences.currentRemoteMode || (installed && vm.isLocalToolsEnabled.first())
            when {
                ready -> vm.requestViewMode("TERMINAL")
                installed -> {
                    vm.setLocalToolsEnabled(true)
                    vm.requestViewMode("TERMINAL")
                }
                else -> {
                    vm.requestViewMode("CHAT")
                    Toast.makeText(activity, L.homescreen.homeRouter.setUpTheTerminalFirstOpen, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
