package com.example.gemini.ui.claude

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
import com.example.gemini.data.agent.claude.ClaudeStatus
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.agents.AgentActionPill
import com.example.gemini.ui.agents.AgentSetupPrompt
import com.example.gemini.ui.agents.AgentStatusDot
import com.example.gemini.ui.components.ClaudeAccent

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
            AgentKind.CLAUDE, "Starting the local server…", "Claude Code will be ready in a moment.", modifier, ClaudeStatus.CHECKING.dotColor
        )
        return
    }
    when (state) {
        ClaudeStatus.CHECKING -> AgentSetupPrompt(
            AgentKind.CLAUDE, "Checking Claude Code…", "Looking for the Claude Code CLI and your sign-in.", modifier, dot
        )
        ClaudeStatus.SIGNED_OUT -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            "Sign in to Claude Code",
            "Use your Claude Pro or Max subscription, or an Anthropic Console account, to chat with Claude Code.",
            modifier, dot,
            actionLabel = "Sign in", onAction = onSignIn
        )
        ClaudeStatus.SIGNING_IN -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            "Finishing sign-in…",
            "Complete the sign-in in your browser, then come back here.",
            modifier, dot,
            actionLabel = "Open sign-in", onAction = onSignIn
        )
        ClaudeStatus.NOT_INSTALLED -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            "Claude Code not found",
            "The claude CLI is not installed on this device yet. You can install it from Settings in one tap.",
            modifier, dot,
            actionLabel = "Set up Claude Code", onAction = onSetup,
            secondaryLabel = "Check again", onSecondary = onRetry
        )
        ClaudeStatus.BRIDGE_OUTDATED -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            "Restart the local server",
            error ?: "The running local server cannot run Claude Code yet. Restart it to load Claude.",
            modifier, dot,
            actionLabel = "Restart server", onAction = onRestartServer,
            secondaryLabel = "Check again", onSecondary = onRetry
        )
        ClaudeStatus.BRIDGE_OFFLINE -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            "Local server offline",
            "Claude Code runs through the local server. Start it to chat with Claude.",
            modifier, dot,
            actionLabel = "Retry", onAction = onRetry,
            secondaryLabel = "Server logs", onSecondary = onServerLogs
        )
        ClaudeStatus.ERROR -> AgentSetupPrompt(
            AgentKind.CLAUDE,
            "Couldn't check Claude Code",
            (error ?: "The status check failed.") + " You can still send a message.",
            modifier, dot,
            actionLabel = "Check again", onAction = onRetry
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
        ClaudeStatus.SIGNED_OUT -> "Claude Code is signed out" to ("Sign in" to onSignIn)
        ClaudeStatus.SIGNING_IN -> "Finishing Claude sign-in…" to ("Open" to onSignIn)
        ClaudeStatus.NOT_INSTALLED -> "Claude Code not found on this device" to ("Set up" to onSetup)
        ClaudeStatus.BRIDGE_OUTDATED -> "Restart the local server to use Claude Code" to ("Restart" to onRestartServer)
        ClaudeStatus.BRIDGE_OFFLINE -> "Local server offline" to ("Retry" to onRetry)
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
                    "The first message of a chat starts Claude Code, which can take a little while on a phone.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
