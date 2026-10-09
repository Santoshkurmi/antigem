package com.example.gemini.ui.agents

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

private data class AgentOption(val agents: Set<AgentKind>, val title: String, val description: String)

private val AGENT_OPTIONS = listOf(
    AgentOption(
        setOf(AgentKind.AGY),
        "Antigravity",
        "Google's coding agent through the agy hub: Gemini and Claude models on your Google plan."
    ),
    AgentOption(
        setOf(AgentKind.CLAUDE),
        "Claude Code",
        "Anthropic's Claude Code CLI on this device: your Claude subscription or Anthropic Console account."
    ),
    AgentOption(
        setOf(AgentKind.AGY, AgentKind.CLAUDE),
        "Both",
        "Pick the agent for each new chat. Both share the chat list, status and accounts."
    )
)

/** Three choices: Antigravity, Claude Code, or both. */
@Composable
fun AgentSelector(selected: Set<AgentKind>, onSelect: (Set<AgentKind>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AGENT_OPTIONS.forEach { option ->
            val isOn = option.agents == selected
            val accent = if (option.agents.size == 1) option.agents.first().accent() else MaterialTheme.colorScheme.primary
            Surface(
                onClick = { onSelect(option.agents) },
                shape = RoundedCornerShape(16.dp),
                color = if (isOn) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (isOn) 1.5.dp else 1.dp, if (isOn) accent.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (option.agents.size == 1) {
                        AgentMark(option.agents.first(), size = 40.dp)
                    } else {
                        Box(Modifier.size(width = 52.dp, height = 40.dp)) {
                            AgentMark(AgentKind.AGY, size = 34.dp)
                            AgentMark(AgentKind.CLAUDE, size = 34.dp, modifier = Modifier.offset(x = 18.dp, y = 6.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(option.description, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, if (isOn) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isOn) Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                    }
                }
            }
        }
    }
}

/** First launch: which agents the app should run (Antigravity preselected). */
@Composable
fun AgentChoiceDialog(onConfirm: (Set<AgentKind>) -> Unit) {
    var selected by remember { mutableStateOf(setOf(AgentKind.AGY)) }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 76.dp, height = 56.dp)) {
                    AgentMark(AgentKind.AGY, size = 48.dp)
                    AgentMark(AgentKind.CLAUDE, size = 48.dp, modifier = Modifier.offset(x = 28.dp, y = 8.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("Choose your agent", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "The app runs only the agents you pick. You can change this later in Settings → Agents.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                Spacer(Modifier.height(18.dp))
                AgentSelector(selected, { selected = it })
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { onConfirm(selected) },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Continue", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Settings → Agents: change the selection; applying restarts the local server so a turned-off agent stops. */
@Composable
fun AgentsSettingsPage(
    enabled: Set<AgentKind>,
    isApplying: Boolean,
    onApply: (Set<AgentKind>) -> Unit,
    modifier: Modifier = Modifier
) {
    var selected by remember(enabled) { mutableStateOf(enabled) }
    var confirm by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
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
                Text(if (selected == enabled) "No changes" else "Apply and restart", fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Restart to apply?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The local server restarts with the new agents so nothing of a turned-off agent keeps running. " +
                        "Replies in progress stop, and a chat of a turned-off agent is closed.",
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    onApply(selected)
                }) { Text("Restart now") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

/** Shown while the server restarts for a new agent selection. */
@Composable
fun AgentsApplyingDialog() {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Applying agents", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Restarting the local server…", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}
