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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shown (in the `:crash` process) after the app crashed: a dizzy little character, the error, and buttons to report
 * it as a GitHub issue, copy or share the report, or restart the app. The report comes from [CrashHandler].
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val report = runCatching { CrashHandler.reportFile(this).readText() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "No crash report was saved."
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
                        onCopy = { copy(report); toast("Report copied") },
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
        val title = "Crash: " + errorLine.ifBlank { "the app crashed" }.take(120)
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
                appendLine("_The report was cut to fit. The full report is in your clipboard: paste it here._")
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
        toast(if (opened) "Report copied too: paste it in the issue if anything is missing" else "No browser found. The report is copied.")
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
                    "Share crash report"
                )
            )
        }.onFailure { toast("Nothing to share with") }
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
        DizzyBlob(Modifier.size(190.dp))
        Text(
            "Oops! AntiGem tripped over something",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "An error happened and the app had to stop. Sorry about that! Reporting it on GitHub helps get it fixed.",
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
            Text("Report on GitHub")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                ButtonIcon(Icons.Default.ContentCopy)
                Text("Copy")
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                ButtonIcon(Icons.Default.Share)
                Text("Share")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRestart, modifier = Modifier.weight(1f)) {
                ButtonIcon(Icons.Default.Refresh)
                Text("Restart app")
            }
            TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "The report has the error, the app and Android versions and the phone model. Check it before posting: it may contain file or folder names.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showDetails = !showDetails }) {
            ButtonIcon(if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore)
            Text(if (showDetails) "Hide details" else "Show details")
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

/** A round little character that bobs, blinks, sweats and has stars circling its head. */
@Composable
private fun DizzyBlob(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dizzyBlob")
    val bob by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"
    )
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "spin")
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes {
            durationMillis = 3400
            1f at 0
            1f at 3050
            0.1f at 3150
            1f at 3250
        }),
        label = "blink"
    )
    val drop by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "drop")
    val ink = MaterialTheme.colorScheme.onBackground

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val r = w * 0.27f
        val cy = h * 0.6f - bob * h * 0.06f

        // shadow: smaller while the body is up
        val s = 1f - bob * 0.3f
        drawOval(
            ink.copy(alpha = 0.12f),
            topLeft = Offset(cx - r * s, h * 0.93f - r * 0.1f),
            size = Size(r * 2 * s, r * 0.2f)
        )

        rotate(degrees = (bob - 0.5f) * 10f, pivot = Offset(cx, cy)) {
            drawCircle(Terracotta, r, Offset(cx, cy))
            drawCircle(Color.White.copy(alpha = 0.25f), r * 0.22f, Offset(cx - r * 0.45f, cy - r * 0.5f)) // shine
            // cheeks
            drawCircle(Color(0x66FF6F91), r * 0.13f, Offset(cx - r * 0.58f, cy + r * 0.18f))
            drawCircle(Color(0x66FF6F91), r * 0.13f, Offset(cx + r * 0.58f, cy + r * 0.18f))
            // eyes (blinking)
            val eyeW = r * 0.17f
            val eyeH = r * 0.28f * blink
            listOf(-1f, 1f).forEach { side ->
                drawOval(
                    Color(0xFF2B2B2B),
                    topLeft = Offset(cx + side * r * 0.33f - eyeW / 2, cy - r * 0.12f - eyeH / 2),
                    size = Size(eyeW, eyeH)
                )
            }
            // wobbly frown
            val mouth = Path().apply {
                moveTo(cx - r * 0.24f, cy + r * 0.42f)
                cubicTo(cx - r * 0.1f, cy + r * 0.22f, cx + r * 0.1f, cy + r * 0.22f, cx + r * 0.24f, cy + r * 0.42f)
            }
            drawPath(mouth, Color(0xFF2B2B2B), style = Stroke(width = r * 0.08f, cap = StrokeCap.Round))
            // sweat drop sliding down and fading
            drawDrop(cx + r * 0.78f, cy - r * 0.55f + drop * r * 0.55f, r * 0.11f, Color(0xFF7EC8F2).copy(alpha = 1f - drop))
        }

        // stars circling the head
        repeat(3) { i ->
            val a = Math.toRadians((spin + i * 120f).toDouble())
            drawStar(
                cx + cos(a).toFloat() * r * 0.85f,
                cy - r * 1.2f + sin(a).toFloat() * r * 0.22f,
                r * (0.12f + 0.04f * sin(a).toFloat()),
                Color(0xFFFFC94D)
            )
        }
    }
}

private fun DrawScope.drawStar(x: Float, y: Float, radius: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val rr = if (i % 2 == 0) radius else radius * 0.45f
        val a = Math.toRadians(-90.0 + i * 36.0)
        val px = x + cos(a).toFloat() * rr
        val py = y + sin(a).toFloat() * rr
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.drawDrop(x: Float, y: Float, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(x, y - r * 1.7f)
        cubicTo(x + r, y - r * 0.4f, x + r, y + r, x, y + r)
        cubicTo(x - r, y + r, x - r, y - r * 0.4f, x, y - r * 1.7f)
        close()
    }
    drawPath(path, color)
}
