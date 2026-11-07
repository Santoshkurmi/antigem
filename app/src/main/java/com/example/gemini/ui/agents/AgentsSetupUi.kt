package com.example.gemini.ui.agents

import com.example.gemini.ui.components.bouncyVerticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.accent
import com.example.gemini.ui.components.label

private data class AgentOption(val agent: AgentKind, val title: String, val description: String)

private val AGENT_OPTIONS = listOf(
    AgentOption(
        AgentKind.AGY,
        "Antigravity",
        "Google's coding agent through the agy hub: Gemini and Claude models on your Google plan."
    ),
    AgentOption(
        AgentKind.CLAUDE,
        "Claude Code",
        "Anthropic's Claude Code CLI on this device: your Claude subscription or Anthropic Console account."
    ),
    AgentOption(
        AgentKind.OPENCODE,
        "OpenCode",
        "Open-source coding agent: 200+ providers with your own keys, any OpenAI-compatible API, and free models."
    )
)

/**
 * The agents to run: tap to turn each on or off (at least one stays on). With several on, the new-chat screen
 * lets you pick the agent per chat; they share the chat list, status and accounts.
 */
@Composable
fun AgentSelector(selected: Set<AgentKind>, onSelect: (Set<AgentKind>) -> Unit, modifier: Modifier = Modifier, allowEmpty: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AGENT_OPTIONS.forEach { option ->
            val isOn = option.agent in selected
            val accent = option.agent.accent()
            Surface(
                onClick = {
                    val next = if (isOn) selected - option.agent else selected + option.agent
                    if (allowEmpty || next.isNotEmpty()) onSelect(next)
                },
                shape = RoundedCornerShape(16.dp),
                color = if (isOn) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (isOn) 1.5.dp else 1.dp, if (isOn) accent.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentMark(option.agent, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(option.description, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.5.dp, if (isOn) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .background(if (isOn) accent else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isOn) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (selected.size > 1) {
            Text(
                "With several agents on, pick the agent for each new chat on the empty chat screen.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * First launch: which agents the app should run. Nothing is preselected: the user ticks one or more (the same
 * choice as Settings → Agents). Full screen, as step [step] of [total] of the setup.
 */
@Composable
fun AgentChoiceDialog(step: Int = 1, total: Int = 1, onConfirm: (Set<AgentKind>) -> Unit) {
    var selected by remember { mutableStateOf(emptySet<AgentKind>()) }
    com.example.gemini.ui.onboarding.OnboardingStepScreen(
        step = step,
        total = total,
        accent = MaterialTheme.colorScheme.primary,
        icon = {
            Box(Modifier.size(width = 64.dp, height = 44.dp)) {
                AgentMark(AgentKind.AGY, size = 34.dp, modifier = Modifier.align(Alignment.CenterStart))
                AgentMark(AgentKind.CLAUDE, size = 34.dp, modifier = Modifier.align(Alignment.Center).offset(y = (-6).dp))
                AgentMark(AgentKind.OPENCODE, size = 34.dp, modifier = Modifier.align(Alignment.CenterEnd))
            }
        },
        title = "Choose your agents",
        subtitle = "Tick the coding agents you want to use. The app runs only the ones you pick; you can change this later in Settings → Agents.",
        primaryLabel = when (selected.size) {
            0 -> "Pick at least one agent"
            1 -> "Continue with ${selected.first().label()}"
            else -> "Continue with ${selected.size} agents"
        },
        primaryEnabled = selected.isNotEmpty(),
        onPrimary = { onConfirm(selected) }
    ) {
        AgentSelector(selected, { selected = it }, allowEmpty = true)
    }
}

/** Settings → Agents: change the selection; applying stops the local server and restarts the app with the new agents. */
@Composable
fun AgentsSettingsPage(
    enabled: Set<AgentKind>,
    isApplying: Boolean,
    onApply: (Set<AgentKind>) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember(enabled) { mutableStateOf(enabled) }
    var confirm by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Agents you turn off do not run at all: the local server starts without them and their settings and chats are hidden (chats stay on disk).",
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(Modifier.height(14.dp))
        AgentSelector(selected, { selected = it })
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { confirm = true },
            enabled = selected != enabled && !isApplying,
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isApplying) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Text("Restarting…")
            } else {
                Text(if (selected == enabled) "No changes" else "Apply and restart app", fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Restart to apply?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The local server stops and the app restarts with the new agents, so nothing of a turned-off agent keeps running. " +
                        "Replies in progress stop, and a chat of a turned-off agent is closed.",
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    onApply(selected)
                }) { Text("Restart app") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

/** Shown while the server stops before the app restarts for a new agent selection. */
@Composable
fun AgentsApplyingDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Applying agents", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Stopping the local server, then restarting the app…", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}
