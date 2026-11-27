package ai.antigem.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.data.debug.Troubleshoot
import ai.antigem.data.debug.Troubleshoot.Level
import ai.antigem.data.local.LocalServerManager
import ai.antigem.i18n.L
import ai.antigem.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Troubleshoot: runs [Troubleshoot] when the page opens (and on "Run again"), shows what is wrong first,
 * then every check by area and the logs, and copies or shares a report with the secrets hidden. Nothing runs once the
 * page is closed.
 */
@Composable
internal fun TroubleshootScreen(cardBg: Color, cardBorder: BorderStroke, onNavigate: (SettingsSection) -> Unit) {
    val T = L.settings.troubleshoot
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var runKey by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var step by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Troubleshoot.Result?>(null) }
    var pending by remember { mutableStateOf<Troubleshoot.Finding?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(runKey) {
        running = true
        step = ""
        val r = runCatching { Troubleshoot.run(context) { step = it } }.getOrNull()
        if (r != null) {
            result = r
            // areas with a problem open by themselves
            r.sections.forEachIndexed { i, s ->
                val key = "s$i:${s.title}"
                if (key !in expanded) expanded[key] = s.level == Level.BAD || s.level == Level.WARN
            }
        }
        running = false
    }

    fun rerunLater(ms: Long) {
        scope.launch {
            running = true
            step = T.waitingForServer
            delay(ms)
            runKey++
        }
    }

    pending?.let { finding ->
        val stop = finding.action == Troubleshoot.Action.STOP_AGY_PROCESSES
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (stop) T.stopProcessesTitle else T.restartTitle) },
            text = { Text(if (stop) T.stopProcessesText(finding.pids.joinToString()) else T.restartText) },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    if (stop) {
                        scope.launch {
                            val n = Troubleshoot.stopAgyProcessesAndRestart(context, finding.pids)
                            Toast.makeText(context, T.stoppedProcesses(n.toString()), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        LocalServerManager.restartServer(context.applicationContext)
                        Toast.makeText(context, T.restarting, Toast.LENGTH_SHORT).show()
                    }
                    rerunLater(9_000)
                }) { Text(if (stop) T.stopAndRestart else T.restartServer) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(L.common.cancel) } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            HeaderCard(result, running, step, cardBg, cardBorder,
                onRun = { runKey++ },
                onShare = { r -> runCatching { Troubleshoot.share(context, r) }.onFailure {
                    Toast.makeText(context, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show()
                } })
        }
        val r = result
        if (r != null) {
            item(key = "findings") {
                FindingsCard(r.findings, cardBg, cardBorder) { f ->
                    when (f.action) {
                        Troubleshoot.Action.RESTART_SERVER, Troubleshoot.Action.STOP_AGY_PROCESSES -> pending = f
                        Troubleshoot.Action.OPEN_SERVERS -> onNavigate(SettingsSection.SERVERS)
                        Troubleshoot.Action.OPEN_AGY_CLI -> onNavigate(SettingsSection.AGY_CLI)
                        Troubleshoot.Action.OPEN_AGENTS -> onNavigate(SettingsSection.AGENTS)
                        null -> Unit
                    }
                }
            }
            itemsIndexed(r.sections, key = { i, s -> "s$i:${s.title}" }) { i, s ->
                val key = "s$i:${s.title}"
                SectionCard(s, expanded[key] == true, cardBg, cardBorder) { expanded[key] = expanded[key] != true }
            }
            item(key = "logs-title") {
                Text(T.logs, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
            // the Server Runner log is live while open; the others are what the check read
            val logs = r.logs.ifEmpty { listOf(Troubleshoot.Log("runner", T.logServerRunner, emptyList())) }
            itemsIndexed(logs, key = { _, l -> "log:${l.id}" }) { _, log ->
                val key = "log:${log.id}"
                LogCard(log, expanded[key] == true, cardBg, cardBorder) { expanded[key] = expanded[key] != true }
            }
        }
    }
}

private fun levelColor(level: Level?): Color = when (level) {
    Level.OK -> AppColors.success
    Level.INFO -> AppColors.info
    Level.WARN -> AppColors.warning
    Level.BAD -> AppColors.danger
    null -> Color.Gray
}

private fun levelIcon(level: Level): ImageVector = when (level) {
    Level.OK -> Icons.Outlined.CheckCircle
    Level.INFO -> Icons.Outlined.Info
    Level.WARN -> Icons.Outlined.WarningAmber
    Level.BAD -> Icons.Outlined.ErrorOutline
}

private fun kindIcon(kind: Troubleshoot.Kind): ImageVector = when (kind) {
    Troubleshoot.Kind.SETUP -> Icons.Outlined.PhoneAndroid
    Troubleshoot.Kind.NETWORK -> Icons.Outlined.Wifi
    Troubleshoot.Kind.BRIDGE -> Icons.Outlined.Dns
    Troubleshoot.Kind.AGY -> Icons.Outlined.RocketLaunch
    Troubleshoot.Kind.CLAUDE -> Icons.Outlined.AutoAwesome
    Troubleshoot.Kind.OPENCODE -> Icons.Outlined.Code
    Troubleshoot.Kind.CODEX -> Icons.Outlined.Terminal
    Troubleshoot.Kind.INTERNET -> Icons.Outlined.Public
    Troubleshoot.Kind.PROCESSES -> Icons.Outlined.Memory
}

@Composable
private fun TsCard(cardBg: Color, cardBorder: BorderStroke, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(cardBg).border(cardBorder, shape)) { content() }
}

@Composable
private fun HeaderCard(
    result: Troubleshoot.Result?,
    running: Boolean,
    step: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    onRun: () -> Unit,
    onShare: (Troubleshoot.Result) -> Unit
) {
    val T = L.settings.troubleshoot
    val bad = result?.findings?.count { it.level == Level.BAD } ?: 0
    val warn = result?.findings?.count { it.level == Level.WARN } ?: 0
    val worst = when {
        result == null -> null
        bad > 0 -> Level.BAD
        warn > 0 -> Level.WARN
        else -> Level.OK
    }
    TsCard(cardBg, cardBorder) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(levelColor(worst).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    if (running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = AppColors.info)
                    else Icon(levelIcon(worst ?: Level.INFO), null, tint = levelColor(worst), modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            running -> T.checking
                            worst == Level.OK -> T.noProblems
                            else -> T.summary(bad.toString(), warn.toString())
                        },
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
                    )
                    val sub = when {
                        running -> step
                        result != null -> T.checkedAt(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(result.startedMs)),
                            String.format(Locale.US, "%.1f", result.tookMs / 1000.0))
                        else -> ""
                    }
                    if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRun, enabled = !running, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(T.runAgain, fontSize = 13.sp)
                }
                // Share only (no copy): the clipboard cuts long text without telling anyone; the file keeps all of it
                Button(onClick = { result?.let(onShare) }, enabled = result != null && !running, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.info)) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(T.share, fontSize = 13.sp)
                }
            }
            Text(T.privacyNote, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), lineHeight = 15.sp)
        }
    }
}

@Composable
private fun FindingsCard(findings: List<Troubleshoot.Finding>, cardBg: Color, cardBorder: BorderStroke, onAction: (Troubleshoot.Finding) -> Unit) {
    val T = L.settings.troubleshoot
    TsCard(cardBg, cardBorder) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(T.findings, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            findings.forEachIndexed { i, f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Icon(levelIcon(f.level), null, tint = levelColor(f.level), modifier = Modifier.size(20.dp).padding(top = 1.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(f.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        if (f.detail.isNotBlank()) SelectionContainer {
                            Text(f.detail, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f), lineHeight = 17.sp)
                        }
                        if (f.fix.isNotBlank()) Text(T.whatToDo(f.fix), fontSize = 12.5.sp, color = levelColor(f.level).copy(alpha = 0.95f), lineHeight = 17.sp)
                        f.action?.let { a ->
                            TextButton(onClick = { onAction(f) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                                Text(actionLabel(a), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppColors.info)
                            }
                        }
                    }
                }
                if (i < findings.lastIndex) androidx.compose.material3.HorizontalDivider(
                    Modifier.padding(start = 44.dp), thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            }
        }
    }
}

private fun actionLabel(a: Troubleshoot.Action): String {
    val T = L.settings.troubleshoot
    return when (a) {
        Troubleshoot.Action.RESTART_SERVER -> T.restartServer
        Troubleshoot.Action.STOP_AGY_PROCESSES -> T.stopAndRestart
        Troubleshoot.Action.OPEN_SERVERS -> T.openWhereAgentsRun
        Troubleshoot.Action.OPEN_AGY_CLI -> T.openAgyCli
        Troubleshoot.Action.OPEN_AGENTS -> T.openAgents
    }
}

@Composable
private fun SectionCard(section: Troubleshoot.Section, open: Boolean, cardBg: Color, cardBorder: BorderStroke, onToggle: () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    TsCard(cardBg, cardBorder) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(levelColor(section.level).copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    Icon(kindIcon(section.kind), null, tint = levelColor(section.level), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(section.title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = onSurface, modifier = Modifier.weight(1f))
                section.level?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(levelColor(it))); Spacer(Modifier.width(6.dp)) }
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = onSurface.copy(alpha = 0.45f))
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    section.rows.forEach { row ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(row.label, fontSize = 12.5.sp, color = onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(0.42f))
                            Spacer(Modifier.width(8.dp))
                            Row(Modifier.weight(0.58f), verticalAlignment = Alignment.Top) {
                                row.level?.let {
                                    Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).background(levelColor(it)))
                                    Spacer(Modifier.width(5.dp))
                                }
                                SelectionContainer { Text(row.value, fontSize = 12.5.sp, color = onSurface, lineHeight = 17.sp) }
                            }
                        }
                    }
                    section.lines.forEach { block -> LinesBox(block.title, block.lines) }
                }
            }
        }
    }
}

@Composable
private fun LinesBox(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        MonoBox(lines.joinToString("\n").ifBlank { L.settings.troubleshoot.empty }, maxHeight = 260)
    }
}

@Composable
private fun MonoBox(text: String, maxHeight: Int) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppColors.codeBg)
            .heightIn(max = maxHeight.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        SelectionContainer { Text(text, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, color = AppColors.codeText) }
    }
}

@Composable
private fun LogCard(log: Troubleshoot.Log, open: Boolean, cardBg: Color, cardBorder: BorderStroke, onToggle: () -> Unit) {
    val T = L.settings.troubleshoot
    val onSurface = MaterialTheme.colorScheme.onSurface
    val live = log.id == "runner"
    var lines by remember(log) { mutableStateOf(log.lines) }
    // the server log follows the Server Runner terminal while it is open on screen
    if (live && open) LaunchedEffect(Unit) {
        while (true) {
            lines = Troubleshoot.serverRunnerLog()
            delay(2_000)
        }
    }
    TsCard(cardBg, cardBorder) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.Article, null, tint = AppColors.accentIndigo, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(log.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onSurface)
                    Text(if (live && open) T.liveLines(lines.size.toString()) else T.lineCount(lines.size.toString()),
                        fontSize = 11.5.sp, color = onSurface.copy(alpha = 0.55f))
                }
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = onSurface.copy(alpha = 0.45f))
            }
            AnimatedVisibility(open) {
                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    MonoBox(lines.joinToString("\n").ifBlank { T.empty }, maxHeight = 420)
                }
            }
        }
    }
}
