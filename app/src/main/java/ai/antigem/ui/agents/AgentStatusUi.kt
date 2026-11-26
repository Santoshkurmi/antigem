package ai.antigem.ui.agents

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
import androidx.compose.material.icons.outlined.RestartAlt
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
import ai.antigem.data.agent.claude.ClaudeCliStatus
import ai.antigem.data.agent.claude.ClaudeStatus
import ai.antigem.data.agent.opencode.OpenCodeState
import ai.antigem.data.agent.codex.CodexState
import ai.antigem.data.remote.AgyHubClient
import ai.antigem.data.remote.SystemConnectionState
import ai.antigem.data.remote.SystemStatus
import ai.antigem.domain.model.AgentKind
import ai.antigem.ui.components.a11yMinTouchTarget
import ai.antigem.ui.components.accent
import ai.antigem.ui.components.label
import ai.antigem.ui.components.rememberTalkBackOn
import ai.antigem.i18n.L
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

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
    serverRestarting: Boolean,
    onSignIn: () -> Unit,
    onCheckAuth: () -> Unit,
    onRetry: () -> Unit,
    onRestartServer: () -> Unit,
    /** The hub could not start because agy is not installed (see [agyNotInstalled]). */
    notInstalled: Boolean = false,
    onInstall: () -> Unit = {}
): AgentStatusEntry {
    val status = state.status
    fun entry(label: String, color: Color = status.dotColor, detail: String? = null, action: AgentStatusAction? = null, pending: Boolean = false) =
        AgentStatusEntry(AgentKind.AGY, label, color, detail, action, pending, isReady = status == SystemStatus.READY && !isAuthBusy)
    return when {
        serverRestarting -> entry(L.common.restarting, SystemStatus.STARTING.dotColor, L.agents.agentStatusUi.theLocalServerIsRestarting, pending = true)
        // like Claude Code, OpenCode and Codex: an Install button, not the hub's "agy is not installed at <path>…"
        notInstalled -> entry(L.common.notInstalled, detail = L.agents.agentStatusUi.antigravityAgyIsNotInstalledYet, action = AgentStatusAction(L.common.install, onInstall))
        isAuthBusy -> entry(L.agents.agentStatusUi.signingIn, SystemStatus.ACQUIRING_CSRF.dotColor, L.agents.agentStatusUi.finishSigningInWithGoogleIn, AgentStatusAction(L.common.check, onCheckAuth), pending = true)
        status == SystemStatus.OFFLINE && inStartupGrace -> entry(L.common.starting, SystemStatus.STARTING.dotColor, L.agents.agentStatusUi.startingTheLocalServer, pending = true)
        status == SystemStatus.OFFLINE && state is SystemConnectionState.Connected -> entry(
            L.agents.agentStatusUi.offline,
            detail = L.agents.agentStatusUi.theAntigravityHubIsStopped,
            action = AgentStatusAction(L.common.restart, onRestartServer)
        )
        status == SystemStatus.OFFLINE -> entry(
            L.agents.agentStatusUi.offline,
            detail = L.agents.agentStatusUi.theLocalServerIsNotRunning,
            action = AgentStatusAction(L.common.start, onRestartServer)
        )
        status == SystemStatus.STARTING -> entry(L.common.starting, detail = L.agents.agentStatusUi.theAntigravityHubIsStarting, pending = true)
        status == SystemStatus.ACQUIRING_CSRF -> entry(L.common.connecting, detail = L.agents.agentStatusUi.gettingASessionTokenFromThe, pending = true)
        status == SystemStatus.CHECKING_AUTH -> entry(L.agents.agentStatusUi.checkingSignIn, pending = true)
        status == SystemStatus.UNAUTHENTICATED -> entry(
            L.agents.agentStatusUi.signInRequired,
            detail = L.agents.agentStatusUi.signInWithGoogleToChat,
            action = AgentStatusAction(L.common.signIn, onSignIn)
        ).copy(isSignedOut = true)
        status == SystemStatus.READY -> entry(
            L.common.ready,
            detail = listOf(authInfo.email, authInfo.userTier.ifBlank { authInfo.planName })
                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
        )
        else -> entry(
            L.agents.agentStatusUi.error,
            detail = (state as? SystemConnectionState.Connected)?.error ?: (state as? SystemConnectionState.Error)?.message,
            action = if (state is SystemConnectionState.Connected) AgentStatusAction(L.common.restart, onRestartServer) else AgentStatusAction(L.common.retry, onRetry)
        )
    }
}

/**
 * Antigravity cannot run because agy is missing: the hub stopped with the bridge's "agy is not installed at …" error, or
 * the bridge said so when asked about the agy CLI.
 */
fun agyNotInstalled(state: SystemConnectionState, cli: ai.antigem.data.remote.BridgeInfo.AgyCli?): Boolean =
    state.status == SystemStatus.ERROR &&
        ((state as? SystemConnectionState.Connected)?.error?.startsWith("agy is not installed") == true || cli?.installed == false)

fun claudeStatusEntry(
    state: ClaudeStatus,
    status: ClaudeCliStatus?,
    error: String?,
    serverRestarting: Boolean,
    onSignIn: () -> Unit,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit
): AgentStatusEntry {
    if (serverRestarting) {
        return AgentStatusEntry(AgentKind.CLAUDE, L.common.restarting, ClaudeStatus.CHECKING.dotColor, L.agents.agentStatusUi.theLocalServerIsRestarting, pending = true)
    }
    val auth = status?.auth
    fun entry(detail: String? = null, action: AgentStatusAction? = null) = AgentStatusEntry(
        AgentKind.CLAUDE, state.label, state.dotColor, detail, action,
        pending = state == ClaudeStatus.CHECKING || state == ClaudeStatus.SIGNING_IN,
        isReady = state == ClaudeStatus.READY,
        isSignedOut = state == ClaudeStatus.SIGNED_OUT
    )
    return when (state) {
        ClaudeStatus.CHECKING -> entry(L.agents.agentStatusUi.lookingForTheClaudeCodeCli)
        ClaudeStatus.BRIDGE_OFFLINE -> entry(error ?: L.agents.agentStatusUi.theLocalServerIsNotRunning, AgentStatusAction(L.common.start, onRestartServer))
        ClaudeStatus.BRIDGE_OUTDATED -> entry(error, AgentStatusAction(L.common.restart, onRestartServer))
        ClaudeStatus.NOT_INSTALLED -> entry(L.agents.agentStatusUi.theClaudeCliIsNotInstalled, AgentStatusAction(L.common.setUp, onSetup))
        ClaudeStatus.SIGNED_OUT -> entry(L.agents.agentStatusUi.signInWithYourClaudeSubscription, AgentStatusAction(L.common.signIn, onSignIn))
        ClaudeStatus.SIGNING_IN -> entry(L.agents.agentStatusUi.finishSigningInInYourBrowser, AgentStatusAction(L.common.open, onSignIn))
        ClaudeStatus.READY -> entry(
            listOfNotNull(auth?.email, auth?.subscriptionType?.let { claudePlanLabel(it) }, status?.version?.substringBefore(" "))
                .joinToString(" · ").ifBlank { null }
        )
        ClaudeStatus.ERROR -> entry(error, AgentStatusAction(L.common.retry, onRetry))
    }
}

fun openCodeStatusEntry(
    state: ai.antigem.data.agent.opencode.OpenCodeState,
    status: ai.antigem.data.agent.opencode.OpenCodeStatus?,
    serverRestarting: Boolean,
    onSetup: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit
): AgentStatusEntry {
    val color = when (state) {
        OpenCodeState.READY -> SystemStatus.READY.dotColor
        OpenCodeState.CHECKING -> SystemStatus.STARTING.dotColor
        OpenCodeState.OFFLINE, OpenCodeState.DISABLED, OpenCodeState.OUTDATED -> SystemStatus.OFFLINE.dotColor
        else -> ClaudeStatus.ERROR.dotColor
    }
    if (serverRestarting) {
        return AgentStatusEntry(AgentKind.OPENCODE, L.common.restarting, SystemStatus.STARTING.dotColor, L.agents.agentStatusUi.theLocalServerIsRestarting, pending = true)
    }
    fun entry(detail: String? = null, action: AgentStatusAction? = null) = AgentStatusEntry(
        AgentKind.OPENCODE, state.label, color, detail, action,
        pending = state == OpenCodeState.CHECKING,
        isReady = state == OpenCodeState.READY
    )
    return when (state) {
        OpenCodeState.CHECKING -> entry(L.agents.agentStatusUi.startingWaitingForTheServer)
        OpenCodeState.READY -> entry(listOfNotNull(status?.version?.let { "OpenCode $it" }, if (status?.running == true) "running" else L.agents.agentStatusUi.startsWithTheFirstMessage).joinToString(" · "))
        OpenCodeState.NOT_INSTALLED -> entry(L.agents.agentStatusUi.opencodeIsNotInstalledYetThe, AgentStatusAction(L.common.install, onSetup))
        OpenCodeState.DISABLED -> entry(L.agents.agentStatusUi.theServerWasStartedWithOpencode, AgentStatusAction(L.common.restart, onRestartServer))
        OpenCodeState.OUTDATED -> entry(L.agents.agentStatusUi.thisServerHasNoOpencodeSupport, AgentStatusAction(L.common.restart, onRestartServer))
        OpenCodeState.OFFLINE -> entry(L.agents.agentStatusUi.theServerIsNotRunning, AgentStatusAction(L.common.start, onRestartServer))
        OpenCodeState.ERROR -> entry(status?.error, AgentStatusAction(L.common.retry, onRetry))
    }
}

fun codexStatusEntry(
    state: ai.antigem.data.agent.codex.CodexState,
    status: ai.antigem.data.agent.codex.CodexStatus?,
    account: ai.antigem.data.agent.codex.CodexAccount?,
    serverRestarting: Boolean,
    onSetup: () -> Unit,
    onSignIn: () -> Unit,
    onRestartServer: () -> Unit,
    onRetry: () -> Unit
): AgentStatusEntry {
    val color = when (state) {
        CodexState.READY -> SystemStatus.READY.dotColor
        CodexState.CHECKING -> SystemStatus.STARTING.dotColor
        CodexState.SIGNED_OUT -> ClaudeStatus.SIGNED_OUT.dotColor
        CodexState.OFFLINE, CodexState.DISABLED, CodexState.OUTDATED -> SystemStatus.OFFLINE.dotColor
        else -> ClaudeStatus.ERROR.dotColor
    }
    if (serverRestarting) {
        return AgentStatusEntry(AgentKind.CODEX, L.common.restarting, SystemStatus.STARTING.dotColor, L.agents.agentStatusUi.theLocalServerIsRestarting, pending = true)
    }
    fun entry(detail: String? = null, action: AgentStatusAction? = null) = AgentStatusEntry(
        AgentKind.CODEX, state.label, color, detail, action,
        pending = state == CodexState.CHECKING,
        isReady = state == CodexState.READY,
        isSignedOut = state == CodexState.SIGNED_OUT
    )
    return when (state) {
        CodexState.CHECKING -> entry(L.agents.agentStatusUi.startingWaitingForTheServer)
        CodexState.READY -> entry(
            listOfNotNull(account?.email, account?.plan?.replaceFirstChar { it.uppercase() }, status?.version?.let { L.agents.agentStatusUi.codexValue(it) })
                .joinToString(" · ").ifBlank { null }
        )
        CodexState.SIGNED_OUT -> entry(L.agents.agentStatusUi.signInWithYourChatgptAccount, AgentStatusAction(L.common.signIn, onSignIn))
        CodexState.NOT_INSTALLED -> entry(
            if (status?.termux_package == true) L.agents.agentStatusUi.codexIsNotInstalledYetPkg
            else L.agents.agentStatusUi.codexIsNotInstalledYetThe,
            AgentStatusAction(L.common.install, onSetup)
        )
        CodexState.DISABLED -> entry(L.agents.agentStatusUi.theServerWasStartedWithCodex, AgentStatusAction(L.common.restart, onRestartServer))
        CodexState.OUTDATED -> entry(L.agents.agentStatusUi.thisServerHasNoCodexSupport, AgentStatusAction(L.common.restart, onRestartServer))
        CodexState.OFFLINE -> entry(L.agents.agentStatusUi.theServerIsNotRunning, AgentStatusAction(L.common.start, onRestartServer))
        CodexState.ERROR -> entry(status?.error, AgentStatusAction(L.common.retry, onRetry))
    }
}

fun claudePlanLabel(raw: String): String = when (raw.lowercase()) {
    "pro" -> L.agents.agentStatusUi.pro
    "max" -> L.agents.agentStatusUi.max
    "team" -> L.agents.agentStatusUi.team
    "enterprise" -> L.agents.agentStatusUi.enterprise
    else -> raw.replaceFirstChar { it.uppercase() }
}

/**
 * Connection dot for one or two agents. With two different states the upper half is [top] (Antigravity) and the
 * lower half [bottom] (Claude Code); the same state (or one agent) draws one solid dot.
 */
@Composable
fun AgentStatusDot(top: Color, bottom: Color?, modifier: Modifier = Modifier, size: Dp = 11.dp, pulsing: Boolean = false) =
    AgentStatusDot(listOfNotNull(top, bottom), modifier, size, pulsing)

/**
 * Connection dot for up to three agents, one slice each in the order given (Antigravity, Claude Code, OpenCode):
 * one agent is a solid dot, two are an upper and a lower half, three are thirds (top, lower right, lower left).
 * Agents in the same state draw one solid dot.
 */
@Composable
fun AgentStatusDot(colors: List<Color>, modifier: Modifier = Modifier, size: Dp = 11.dp, pulsing: Boolean = false) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "agent_dot")
        transition.animateFloat(1f, 0.4f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "agent_dot_alpha").value
    } else 1f
    val slices = colors.ifEmpty { listOf(SystemStatus.OFFLINE.dotColor) }
    Canvas(modifier.size(size).graphicsLayer { this.alpha = alpha }) {
        when {
            slices.distinct().size == 1 -> drawCircle(slices.first())
            slices.size == 2 -> {
                drawArc(slices[0], startAngle = 180f, sweepAngle = 180f, useCenter = true)
                drawArc(slices[1], startAngle = 0f, sweepAngle = 180f, useCenter = true)
            }
            else -> {
                // the first slice is centred on top (-90°), then clockwise
                val sweep = 360f / slices.size
                slices.forEachIndexed { i, c -> drawArc(c, startAngle = -90f - sweep / 2 + i * sweep, sweepAngle = sweep, useCenter = true) }
            }
        }
    }
}

/** Round agent mark (tinted with the agent's color) with an optional status badge. */
@Composable
fun AgentMark(agent: AgentKind, modifier: Modifier = Modifier, size: Dp = 32.dp, statusColor: Color? = null) {
    val accent = agent.accent()
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.15f)).clearAndSetSemantics { },
            contentAlignment = Alignment.Center
        ) {
            when (agent) {
                AgentKind.AGY -> Icon(Icons.Outlined.RocketLaunch, null, tint = accent, modifier = Modifier.size(size * 0.52f))
                AgentKind.CLAUDE -> Text("✻", color = accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
                AgentKind.OPENCODE -> Text("</>", color = accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.3f).sp, maxLines = 1)
                AgentKind.CODEX -> Text(">_", color = accent, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, maxLines = 1)
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
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
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
            AgentActionPill(
                text = action.label,
                color = entry.agent.accent(),
                onClick = {
                    action.onClick()
                    onActionDone()
                },
                spokenLabel = "${entry.agent.label()}, ${action.label}"
            )
        }
    }
}

@Composable
fun AgentActionPill(text: String, color: Color, onClick: () -> Unit, spokenLabel: String? = null) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier.semantics {
            this.role = Role.Button
            if (spokenLabel != null) contentDescription = spokenLabel
        }
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
    val talkBackOn = rememberTalkBackOn()
    val agy = entries.find { it.agent == AgentKind.AGY }
    val claude = entries.find { it.agent == AgentKind.CLAUDE }
    val openCode = entries.find { it.agent == AgentKind.OPENCODE }
    val codex = entries.find { it.agent == AgentKind.CODEX }
    // one slice per enabled agent
    val colors = listOfNotNull(agy, claude, openCode, codex).map { it.color }
    Box(
        modifier = modifier
            .a11yMinTouchTarget(talkBackOn)
            .size(36.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = L.components.a11y.agentStatusValue(
                    entries.joinToString { "${it.agent.label()}: ${it.label}" }
                )
            }
            .clickable(role = Role.Button) { open = true },
        contentAlignment = Alignment.Center
    ) {
        AgentStatusDot(
            colors = colors,
            size = if (colors.distinct().size > 1) 12.dp else 11.dp,
            pulsing = entries.any { it.pending }
        )
        AgentStatusMenu(entries, expanded = open, onDismiss = { open = false }, onOpenServerLogs = onOpenServerLogs)
    }
}

/**
 * The agents' status popup (each agent's state with its fix, the server logs and, with [onRestartServer], a restart
 * of the server, which many did not find inside the logs); anchored to its parent.
 */
@Composable
fun AgentStatusMenu(
    entries: List<AgentStatusEntry>,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenServerLogs: () -> Unit,
    onRestartServer: (() -> Unit)? = null
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        shadowElevation = 8.dp,
        modifier = Modifier.width(300.dp)
    ) {
        Text(
            L.agents.agentStatusUi.agents,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)
                .semantics { heading() }
        )
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            entries.forEach { AgentStatusRow(it) { onDismiss() } }
        }
        HorizontalDivider(
            Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    onDismiss()
                    onOpenServerLogs()
                }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(10.dp))
            Text(L.common.serverLogs, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
        }
        if (onRestartServer != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        onDismiss()
                        onRestartServer()
                    }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.RestartAlt, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(10.dp))
                Text(L.common.restartServer, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
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
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            // read out when it changes (starting -> signed out -> ready)
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }
        )
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
