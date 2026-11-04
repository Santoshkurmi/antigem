package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.ui.ide.IdeExecutionRequest
import com.example.gemini.ui.ide.IdeExecutionRunnerSheet

/**
 * Runs a chat bash command in a fresh interactive terminal over the chat. The user first sees (and can edit) the command;
 * Enter runs it. Closing the terminal destroys its session and everything still running in it.
 */
@Composable
fun ChatCommandRunDialog(command: String, onDismiss: () -> Unit) {
    var request by remember { mutableStateOf<IdeExecutionRequest?>(null) }
    val running = request

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = running == null,
            usePlatformDefaultWidth = false
        )
    ) {
        if (running != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF141418),
                border = BorderStroke(1.dp, Color(0xFF2E2E38)),
                modifier = Modifier
                    .fillMaxWidth(0.97f)
                    .fillMaxHeight(0.92f)
                    .imePadding()
                    .clip(RoundedCornerShape(16.dp))
            ) {
                IdeExecutionRunnerSheet(request = running, onClose = onDismiss)
            }
        } else {
            CommandConfirmCard(
                initial = command,
                onCancel = onDismiss,
                onRun = { cmd -> request = IdeExecutionRequest(profileName = "Chat command", command = cmd) }
            )
        }
    }
}

@Composable
private fun CommandConfirmCard(initial: String, onCancel: () -> Unit, onRun: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length))) }
    val focusRequester = remember { FocusRequester() }
    val canRun = value.text.isNotBlank()
    // A one-line command runs on Enter; a multi-line one (heredocs, scripts) needs Enter for new lines, so Shift+Enter / the button run it
    val singleLine = !initial.contains('\n')
    val run = { if (canRun) onRun(value.text.trim()) }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF16161C),
        border = BorderStroke(1.dp, Color(0xFF2E2E38)),
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxWidth(0.94f).imePadding()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(ClaudeTerracotta.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Terminal, null, tint = ClaudeTerracotta, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Run in terminal", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Opens a new terminal session. Edit the command if needed.", fontSize = 12.sp, color = Color(0xFF9A9AA6))
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF0D0E15),
                border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("$ ", fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                    androidx.compose.foundation.text.BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = singleLine,
                        maxLines = if (singleLine) 1 else 12,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFFE2E8F0)),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(ClaudeTerracotta),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = if (singleLine) ImeAction.Go else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(onGo = { run() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { e ->
                                val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                                if (enter && e.type == KeyEventType.KeyDown && (singleLine || e.isShiftPressed)) { run(); true } else false
                            }
                    )
                }
            }

            Text(
                if (singleLine) "Press Enter to run · closing the terminal stops everything in it"
                else "Shift+Enter or Run to start · closing the terminal stops everything in it",
                fontSize = 11.sp,
                color = Color(0xFF7A7A86)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFFB0B0BA)) }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = run,
                    enabled = canRun,
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Run")
                }
            }
        }
    }
}
