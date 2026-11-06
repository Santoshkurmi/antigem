package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue
import com.example.gemini.theme.QuotaGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val CLONED_PATH_MARKER = "ANTIGEM_CLONED_PATH="

/** The folder name git gives a clone of [url]: "https://github.com/a/my-repo.git" → "my-repo". */
private fun repoNameOf(url: String): String =
    url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")

/**
 * Clones a public git repository into the home folder with the bundled `git_clone` script, showing its output
 * like the backup and restore dialogs. On success [onCloned] gets the new project, shortly after the log shows it.
 */
@Composable
fun GitCloneDialog(
    onCloned: (ProjectItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var repoUrl by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    var isRunning by remember { mutableStateOf(false) }
    var exitCode by remember { mutableStateOf<Int?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    var executionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val listState = rememberLazyListState()

    val trimmedUrl = repoUrl.trim()
    val urlValid = trimmedUrl.startsWith("https://") && repoNameOf(trimmedUrl).isNotBlank()
    val nameValid = projectName.isBlank() || projectName.trim().matches(Regex("[A-Za-z0-9._-]+"))

    val activity = (context as? android.app.Activity) ?: ((context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)
    DisposableEffect(isRunning) {
        val window = activity?.window
        if (isRunning) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun startClone() {
        executionJob?.cancel()
        started = true
        executionJob = scope.launch {
            isRunning = true
            exitCode = null
            logs.clear()
            logs.add("⚡ Initializing git clone...")
            var clonedPath: String? = null
            val args = listOf(trimmedUrl) + listOfNotNull(projectName.trim().takeIf { it.isNotEmpty() })
            val startedAt = System.currentTimeMillis()
            // Remote mode: the repository is cloned by the bridge on the server, not by a script on the phone
            val runClone: suspend ((String) -> Unit) -> Pair<Int, Long> = { sink ->
                if (com.example.gemini.data.preferences.AuthPreferences.currentRemoteMode) {
                    val code = com.example.gemini.data.remote.AgyBridgeService.instance
                        .cloneRepository(trimmedUrl, projectName.trim(), onLine = sink)
                    code to (System.currentTimeMillis() - startedAt)
                } else {
                    val r = LocalEnvironmentManager.executeScriptLive(context = context, scriptName = "git_clone", args = args) { sink(it) }
                    r.exitCode to r.durationMs
                }
            }
            val (resultExit, resultDuration) = runClone { line ->
                if (line.startsWith(CLONED_PATH_MARKER)) {
                    clonedPath = line.removePrefix(CLONED_PATH_MARKER).trim()
                    return@runClone
                }
                if (line.isBlank() || line.startsWith("ANTIGEM_EXIT=")) return@runClone
                // git redraws its progress ("Receiving objects:  45% (450/1000)"): keep one line per phase
                val phase = line.substringBefore(':', "")
                val prevPhase = logs.lastOrNull()?.substringBefore(':', "")
                if (line.contains('%') && phase.isNotEmpty() && phase == prevPhase) {
                    logs[logs.size - 1] = line
                } else {
                    logs.add(line)
                }
            }
            exitCode = resultExit
            durationMs = resultDuration
            isRunning = false
            val path = clonedPath
            if (resultExit == 0 && path != null) {
                delay(800) // let the success line be seen before the chat comes back
                onCloned(ProjectItem(File(path).name, path))
            }
        }
    }

    LaunchedEffect(logs.size, logs.lastOrNull()) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
    }

    Dialog(
        onDismissRequest = { if (!isRunning) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isRunning,
            dismissOnClickOutside = !isRunning,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.92f)
                .then(if (started) Modifier.fillMaxHeight(0.80f) else Modifier)
        ) {
            Column(
                modifier = Modifier
                    .then(if (started) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when {
                            !started -> Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ClaudeTerracotta.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(20.dp))
                            }
                            isRunning -> Icon(Icons.Default.Terminal, contentDescription = null, tint = GeminiBlue, modifier = Modifier.size(22.dp))
                            exitCode == 0 -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                            else -> Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(22.dp))
                        }
                        Column {
                            Text(
                                text = "Clone Git Repository",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when {
                                    !started -> "Public repositories only, cloned into your home folder"
                                    isRunning -> "Cloning..."
                                    exitCode == 0 -> "Cloned in ${(durationMs / 1000.0)}s, opening project..."
                                    else -> "Clone failed (Exit: $exitCode)"
                                },
                                fontSize = 11.5.sp,
                                color = when {
                                    !started -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    isRunning -> GeminiBlue
                                    exitCode == 0 -> QuotaGreen
                                    else -> Color.Red
                                }
                            )
                        }
                    }
                    if (!isRunning) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!started) {
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = { repoUrl = it },
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://github.com/owner/repo") },
                        singleLine = true,
                        isError = trimmedUrl.isNotEmpty() && !urlValid,
                        supportingText = if (trimmedUrl.isNotEmpty() && !urlValid) {
                            { Text("Paste a public https:// repository URL") }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        label = { Text("Project name (optional)") },
                        placeholder = { Text(repoNameOf(trimmedUrl).ifBlank { "Same as the repository" }) },
                        singleLine = true,
                        isError = !nameValid,
                        supportingText = {
                            Text(
                                if (!nameValid) "Use letters, numbers, '.', '_' or '-'"
                                else "~/${projectName.trim().ifBlank { repoNameOf(trimmedUrl).ifBlank { "<repo>" } }}"
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { startClone() },
                        enabled = urlValid && nameValid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clone", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // Terminal output container
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = com.example.gemini.theme.AppColors.terminalBg,
                        border = BorderStroke(1.dp, com.example.gemini.theme.AppColors.editorBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp)
                        ) {
                            items(logs) { line ->
                                val cleanLine = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                                val lineColor = when {
                                    cleanLine.startsWith("❌") || cleanLine.startsWith("fatal:") || cleanLine.startsWith("error:") -> com.example.gemini.theme.AppColors.danger
                                    cleanLine.startsWith("✅") -> com.example.gemini.theme.AppColors.success
                                    cleanLine.startsWith("⚠️") || cleanLine.startsWith("warning:") -> com.example.gemini.theme.AppColors.warning
                                    cleanLine.startsWith("=") || cleanLine.startsWith("[") -> com.example.gemini.theme.AppColors.info
                                    else -> com.example.gemini.theme.AppColors.editorText
                                }
                                Text(
                                    text = cleanLine,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = lineColor,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (isRunning) {
                            Button(
                                onClick = {
                                    executionJob?.cancel()
                                    isRunning = false
                                    exitCode = 130
                                    logs.add("🛑 Clone cancelled by user.")
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = com.example.gemini.theme.AppColors.danger)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Stop", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        } else if (exitCode != 0) {
                            OutlinedButton(
                                onClick = { started = false },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Back", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Close", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
