package ai.antigem.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.antigem.data.context.GlobalContext
import ai.antigem.data.render.RenderBundle
import ai.antigem.data.render.RenderComponent
import ai.antigem.theme.QuotaGreen
import ai.antigem.ui.components.MarkdownBlockView
import ai.antigem.ui.components.bouncyVerticalScroll
import ai.antigem.ui.components.parseMarkdownBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ai.antigem.i18n.L

/**
 * Settings → STEM & diagrams: download the render bundle (one zip from the app's GitHub release), switch each kind
 * of block on or off, tell agents about them, and read the guide (its examples render live).
 */
@Composable
fun StemRenderingScreen() {
    val context = LocalContext.current
    remember { RenderBundle.init(context) }
    val installed by RenderBundle.installed.collectAsState()
    val status by RenderBundle.status.collectAsState()
    val active by RenderBundle.active.collectAsState()
    val tellAgents by RenderBundle.tellAgents.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var cacheTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(installed, cacheTick) { cacheBytes = withContext(Dispatchers.IO) { RenderBundle.cacheSizeBytes() } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow

    Column(Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            L.settings.stemRendering.extraBlocksForStudyNotesIn,
            fontSize = 12.5.sp, lineHeight = 17.sp, color = muted
        )
        Spacer(Modifier.height(16.dp))

        // ----- the bundle -----
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.settings.stemRendering.offlineBundle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${RenderBundle.BUNDLE_TAG} · ${mb(RenderBundle.BUNDLE_SIZE)} download" + if (installed) " · installed" else "",
                            fontSize = 12.sp, color = if (installed) QuotaGreen else muted
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    L.settings.stemRendering.smilesdrawerFunctionPlotJsxgraphMatterJs,
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                // heavy: warn before (and after) installing
                val warn = ai.antigem.theme.AppColors.warning
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(warn.copy(alpha = 0.12f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(Icons.Outlined.Warning, null, tint = warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        L.settings.stemRendering.heavyOnThePhoneEachDiagram,
                        fontSize = 12.5.sp, lineHeight = 17.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
                when (val s = status) {
                    is RenderBundle.Status.Downloading -> {
                        val fraction = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(L.settings.stemRendering.downloadingMbOfMb2(mb(s.done), mb(s.total)), fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
                            TextButton(onClick = { RenderBundle.cancel() }) { Text(L.common.cancel) }
                        }
                    }
                    RenderBundle.Status.Extracting -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(L.settings.stemRendering.unpacking, fontSize = 12.sp, color = muted)
                    }
                    else -> {
                        if (s is RenderBundle.Status.Failed) {
                            Text(s.message, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(6.dp))
                        }
                        if (installed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { confirmRemove = true }) { Text(L.common.remove) }
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = { RenderBundle.clearCache(); cacheTick++ },
                                    enabled = cacheBytes > 0
                                ) { Text("Clear TikZ cache (${mb(cacheBytes)})") }
                            }
                        } else {
                            Button(onClick = { RenderBundle.download(context) }) {
                                Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (s is RenderBundle.Status.Failed) L.common.tryAgain else L.settings.stemRendering.downloadBundle)
                            }
                        }
                    }
                }
            }
        }

        // ----- what to use -----
        Spacer(Modifier.height(22.dp))
        Text(L.settings.stemRendering.whatToUse, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (installed) L.settings.stemRendering.allOffAtFirstSwitchedOff else L.settings.stemRendering.allOffAtFirstSwitchedOff2,
            fontSize = 12.sp, color = muted
        )
        Spacer(Modifier.height(10.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                RenderComponent.entries.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(c.summary, fontSize = 12.sp, lineHeight = 16.sp, color = muted)
                            val note = when {
                                !c.fromBundle -> L.settings.stemRendering.libraryBuiltInNoDownload(c.library)
                                !installed -> L.settings.stemRendering.libraryVersionNeedsTheBundle(c.library, c.version)
                                else -> "${c.library} ${c.version}"
                            }
                            Text(note, fontSize = 11.5.sp, color = if (c in active) QuotaGreen else muted)
                        }
                        Spacer(Modifier.width(10.dp))
                        // bundle parts can be switched on only once the bundle is downloaded
                        Switch(
                            checked = c in active,
                            onCheckedChange = { RenderBundle.setEnabled(c, it) },
                            enabled = !c.fromBundle || installed
                        )
                    }
                }
            }
        }

        // ----- agents -----
        Spacer(Modifier.height(22.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = cardColor, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.settings.stemRendering.tellAgentsAboutTheseBlocks, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(L.settings.stemRendering.globalContextFirstMessageOfEach, fontSize = 12.sp, color = muted)
                    }
                    Switch(checked = tellAgents, onCheckedChange = { RenderBundle.setTellAgents(it) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    L.settings.stemRendering.aShortListOfTheBlocks((if (GlobalContext.canReadAppFiles) L.settings.stemRendering.andWhereTheFullGuideIs else "")),
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                TextButton(onClick = { showPrompt = !showPrompt }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (showPrompt) L.settings.stemRendering.hideTheText else L.settings.stemRendering.showExactlyWhatIsSent)
                }
                if (showPrompt) {
                    val text = remember(active, tellAgents, installed) {
                        if (!tellAgents) L.settings.stemRendering.offNothingIsSent
                        else RenderBundle.promptSection(canReadFiles = GlobalContext.canReadAppFiles) ?: L.settings.stemRendering.nothingSwitchedOn
                    }
                    SelectionContainer {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(10.dp)
                        )
                    }
                }
            }
        }

        // ----- guide -----
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { showGuide = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(L.settings.stemRendering.guideSyntaxAndLiveExamples)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(L.settings.stemRendering.removeTheBundle) },
            text = { Text(L.settings.stemRendering.blocksThatNeedItShowAs) },
            confirmButton = {
                TextButton(onClick = { RenderBundle.remove(); confirmRemove = false; cacheTick++ }) { Text(L.common.remove) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(L.common.cancel) } }
        )
    }
    if (showGuide) StemGuideDialog(onDismiss = { showGuide = false })
}

private fun mb(bytes: Long): String = if (bytes <= 0) "0 MB" else "%.1f MB".format(bytes / 1_048_576.0)

/** The guide, drawn by the chat's own renderer: every example shows as it will in a chat. */
@Composable
private fun StemGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember { parseMarkdownBlocks(RenderBundle.guideText(context), emptyList()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, L.common.close) }
                    Spacer(Modifier.width(6.dp))
                    Text(L.settings.stemRendering.stemDiagramsGuide, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    items(blocks) { block -> MarkdownBlockView(block = block) }
                }
            }
        }
    }
}
