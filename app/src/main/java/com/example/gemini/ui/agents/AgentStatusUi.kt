package com.example.gemini.ui.agents

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.claude.ClaudeCliStatus
import com.example.gemini.data.agent.claude.ClaudeStatus
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.SystemConnectionState
import com.example.gemini.data.remote.SystemStatus
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.accent
import com.example.gemini.ui.components.label

/** One agent's health: drives the split status dot, its popup, the accounts dialog and the new-chat picker. */
data class AgentStatusEntry(
    val agent: AgentKind,
    val label: String,
    val color: Color,
    val detail: String? = null,
    val action: AgentStatusAction? = null,
    /** Transitional (starting, checking, signing in): the dot pulses. */
    val pending: Boolean = false,
    val isReady: Boolean = false,
    val isSignedOut: Boolean = false
)

class AgentStatusAction(val label: String, val onClick: () -> Unit)

fun agyStatusEntry(
    state: SystemConnectionState,
    authInfo: AgyHubClient.AgyAuthInfo,
    isAuthBusy: Boolean,
    inStartupGrace: Boolean,
    onSignIn: () -> Unit,
    onCheckAuth: () -> Unit,
    onRetry: () -> Unit
): AgentStatusEntry {
    val status = state.status
    fun entry(label: String, color: Color = status.dotColor, detail: String? = null, action: AgentStatusAction? = null, pending: Boolean = false) =
        AgentStatusEntry(AgentKind.AGY, label, color, detail, action, pending, isReady = status == SystemStatus.READY && !isAuthBusy)
    return when {
        isAuthBusy -> entry("Signing in…", SystemStatus.ACQUIRING_CSRF.dotColor, "Finish signing in with Google in your browser.", AgentStatusAction("Check", onCheckAuth), pending = true)
        status == SystemStatus.OFFLINE && inStartupGrace -> entry("Starting…", SystemStatus.STARTING.dotColor, "Starting the local server.", pending = true)
        status == SystemStatus.OFFLINE -> entry(
            "Offline",
            detail = if (state is SystemConnectionState.Connected) "The Antigravity hub is stopped." else "The local server is not running.",
            action = AgentStatusAction("Retry", onRetry)
        )
        status == SystemStatus.STARTING -> entry("Starting…", detail = "The Antigravity hub is starting.", pending = true)
        status == SystemStatus.ACQUIRING_CSRF -> entry("Connecting…", detail = "Getting a session token from the hub.", pending = true)
        status == SystemStatus.CHECKING_AUTH -> entry("Checking sign-in…", pending = true)
        status == SystemStatus.UNAUTHENTICATED -> entry(
            "Sign in required",
            detail = "Sign in with Google to chat with Antigravity.",
            action = AgentStatusAction("Sign in", onSignIn)
        ).copy(isSignedOut = true)
        status == SystemStatus.READY -> entry(
            "Ready",
            detail = listOf(authInfo.email, authInfo.userTier.ifBlank { authInfo.planName })
                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
        )
        else -> entry(
            "Error",
            detail = (state as? SystemConnectionState.Connected)?.error ?: (state as? SystemConnectionState.Error)?.message,
            action = AgentStatusAction("Retry", onRetry)
        )
    }
}

fun claudeStatusEntry(
    state: ClaudeStatus,
    status: ClaudeCliStatus?,
    error: String?,
    onSignIn: () -> Unit,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit
): AgentStatusEntry {
    val auth = status?.auth
    fun entry(detail: String? = null, action: AgentStatusAction? = null) = AgentStatusEntry(
        AgentKind.CLAUDE, state.label, state.dotColor, detail, action,
        pending = state == ClaudeStatus.CHECKING || state == ClaudeStatus.SIGNING_IN,
        isReady = state == ClaudeStatus.READY,
        isSignedOut = state == ClaudeStatus.SIGNED_OUT
    )
    return when (state) {
        ClaudeStatus.CHECKING -> entry("Looking for the Claude Code CLI and its sign-in.")
        ClaudeStatus.BRIDGE_OFFLINE -> entry(error ?: "The local server is not running.", AgentStatusAction("Retry", onRetry))
        ClaudeStatus.BRIDGE_OUTDATED -> entry(error, AgentStatusAction("Restart", onRestartServer))
        ClaudeStatus.NOT_INSTALLED -> entry("The claude CLI is not installed on this device.", AgentStatusAction("Set up", onSetup))
        ClaudeStatus.SIGNED_OUT -> entry("Sign in with your Claude subscription or an Anthropic Console account.", AgentStatusAction("Sign in", onSignIn))
        ClaudeStatus.SIGNING_IN -> entry("Finish signing in in your browser.", AgentStatusAction("Open", onSignIn))
        ClaudeStatus.READY -> entry(
            listOfNotNull(auth?.email, auth?.subscriptionType?.let { claudePlanLabel(it) }, status?.version?.substringBefore(" "))
                .joinToString(" · ").ifBlank { null }
        )
        ClaudeStatus.ERROR -> entry(error, AgentStatusAction("Retry", onRetry))
    }
}

fun claudePlanLabel(raw: String): String = when (raw.lowercase()) {
    "pro" -> "Pro"
    "max" -> "Max"
    "team" -> "Team"
    "enterprise" -> "Enterprise"
    else -> raw.replaceFirstChar { it.uppercase() }
}

/**
 * Connection dot for one or two agents. With two different states the upper half is [top] (Antigravity) and the
 * lower half [bottom] (Claude Code); the same state (or one agent) draws one solid dot.
 */
@Composable
fun AgentStatusDot(top: Color, bottom: Color?, modifier: Modifier = Modifier, size: Dp = 11.dp, pulsing: Boolean = false) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "agent_dot")
        transition.animateFloat(1f, 0.4f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "agent_dot_alpha").value
    } else 1f
    Canvas(modifier.size(size).graphicsLayer { this.alpha = alpha }) {
        if (bottom == null || bottom == top) {
            drawCircle(top)
        } else {
            drawArc(top, startAngle = 180f, sweepAngle = 180f, useCenter = true)
            drawArc(bottom, startAngle = 0f, sweepAngle = 180f, useCenter = true)
        }
    }
}

/** Round agent mark (tinted with the agent's color) with an optional status badge. */
@Composable
fun AgentMark(agent: AgentKind, modifier: Modifier = Modifier, size: Dp = 32.dp, statusColor: Color? = null) {
    val accent = agent.accent()
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            when (agent) {
                AgentKind.AGY -> Icon(Icons.Outlined.RocketLaunch, null, tint = accent, modifier = Modifier.size(size * 0.52f))
                AgentKind.CLAUDE -> Text("✻", color = accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
            }
        }
        if (statusColor != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.34f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
        }
    }
}

/** One agent row: mark, name, state, detail and its action. */
@Composable
fun AgentStatusRow(entry: AgentStatusEntry, modifier: Modifier = Modifier, onActionDone: () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AgentMark(entry.agent, size = 34.dp, statusColor = entry.color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.agent.label(), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(entry.label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = entry.color, maxLines = 1)
            }
            entry.detail?.let {
                Text(
                    it,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        entry.action?.let { action ->
            Spacer(Modifier.width(8.dp))
            AgentActionPill(action.label, entry.agent.accent()) {
                action.onClick()
                onActionDone()
            }
        }
    }
}

@Composable
fun AgentActionPill(text: String, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

/**
 * Top-bar status: the (split) dot; tapping opens each agent's state with its fix (sign in, set up, restart)
 * and the server logs.
 */
@Composable
fun AgentStatusIndicator(
    entries: List<AgentStatusEntry>,
    onOpenServerLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val agy = entries.find { it.agent == AgentKind.AGY }
    val claude = entries.find { it.agent == AgentKind.CLAUDE }
    val top = agy?.color ?: claude?.color ?: SystemStatus.OFFLINE.dotColor
    val bottom = if (agy != null) claude?.color else null
    Box(
        modifier = modifier.size(36.dp).clip(CircleShape).clickable { open = true },
        contentAlignment = Alignment.Center
    ) {
        AgentStatusDot(
            top = top,
            bottom = bottom,
            size = if (bottom != null && bottom != top) 12.dp else 11.dp,
            pulsing = entries.any { it.pending }
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
            shadowElevation = 8.dp,
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                "AGENTS",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)
            )
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                entries.forEach { AgentStatusRow(it) { open = false } }
            }
            HorizontalDivider(
                Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        open = false
                        onOpenServerLogs()
                    }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text("Server logs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Centered prompt for an agent that is not ready (empty chat screen). */
@Composable
fun AgentSetupPrompt(
    agent: AgentKind,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {}
) {
    val accent = agent.accent()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AgentMark(agent, size = 60.dp, statusColor = statusColor)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
            ) { Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        }
        if (secondaryLabel != null) {
            androidx.compose.material3.TextButton(onClick = onSecondary) {
                Text(secondaryLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
    }
}
