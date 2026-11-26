package ai.antigem.ui.chat

import ai.antigem.i18n.L
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * TalkBack only: speaks what happens in the open chat that a screen-reader user can't see coming: the reply finished,
 * the agent waits for an approval or asks a question, the chat failed to load. Nothing is drawn.
 *
 * Compose it only while TalkBack is on ([ai.antigem.ui.components.rememberTalkBackOn]), so for everyone else none of
 * this exists. Only changes are spoken, not what a chat already shows when it opens ([chatKey] starts over), and only
 * while this screen is in front (the main window and a floating chat don't both speak).
 */
@Composable
fun ChatTalkBackAnnouncer(
    chatKey: String?,
    running: Boolean,
    needsApproval: Boolean,
    hasQuestion: Boolean,
    error: String?
) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // what was last spoken about (or already there when the chat opened)
    val last = remember(chatKey) { Seen(running, needsApproval, hasQuestion, error) }

    fun say(text: String) {
        if (text.isNotBlank() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            @Suppress("DEPRECATION")
            view.announceForAccessibility(text)
        }
    }

    LaunchedEffect(chatKey, running) {
        if (running == last.running) return@LaunchedEffect
        if (!running) {
            // a short pause between two steps of one turn isn't "finished": a new change cancels this wait
            delay(1500)
            say(L.chat.chatTalkBackAnnouncer.replyFinished)
        }
        last.running = running
    }
    LaunchedEffect(chatKey, needsApproval) {
        if (needsApproval && !last.needsApproval) say(L.chat.chatTalkBackAnnouncer.theAgentNeedsYourApproval)
        last.needsApproval = needsApproval
    }
    LaunchedEffect(chatKey, hasQuestion) {
        if (hasQuestion && !last.hasQuestion) say(L.chat.chatTalkBackAnnouncer.theAgentIsAskingAQuestion)
        last.hasQuestion = hasQuestion
    }
    LaunchedEffect(chatKey, error) {
        if (!error.isNullOrBlank() && error != last.error) say(error)
        last.error = error
    }
}

private class Seen(var running: Boolean, var needsApproval: Boolean, var hasQuestion: Boolean, var error: String?)
