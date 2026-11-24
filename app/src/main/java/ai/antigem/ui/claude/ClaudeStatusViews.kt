package ai.antigem.ui.claude

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.agent.claude.ClaudeStatus
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.agents.AgentActionPill
import ai.antigem.ui.agents.AgentSetupPrompt
import ai.antigem.ui.agents.AgentStatusDot
import ai.antigem.ui.components.ClaudeAccent
import ai.antigem.i18n.L

/** Empty Claude chat when Claude Code is not ready: what is wrong and the one action that fixes it. */
@Composable
fun ClaudeStatusPrompt(
    state: ClaudeStatus,
    error: String?,
    onSignIn: () -> Unit,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit,
    onServerLogs: () -> Unit,
    modifier: Modifier = Modifier,
    /** The local server is (re)starting: wait instead of reporting it offline. */
    serverStarting: Boolean = false
) {
    val dot = state.dotColor
    if (serverStarting) {
        AgentSetupPrompt(
            AgentKind.CLAUDE, L.claude.statusViews.startingTheLocalServer, L.claude.statusViews.claudeCodeWillBeReadyIn, modifier, ClaudeStatus.CHECKING.dotColor
        )
        return
    }
    when (state) {
        ClaudeStatus.CHECKING -> AgentSetupPrompt(
            AgentKind.CLAUDE, L.claude.statusViews.checkingClaudeCode, L.claude.statusViews.lookingForTheClaudeCodeCli, modifier, dot
        )
        ClaudeStatus.SIGNED_OUT -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            L.claude.statusViews.signInToClaudeCode,
            L.claude.statusViews.useYourClaudeProOrMax,
            modifier, dot,
            actionLabel = L.common.signIn, onAction = onSignIn
        )
        ClaudeStatus.SIGNING_IN -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            L.claude.statusViews.finishingSignIn,
            L.claude.statusViews.completeTheSignInInYour,
            modifier, dot,
            actionLabel = L.claude.statusViews.openSignIn, onAction = onSignIn
        )
        ClaudeStatus.NOT_INSTALLED -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            L.claude.statusViews.claudeCodeNotFound,
            L.claude.statusViews.theClaudeCliIsNotInstalled,
            modifier, dot,
            actionLabel = L.claude.statusViews.setUpClaudeCode, onAction = onSetup,
            secondaryLabel = L.common.checkAgain, onSecondary = onRetry
        )
        ClaudeStatus.BRIDGE_OUTDATED -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            L.claude.statusViews.restartTheLocalServer,
            error ?: L.claude.statusViews.theRunningLocalServerCannotRun,
            modifier, dot,
            actionLabel = L.common.restartServer, onAction = onRestartServer,
            secondaryLabel = L.common.checkAgain, onSecondary = onRetry
        )
        ClaudeStatus.BRIDGE_OFFLINE -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            L.claude.statusViews.localServerOffline,
            L.claude.statusViews.claudeCodeRunsThroughTheLocal,
            modifier, dot,
            actionLabel = L.common.retry, onAction = onRetry,
            secondaryLabel = L.common.serverLogs, onSecondary = onServerLogs
        )
        ClaudeStatus.ERROR -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            L.claude.statusViews.couldntCheckClaudeCode,
            L.claude.statusViews.valueYouCanStillSendA((error ?: L.claude.statusViews.theStatusCheckFailed)),
            modifier, dot,
            actionLabel = L.common.checkAgain, onAction = onRetry
        )
        ClaudeStatus.READY -> Unit
    }
}

/** One-line notice above the input of a Claude chat that has messages but Claude Code is not ready. */
@Composable
fun ClaudeStatusBanner(
    state: ClaudeStatus,
    onSignIn: () -> Unit,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (text, action) = when (state) {
        ClaudeStatus.SIGNED_OUT -> L.claude.statusViews.claudeCodeIsSignedOut to (L.common.signIn to onSignIn)
        ClaudeStatus.SIGNING_IN -> L.claude.statusViews.finishingClaudeSignIn to (L.common.open to onSignIn)
        ClaudeStatus.NOT_INSTALLED -> L.claude.statusViews.claudeCodeNotFoundOnThis to (L.common.setUp to onSetup)
        ClaudeStatus.BRIDGE_OUTDATED -> L.claude.statusViews.restartTheLocalServerToUse to (L.common.restart to onRestartServer)
        ClaudeStatus.BRIDGE_OFFLINE -> L.claude.statusViews.localServerOffline to (L.common.retry to onRetry)
        else -> return
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, state.dotColor.copy(alpha = 0.45f)),
        shadowElevation = 2.dp
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentStatusDot(state.dotColor, null, size = 9.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            AgentActionPill(action.first, ClaudeAccent, action.second)
        }
    }
}

/** Above the input while a sent message waits for the claude process to boot. */
@Composable
fun ClaudeStartupNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = ClaudeAccent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, ClaudeAccent.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.padding(end = 10.dp).size(14.dp),
                strokeWidth = 2.dp,
                color = ClaudeAccent
            )
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    L.claude.statusViews.theFirstMessageOfAChat,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
