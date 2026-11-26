package ai.antigem.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.i18n.L

/**
 * Shown (in the `:crash` process) after the app crashed: a dizzy little character, the error, and buttons to report
 * it as a GitHub issue, copy or share the report, or restart the app. The report comes from [CrashHandler].
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // scrubbed before it is shown: what the user sees is exactly what a report, copy or share sends
        val report = runCatching { CrashHandler.reportFile(this).readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { CrashReportScrubber.scrub(this, it) } ?: L.crash.activity.noCrashReportWasSaved
        // The error line (like "java.lang.OutOfMemoryError: …") is the first line after the header block.
        val errorLine = report.substringAfter("\n\n", report).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Terracotta, onPrimary = Color.White)
                else lightColorScheme(primary = Terracotta, onPrimary = Color.White)
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CrashScreen(
                        errorLine = errorLine,
                        report = report,
                        onReport = { reportOnGitHub(report, errorLine) },
                        onCopy = { copy(report); toast(L.crash.activity.reportCopied) },
                        onShare = { share(report) },
                        onRestart = { restartApp() },
                        onClose = { finishAndRemoveTask() }
                    )
                }
            }
        }
    }

    /** Copies the full report and opens a new GitHub issue with it filled in (cut to fit the link if it is long). */
    private fun reportOnGitHub(report: String, errorLine: String) {
        copy(report)
        val title = L.crash.activity.crashTitle(errorLine.ifBlank { L.crash.activity.theAppCrashed }.take(120))
        fun body(text: String, cut: Boolean) = buildString {
            appendLine("**What were you doing when it crashed?**")
            appendLine()
            appendLine("<!-- for example: typing a message in a chat -->")
            appendLine()
            appendLine("**Crash report**")
            appendLine()
            appendLine("```")
            appendLine(text)
            appendLine("```")
            if (cut) {
                appendLine()
                appendLine(L.crash.activity.theReportWasCutToFit)
            }
        }
        fun url(body: String) = "$ISSUES_URL?title=${Uri.encode(title)}&body=${Uri.encode(body)}"
        // Browsers and GitHub refuse very long links: drop lines from the end until it fits.
        var lines = report.lines()
        var link = url(body(report, cut = false))
        while (link.length > MAX_URL_CHARS && lines.size > 10) {
            lines = lines.dropLast(maxOf(1, lines.size / 10))
            link = url(body(lines.joinToString("\n") + "\n…", cut = true))
        }
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        toast(if (opened) L.crash.activity.reportCopiedTooPasteItIn else L.crash.activity.noBrowserFoundTheReportIs)
    }

    private fun copy(text: String) {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("AntiGem crash report", text))
        }
    }

    private fun share(text: String) {
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "AntiGem crash report")
                        .putExtra(Intent.EXTRA_TEXT, text),
                    L.crash.activity.shareCrashReport
                )
            )
        }.onFailure { toast(L.crash.activity.nothingToShareWith) }
    }

    private fun restartApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finishAndRemoveTask()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val ISSUES_URL = "https://github.com/Santoshkurmi/antigem/issues/new"
        const val MAX_URL_CHARS = 7500
    }
}

private val Terracotta = Color(0xFFD97757)

@Composable
private fun CrashScreen(
    errorLine: String,
    report: String,
    onReport: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        CrashBuddy(Modifier.size(width = 240.dp, height = 200.dp))
        Text(
            L.crash.buddy.tapOrPetToCheerUp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            L.crash.activity.oopsAntigemTrippedOverSomething,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            L.crash.activity.anErrorHappenedAndTheApp,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (errorLine.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    errorLine,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 4,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onReport, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            ButtonIcon(Icons.Default.BugReport)
            Text(L.crash.activity.reportOnGithub)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                ButtonIcon(Icons.Default.ContentCopy)
                Text(L.common.copy)
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                ButtonIcon(Icons.Default.Share)
                Text(L.common.share)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                ButtonIcon(Icons.Default.Refresh)
                Text(L.crash.activity.restartApp)
            }
            TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text(L.common.close) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            L.crash.activity.theReportHasTheErrorThe,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showDetails = !showDetails }) {
            ButtonIcon(if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore)
            Text(if (showDetails) L.crash.activity.hideDetails else L.crash.activity.showDetails)
        }
        AnimatedVisibility(visible = showDetails) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        report,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ButtonIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}
