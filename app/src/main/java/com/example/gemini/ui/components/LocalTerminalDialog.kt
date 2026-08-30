package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.data.local.LocalInteractiveSession
import com.example.gemini.data.local.LocalTerminalManager
import com.example.gemini.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LocalTerminalDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        LocalTerminalContent(onClose = onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalTerminalContent(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sessions by LocalTerminalManager.sessions.collectAsState()
    val activeSessionId by LocalTerminalManager.activeSessionId.collectAsState()

    // Ensure at least one active primary session
    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) {
            LocalTerminalManager.getOrCreatePrimarySession(context)
        }
    }

    val activeSession = sessions.find { it.id == activeSessionId }
        ?: sessions.firstOrNull()
        ?: remember { LocalTerminalManager.getOrCreatePrimarySession(context) }

    val lines by activeSession.lines.collectAsState()
    val isBusy by activeSession.isBusy.collectAsState()
    val isExited by activeSession.isExited.collectAsState()

    var commandInput by remember { mutableStateOf(TextFieldValue("")) }
    val history = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableStateOf(-1) }

    val listState = rememberLazyListState()
    var terminalFontSize by remember { mutableStateOf(12.5.sp) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var lastExecutedCmd by remember { mutableStateOf("") }
    var lastExecutionTime by remember { mutableLongStateOf(0L) }

    val executeCurrentCommand: (String) -> Unit = { rawCmd ->
        val now = System.currentTimeMillis()
        val cmd = rawCmd.trimEnd('\r', '\n')

        // Prevent duplicate execution within 250ms
        if (now - lastExecutionTime > 250L || cmd != lastExecutedCmd) {
            lastExecutedCmd = cmd
            lastExecutionTime = now

            if (cmd.isNotBlank()) {
                history.add(cmd)
                historyIndex = -1
                if (isCtrlActive) {
                    val firstChar = cmd.trim().first().lowercaseChar()
                    val ctrlCode = (firstChar.code - 'a'.code + 1).toChar().toString()
                    activeSession.sendRawInput(ctrlCode)
                    isCtrlActive = false
                } else {
                    activeSession.executeCommand(cmd)
                }
            } else {
                activeSession.executeCommand("")
            }
        }
        commandInput = TextFieldValue("")
    }

    val onKeyAction: (() -> Unit) -> Unit = { action ->
        action()
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    // Auto-focus on start & auto-scroll to bottom on new output or typing
    LaunchedEffect(Unit) {
        delay(150)
        keyboardController?.show()
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    LaunchedEffect(lines.size, commandInput.text) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
        ) {
            // TOP HEADER BAR: Terminal session tabs, path, and controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F0F11))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot (Green when active, Red when exited, Amber when busy)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isExited -> Color.Red
                                isBusy -> ClaudeTerracotta
                                else -> QuotaGreen
                            }
                        )
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Sessions Tabs
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sessions.forEach { sess ->
                        val isSelected = sess.id == activeSession.id
                        Surface(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .clickable { LocalTerminalManager.selectSession(sess.id) },
                            color = if (isSelected) Color(0xFF222226) else Color.Transparent,
                            shape = RoundedCornerShape(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sess.name,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.White else Color.Gray
                                )

                                if (sessions.size > 1) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .size(11.dp)
                                            .clickable { LocalTerminalManager.closeSession(sess.id) }
                                    )
                                }
                            }
                        }
                    }

                    // Add session '+'
                    IconButton(
                        onClick = { LocalTerminalManager.createNewSession(context) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Session",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // Font size controls
                IconButton(
                    onClick = { if (terminalFontSize.value > 9) terminalFontSize = (terminalFontSize.value - 1).sp },
                    modifier = Modifier.size(26.dp)
                ) {
                    Text("A-", fontSize = 10.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = { if (terminalFontSize.value < 18) terminalFontSize = (terminalFontSize.value + 1).sp },
                    modifier = Modifier.size(26.dp)
                ) {
                    Text("A+", fontSize = 10.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                }

                // Copy entire screen
                IconButton(
                    onClick = {
                        val text = lines.joinToString("\n") { it.rawText }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
                        Toast.makeText(context, "Copied terminal output", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Output",
                        tint = Color.LightGray,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Clear screen
                IconButton(
                    onClick = { activeSession.clearLines() },
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = "Clear",
                        tint = Color.LightGray,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Close / Hide Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // TERMINAL OUTPUT SCREEN (LazyColumn) + PINNED INLINE PROMPT ROW
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        keyboardController?.show()
                        try { focusRequester.requestFocus() } catch (_: Exception) {}
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // 1. SCROLLABLE TERMINAL OUTPUT BUFFER
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(lines, key = { it.id }) { line ->
                        Text(
                            text = line.annotatedString,
                            fontFamily = FontFamily.Monospace,
                            fontSize = terminalFontSize,
                            lineHeight = (terminalFontSize.value * 1.35f).sp,
                            color = Color(0xFFE4E4E7),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 2. PINNED ACTIVE PROMPT ROW (Outside LazyColumn, permanently focused & mounted!)
                val homePath = LocalEnvironmentManager.getHomeDir(context).canonicalPath
                val dirName = if (activeSession.workingDirectory.startsWith(homePath)) {
                    val rel = activeSession.workingDirectory.removePrefix(homePath)
                    if (rel.isEmpty()) "~" else "~$rel"
                } else {
                    activeSession.workingDirectory.substringAfterLast("/").ifBlank { "/" }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "➜ ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = terminalFontSize,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981) // Green arrow
                    )
                    Text(
                        text = "$dirName ",
                        fontFamily = FontFamily.Monospace,
                        fontSize = terminalFontSize,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8) // Cyan dir
                    )

                    // Permanently mounted BasicTextField - never unmounts, never loses focus!
                    BasicTextField(
                        value = commandInput,
                        onValueChange = { newValue ->
                            if (newValue.text.contains("\n")) {
                                val cmd = newValue.text.substringBefore("\n")
                                commandInput = TextFieldValue("")
                                executeCurrentCommand(cmd)
                            } else {
                                commandInput = newValue
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = terminalFontSize,
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(Color.White),
                        singleLine = false,
                        maxLines = 1,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.None,
                            autoCorrectEnabled = false
                        )
                    )
                }
            }

            // EXTRA-KEYS TOOLBAR (Exact 2-Row Termux Layout from Screenshot)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C0C0E))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // ROW 1: ESC | / | - | HOME | ↑ | END | PGUP
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    TermuxKey(label = "ESC", modifier = Modifier.weight(1f)) {
                        onKeyAction { activeSession.sendEsc() }
                    }
                    TermuxKey(label = "/", modifier = Modifier.weight(1f)) {
                        onKeyAction {
                            commandInput = TextFieldValue(commandInput.text + "/", androidx.compose.ui.text.TextRange(commandInput.text.length + 1))
                        }
                    }
                    TermuxKey(label = "-", modifier = Modifier.weight(1f)) {
                        onKeyAction {
                            commandInput = TextFieldValue(commandInput.text + "-", androidx.compose.ui.text.TextRange(commandInput.text.length + 1))
                        }
                    }
                    TermuxKey(label = "HOME", modifier = Modifier.weight(1f)) {
                        onKeyAction { activeSession.sendHome() }
                    }
                    TermuxKey(label = "↑", modifier = Modifier.weight(1f)) {
                        onKeyAction {
                            if (history.isNotEmpty()) {
                                if (historyIndex < history.size - 1) historyIndex++
                                val cmd = history[history.size - 1 - historyIndex]
                                commandInput = TextFieldValue(cmd, androidx.compose.ui.text.TextRange(cmd.length))
                            } else {
                                activeSession.sendArrowUp()
                            }
                        }
                    }
                    TermuxKey(label = "END", modifier = Modifier.weight(1f)) {
                        onKeyAction { activeSession.sendEnd() }
                    }
                    TermuxKey(label = "PGUP", modifier = Modifier.weight(1f)) {
                        onKeyAction { activeSession.sendPgUp() }
                    }
                }

                // ROW 2: ↹ (TAB) | CTRL | ALT | ← | ↓ | → | PGDN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    TermuxKey(label = "↹", modifier = Modifier.weight(1f)) {
                        onKeyAction { activeSession.sendTab() }
                    }
                    TermuxKey(
                        label = "CTRL",
                        isActive = isCtrlActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        onKeyAction { isCtrlActive = !isCtrlActive }
                    }
                    TermuxKey(
                        label = "ALT",
                        isActive = isAltActive,
                        modifier = Modifier.weight(1f)
                    ) {
                        onKeyAction { isAltActive = !isAltActive }
                    }
                    TermuxKey(label = "←", modifier = Modifier.weight(1f)) {
                        onKeyAction { activeSession.sendArrowLeft() }
                    }
                    TermuxKey(label = "↓", modifier = Modifier.weight(1f)) {
                        onKeyAction {
                            if (historyIndex > 0) {
                                historyIndex--
                                val cmd = history[history.size - 1 - historyIndex]
                                commandInput = TextFieldValue(cmd, androidx.compose.ui.text.TextRange(cmd.length))
                            } else if (historyIndex == 0) {
                                historyIndex = -1
                                commandInput = TextFieldValue("")
                            } else {
                                activeSession.sendArrowDown()
                            }
                        }
                    }
                    TermuxKey(label = "→", modifier = Modifier.weight(1f)) {
                        onKeyAction { activeSession.sendArrowRight() }
                    }
                    TermuxKey(label = "PGDN", modifier = Modifier.weight(1f)) {
                        onKeyAction { activeSession.sendPgDn() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TermuxKey(
    label: String,
    isActive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable { onClick() },
        color = if (isActive) ClaudeTerracotta else Color(0xFF1E1E22),
        shape = RoundedCornerShape(3.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) Color.White else Color(0xFFE2E8F0)
            )
        }
    }
}

