package com.example.gemini.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.example.gemini.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PriorityHigh
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import android.util.Log
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathDrawable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.gemini.data.remote.HubMediaResolver
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiBlue
import com.example.gemini.theme.QuotaGreen
import kotlinx.coroutines.delay
import java.util.regex.Pattern

enum class TableAlignment { LEFT, CENTER, RIGHT }

enum class AlertType { NOTE, TIP, IMPORTANT, WARNING, CAUTION }

@androidx.compose.runtime.Immutable
sealed class MarkdownBlock {
    @androidx.compose.runtime.Immutable
    data class Paragraph(val text: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Alert(val type: AlertType, val title: String? = null, val text: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class AgentThought(val thought: String, val durationMs: Long? = null, val isStreaming: Boolean = false) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class AgentTool(val toolCall: com.example.gemini.domain.model.ToolCall) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class AgentError(
        val title: String = "Error",
        val userMessage: String = "",
        val shortError: String = "",
        val fullError: String = "",
        val errorCode: Int? = null,
        val errorId: String = "",
        val rawJson: String = ""
    ) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Bullet(val indent: Int, val text: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Numbered(val number: String, val text: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Task(val isChecked: Boolean, val text: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Blockquote(val text: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Code(val language: String, val code: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Math(val latex: String, val isDisplay: Boolean = true) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Mermaid(val code: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class InteractiveUi(val htmlCode: String, val title: String = "Interactive App") : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Image(val alt: String, val url: String) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class YouTubeVideo(
        val videoId: String,
        val originalUrl: String,
        val title: String? = null,
        val customThumbnailUrl: String? = null
    ) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Details(val summary: String, val body: String, val defaultOpen: Boolean = false) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    data class Table(val headers: List<String>, val rows: List<List<String>>, val alignments: List<TableAlignment>) : MarkdownBlock()
    @androidx.compose.runtime.Immutable
    object HorizontalRule : MarkdownBlock()
}

@Composable
fun MarkdownBlockView(
    block: MarkdownBlock,
    modifier: Modifier = Modifier,
    onApproveTool: ((com.example.gemini.domain.model.ToolCall) -> Unit)? = null,
    onRejectTool: ((com.example.gemini.domain.model.ToolCall) -> Unit)? = null,
    onTerminateTool: ((com.example.gemini.domain.model.ToolCall) -> Unit)? = null,
    onSubmitChoices: ((com.example.gemini.domain.model.ToolCall, List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>, String) -> Unit)? = null,
    onSkipChoices: ((com.example.gemini.domain.model.ToolCall, List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>) -> Unit)? = null
) {
    when (block) {
        is MarkdownBlock.AgentThought -> {
            ThinkingAccordion(
                thoughtText = block.thought,
                durationMs = block.durationMs,
                isStreaming = block.isStreaming,
                modifier = modifier.padding(vertical = 4.dp)
            )
        }
        is MarkdownBlock.AgentTool -> {
            AgentToolCallCard(
                toolCall = block.toolCall,
                onApprove = onApproveTool,
                onReject = onRejectTool,
                onTerminate = onTerminateTool,
                onSubmitChoices = onSubmitChoices,
                onSkipChoices = onSkipChoices,
                modifier = modifier
            )
        }
        is MarkdownBlock.AgentError -> {
            AgentErrorCard(error = block, modifier = modifier)
        }
        is MarkdownBlock.InteractiveUi -> {
            InteractiveUiView(htmlCode = block.htmlCode, title = block.title, modifier = modifier)
        }
        is MarkdownBlock.Math -> {
            NativeMathView(latex = block.latex, isDisplay = block.isDisplay, modifier = modifier)
        }
        is MarkdownBlock.Mermaid -> {
            MermaidDiagramView(code = block.code, modifier = modifier)
        }
        is MarkdownBlock.Code -> {
            CodeBlock(code = block.code, language = block.language, modifier = modifier)
        }
        is MarkdownBlock.Table -> {
            MarkdownTableView(table = block, modifier = modifier)
        }
        is MarkdownBlock.Image -> {
            MarkdownImageView(image = block, modifier = modifier)
        }
        is MarkdownBlock.YouTubeVideo -> {
            YouTubeVideoView(video = block, modifier = modifier)
        }
        is MarkdownBlock.Details -> {
            MarkdownDetailsView(details = block, modifier = modifier)
        }
        is MarkdownBlock.Header -> {
            val (fontSize, topPad, bottomPad) = when (block.level) {
                1 -> Triple(20.sp, 10.dp, 5.dp)
                2 -> Triple(17.5.sp, 9.dp, 4.dp)
                3 -> Triple(15.5.sp, 8.dp, 4.dp)
                4 -> Triple(14.sp, 7.dp, 3.dp)
                5 -> Triple(13.sp, 6.dp, 2.dp)
                else -> Triple(12.5.sp, 5.dp, 2.dp)
            }
            FormattedInlineText(
                text = block.text,
                style = TextStyle(
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    color = if (block.level <= 3) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                ),
                modifier = modifier.padding(top = topPad, bottom = bottomPad)
            )
        }
        is MarkdownBlock.Bullet -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .padding(start = (block.indent * 14).dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = if (block.indent == 0) "• " else "◦ ",
                    fontWeight = FontWeight.Bold,
                    color = ClaudeTerracotta,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None
                        )
                    )
                )
                FormattedInlineText(
                    text = block.text,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is MarkdownBlock.Numbered -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "${block.number} ",
                    fontWeight = FontWeight.Bold,
                    color = ClaudeTerracotta,
                    fontSize = 13.5.sp,
                    lineHeight = 21.sp,
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.None
                        )
                    )
                )
                FormattedInlineText(
                    text = block.text,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is MarkdownBlock.Task -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (block.isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (block.isChecked) "Completed" else "Incomplete",
                    tint = if (block.isChecked) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .padding(top = 1.5.dp, end = 6.dp)
                        .size(17.dp)
                )
                FormattedInlineText(
                    text = block.text,
                    isStrikethrough = block.isChecked,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        is MarkdownBlock.Alert -> {
            MarkdownAlertView(
                alert = block,
                modifier = modifier
            )
        }

        is MarkdownBlock.Blockquote -> {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ) {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(4.dp)
                            .background(ClaudeTerracotta)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    FormattedInlineText(
                        text = block.text,
                        modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp)
                    )
                }
            }
        }
        is MarkdownBlock.HorizontalRule -> {
            HorizontalDivider(
                modifier = modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
        }
        is MarkdownBlock.Paragraph -> {
            FormattedInlineText(
                text = block.text,
                modifier = modifier.padding(vertical = 3.dp)
            )
        }
    }
}

private data class AlertStyle(
    val accentColor: Color,
    val lightBg: Color,
    val darkBg: Color,
    val defaultTitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun MarkdownAlertView(
    alert: MarkdownBlock.Alert,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val style = when (alert.type) {
        AlertType.NOTE -> AlertStyle(
            accentColor = Color(0xFF3B82F6),
            lightBg = Color(0xFFEFF6FF),
            darkBg = Color(0x223B82F6),
            defaultTitle = "Note",
            icon = Icons.Outlined.Info
        )
        AlertType.TIP -> AlertStyle(
            accentColor = Color(0xFF10B981),
            lightBg = Color(0xFFECFDF5),
            darkBg = Color(0x2210B981),
            defaultTitle = "Tip",
            icon = Icons.Outlined.Lightbulb
        )
        AlertType.IMPORTANT -> AlertStyle(
            accentColor = Color(0xFF8B5CF6),
            lightBg = Color(0xFFF5F3FF),
            darkBg = Color(0x228B5CF6),
            defaultTitle = "Important",
            icon = Icons.Outlined.PriorityHigh
        )
        AlertType.WARNING -> AlertStyle(
            accentColor = Color(0xFFF59E0B),
            lightBg = Color(0xFFFFFBEB),
            darkBg = Color(0x22F59E0B),
            defaultTitle = "Warning",
            icon = Icons.Outlined.Warning
        )
        AlertType.CAUTION -> AlertStyle(
            accentColor = Color(0xFFEF4444),
            lightBg = Color(0xFFFEF2F2),
            darkBg = Color(0x22EF4444),
            defaultTitle = "Caution",
            icon = Icons.Outlined.Report
        )
    }

    val displayTitle = alert.title ?: style.defaultTitle

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (isDark) style.darkBg else style.lightBg,
        border = BorderStroke(1.dp, style.accentColor.copy(alpha = if (isDark) 0.35f else 0.25f))
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(style.accentColor)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = if (alert.text.isNotBlank()) 4.dp else 0.dp)
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = displayTitle,
                        tint = style.accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displayTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = style.accentColor,
                        letterSpacing = 0.3.sp
                    )
                }

                if (alert.text.isNotBlank()) {
                    FormattedInlineText(
                        text = alert.text,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}



@Composable
fun MarkdownContent(
    content: String,
    toolCalls: List<com.example.gemini.domain.model.ToolCall> = emptyList(),
    modifier: Modifier = Modifier,
    onApproveTool: ((com.example.gemini.domain.model.ToolCall) -> Unit)? = null,
    onRejectTool: ((com.example.gemini.domain.model.ToolCall) -> Unit)? = null,
    onTerminateTool: ((com.example.gemini.domain.model.ToolCall) -> Unit)? = null,
    onSubmitChoices: ((com.example.gemini.domain.model.ToolCall, List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>, String) -> Unit)? = null,
    onSkipChoices: ((com.example.gemini.domain.model.ToolCall, List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>) -> Unit)? = null
) {
    val t0 = System.nanoTime()
    val blocks = remember(content, toolCalls) {
        val bStart = System.nanoTime()
        val parsed = parseMarkdownBlocks(content, toolCalls)
        val bDt = (System.nanoTime() - bStart) / 1_000_000.0
        Log.d("PERF_TRACE", "  🔨 [Markdown Parse] len=${content.length}, blocks=${parsed.size}, took=${"%.2f".format(bDt)}ms")
        parsed
    }

    SideEffect {
        val dt = (System.nanoTime() - t0) / 1_000_000.0
        if (dt > 1.0) {
            Log.w("PERF_TRACE", "  📦 [MarkdownContent Comp] len=${content.length}, blocks=${blocks.size}, took=${"%.2f".format(dt)}ms")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEach { block ->
            MarkdownBlockView(
                block = block,
                onApproveTool = onApproveTool,
                onRejectTool = onRejectTool,
                onTerminateTool = onTerminateTool,
                onSubmitChoices = onSubmitChoices,
                onSkipChoices = onSkipChoices
            )
        }
    }
}

private val mathDrawableCache = android.util.LruCache<Int, JLatexMathDrawable>(150)

/**
 * 100% Native Android Canvas JLaTeXMath Renderer:
 * Renders limits, integrals, fractions, matrices, square roots, and complex LaTeX formulas
 * directly to native Android Canvas with zero WebViews, 120 FPS performance, and 0ms latency.
 */
@Composable
fun NativeMathView(
    latex: String,
    isDisplay: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isDark = isAppInDarkTheme()
    val textColor = if (isDark) android.graphics.Color.parseColor("#ECECF1") else android.graphics.Color.parseColor("#1A1A1A")
    val density = LocalDensity.current
    val textSizePx = with(density) { (if (isDisplay) 18.sp else 15.sp).toPx() }

    val cleanLatex = remember(latex) {
        latex.trim()
            .removePrefix("$$").removeSuffix("$$")
            .removePrefix("\\[").removeSuffix("\\]")
            .removePrefix("$").removeSuffix("$")
            .trim()
    }

    val cacheKey = cleanLatex.hashCode() * 31 + textColor * 7 + textSizePx.toInt()
    val jLatexDrawable = remember(cleanLatex, textColor, textSizePx, isDark) {
        mathDrawableCache.get(cacheKey) ?: run {
            val built = try {
                // First attempt: Colorized LaTeX formula with syntax highlighting
                val colorizedLatex = colorizeLatexEquation(cleanLatex, isDark)
                JLatexMathDrawable.builder(colorizedLatex)
                    .textSize(textSizePx)
                    .color(textColor)
                    .background(android.graphics.Color.TRANSPARENT)
                    .build()
            } catch (_: Exception) {
                try {
                    // Fallback: Standard monochrome native LaTeX
                    JLatexMathDrawable.builder(cleanLatex)
                        .textSize(textSizePx)
                        .color(textColor)
                        .background(android.graphics.Color.TRANSPARENT)
                        .build()
                } catch (_: Exception) {
                    null
                }
            }
            if (built != null) {
                mathDrawableCache.put(cacheKey, built)
            }
            built
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = if (isDisplay) Alignment.Center else Alignment.CenterStart
        ) {
            if (jLatexDrawable != null) {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            adjustViewBounds = true
                            setImageDrawable(jLatexDrawable)
                        }
                    },
                    update = { view ->
                        view.setImageDrawable(jLatexDrawable)
                    }
                )
            } else {
                // High-performance typographic fallback if LaTeX formula syntax contains custom non-standard commands
                Text(
                    text = formatLatexToNativeMath(cleanLatex),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = if (isDisplay) 16.5.sp else 14.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Interactive Mermaid Diagram Renderer:
 * Supports Flowcharts, Sequence Diagrams, Class Diagrams, State Diagrams, ER diagrams, Mindmaps, Git graphs.
 * Features Diagram / Code tabs and copy support.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidDiagramView(
    code: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isAppInDarkTheme()
    var selectedTab by remember { mutableStateOf(0) } // 0 = Diagram, 1 = Code
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2500)
            isCopied = false
        }
    }

    val safeCode = remember(code) {
        code.trim()
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")
    }

    val mermaidTheme = if (isDark) "dark" else "default"
    val htmlContent = remember(safeCode, mermaidTheme) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
            <script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
            <style>
                * { box-sizing: border-box; }
                html, body {
                    margin: 0;
                    padding: 10px;
                    background-color: transparent;
                    width: 100%;
                    min-height: 100%;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    overflow: auto;
                    font-family: system-ui, -apple-system, sans-serif;
                }
                .mermaid {
                    width: 100%;
                    display: flex;
                    justify-content: center;
                }
                svg {
                    max-width: 100% !important;
                    height: auto !important;
                }
            </style>
        </head>
        <body>
            <div class="mermaid">
                $safeCode
            </div>
            <script>
                try {
                    mermaid.initialize({
                        startOnLoad: true,
                        theme: '$mermaidTheme',
                        securityLevel: 'loose'
                    });
                } catch (e) {
                    document.body.innerText = '$safeCode';
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "MERMAID DIAGRAM",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ClaudeTerracotta,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.weight(1f))

                // Diagram / Code Switcher Pills
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selectedTab == 0) ClaudeTerracotta else Color.Transparent)
                            .clickable { selectedTab = 0 }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Diagram",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selectedTab == 0) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selectedTab == 1) ClaudeTerracotta else Color.Transparent)
                            .clickable { selectedTab = 1 }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Code",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selectedTab == 1) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Mermaid Diagram", code)
                        clipboard.setPrimaryClip(clip)
                        isCopied = true
                        Toast.makeText(context, "Diagram code copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    if (isCopied) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Copied",
                            tint = QuotaGreen,
                            modifier = Modifier.size(15.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // Body: Visual Diagram vs Raw Code
            if (selectedTab == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 420.dp)
                        .padding(6.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                isNestedScrollingEnabled = false
                                isVerticalScrollBarEnabled = false
                                isHorizontalScrollBarEnabled = false
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                tag = htmlContent
                                loadDataWithBaseURL("https://cdn.jsdelivr.net", htmlContent, "text/html", "UTF-8", null)
                            }
                        },
                        update = { webView ->
                            if (webView.tag != htmlContent) {
                                webView.tag = htmlContent
                                webView.loadDataWithBaseURL("https://cdn.jsdelivr.net", htmlContent, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                CodeBlock(code = code, language = "mermaid")
            }
        }
    }
}

/**
 * Intelligent LaTeX syntax highlighter that colorizes mathematical components
 * (numbers in warm amber/orange, calculus operators in cyan/teal, Greek letters in yellow/purple, functions in green/blue)
 * to provide a rich, visually stunning textbook appearance.
 */
fun colorizeLatexEquation(latex: String, isDark: Boolean): String {
    // If the input already has manual color tags, don't double colorize
    if (latex.contains("\\color") || latex.contains("\\textcolor")) {
        return latex
    }

    val numColor = if (isDark) "orange" else "orange"
    val opColor = if (isDark) "cyan" else "teal"
    val greekColor = if (isDark) "yellow" else "purple"
    val funcColor = if (isDark) "green" else "blue"

    var result = latex

    // 1. Colorize Big operators: \int, \iint, \iiint, \oint, \sum, \prod, \coprod, \lim
    result = result.replace(Regex("(\\\\(?:int|iint|iiint|oint|sum|prod|coprod|lim)\\b)"), "\\\\textcolor{$opColor}{$1}")

    // 2. Colorize Greek Letters
    val greekRegex = Regex("(\\\\(?:alpha|beta|gamma|delta|epsilon|varepsilon|zeta|eta|theta|vartheta|iota|kappa|lambda|mu|nu|xi|pi|varpi|rho|varrho|sigma|varsigma|tau|upsilon|phi|varphi|chi|psi|omega|Gamma|Delta|Theta|Lambda|Xi|Pi|Sigma|Upsilon|Phi|Psi|Omega)\\b)")
    result = result.replace(greekRegex, "\\\\textcolor{$greekColor}{$1}")

    // 3. Colorize Math Functions: \sin, \cos, \tan, \cot, \sec, \csc, \log, \ln, \exp, \det
    val funcRegex = Regex("(\\\\(?:sin|cos|tan|cot|sec|csc|log|ln|exp|det|max|min)\\b)")
    result = result.replace(funcRegex, "\\\\textcolor{$funcColor}{$1}")

    // 4. Colorize Numbers and constants (not preceded by backslash or letters)
    result = result.replace(Regex("(?<![a-zA-Z\\\\])(\\b\\d+(?:\\.\\d+)?\\b)"), "\\\\textcolor{$numColor}{$1}")

    return result
}

/**
 * Fast Native LaTeX Math formatter:
 * Converts LaTeX commands, Greek letters, operators, superscripts, subscripts, and symbols
 * into crisp Unicode mathematical typography natively without any JavaScript or WebViews.
 */
fun formatLatexToNativeMath(rawLatex: String): String {
    var text = rawLatex.trim()
        .removePrefix("$$").removeSuffix("$$")
        .removePrefix("\\[").removeSuffix("\\]")
        .removePrefix("$").removeSuffix("$")
        .trim()

    // 1. Remove layout wrappers and text formatting commands
    text = text.replace(Regex("\\\\left\\b|\\\\right\\b"), "")
    text = text.replace(Regex("\\\\(?:text|mathrm|mathbf|mathit|operatorname)\\{([^}]*)\\}"), "$1")
    text = text.replace(Regex("\\\\displaystyle\\b"), "")

    // 2. Fractions: \frac{num}{den}, \dfrac, \tfrac, \cfrac with nested braces & parentheses support
    val fracRegex = Regex("\\\\(?:frac|dfrac|tfrac|cfrac)\\{((?:[^{}]|\\{[^{}]*\\})+)\\}\\{((?:[^{}]|\\{[^{}]*\\})+)\\}")
    var fracIterations = 0
    while (fracRegex.containsMatchIn(text) && fracIterations < 10) {
        text = fracRegex.replace(text) { match ->
            val rawNum = match.groupValues[1].trim()
            val rawDen = match.groupValues[2].trim()
            val num = formatLatexToNativeMath(rawNum)
            val den = formatLatexToNativeMath(rawDen)

            when {
                num == "1" && den == "2" -> "½"
                num == "1" && den == "3" -> "⅓"
                num == "2" && den == "3" -> "⅔"
                num == "1" && den == "4" -> "¼"
                num == "3" && den == "4" -> "¾"
                num == "1" && den == "5" -> "⅕"
                num == "1" && den == "8" -> "⅛"
                !num.contains(" + ") && !num.contains(" - ") && !den.contains(" + ") && !den.contains(" - ") -> "$num/$den"
                else -> "($num) / ($den)"
            }
        }
        fracIterations++
    }

    // 3. Square roots: \sqrt{x} -> √(x), \sqrt[n]{x} -> ⁿ√(x)
    text = text.replace(Regex("\\\\sqrt\\[([^]]+)\\]\\{([^}]+)\\}"), "($1)√($2)")
    text = text.replace(Regex("\\\\sqrt\\{([^}]+)\\}"), "√($1)")

    // 4. Integrals, Summations, Limits
    text = text.replace(Regex("\\\\int_\\{([^}]+)\\}\\^\\{([^}]+)\\}"), "∫_{$1}^{$2} ")
    text = text.replace(Regex("\\\\sum_\\{([^}]+)\\}\\^\\{([^}]+)\\}"), "∑_{$1}^{$2} ")
    text = text.replace(Regex("\\\\prod_\\{([^}]+)\\}\\^\\{([^}]+)\\}"), "∏_{$1}^{$2} ")
    text = text.replace(Regex("\\\\lim_\\{([^}]+)\\}"), "lim_{$1} ")

    // 5. Greek Letters (Lower and Upper)
    val greekMap = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
        "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ", "\\varrho" to "ϱ",
        "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ",
        "\\phi" to "φ", "\\varphi" to "ϕ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω"
    )
    for ((latexCmd, symbol) in greekMap) {
        text = text.replace(Regex(Regex.escape(latexCmd) + "(?![a-zA-Z])"), symbol)
    }

    // 6. Mathematical Operators and Relations
    val symbolMap = mapOf(
        "\\pm" to "±", "\\mp" to "∓", "\\times" to "×", "\\cdot" to "·", "\\div" to "÷",
        "\\circ" to "∘", "\\bullet" to "•", "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇",
        "\\int" to "∫", "\\iint" to "∬", "\\iiint" to "∭", "\\oint" to "∮",
        "\\sum" to "∑", "\\prod" to "∏",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠",
        "\\approx" to "≈", "\\equiv" to "≡", "\\sim" to "∼", "\\propto" to "∝",
        "\\ll" to "≪", "\\gg" to "≫", "\\in" to "∈", "\\notin" to "∉",
        "\\subset" to "⊂", "\\subseteq" to "⊆", "\\supset" to "⊃", "\\supseteq" to "⊇",
        "\\cap" to "∩", "\\cup" to "∪", "\\forall" to "∀", "\\exists" to "∃", "\\nexists" to "∄",
        "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←", "\\Rightarrow" to "⇒",
        "\\Leftarrow" to "⇐", "\\iff" to "⇔", "\\mapsto" to "↦",
        "\\dots" to "…", "\\cdots" to "⋯", "\\ddots" to "⋱", "\\vdots" to "⋮",
        "\\quad" to "   ", "\\qquad" to "      ", "\\," to " ", "\\;" to " ", "\\!" to ""
    )
    for ((latexCmd, symbol) in symbolMap) {
        text = text.replace(Regex(Regex.escape(latexCmd) + "(?![a-zA-Z])"), symbol)
    }

    // 7. Superscript conversion (e.g. ^2, ^{10}, ^x)
    val supMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
        'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ'
    )
    text = text.replace(Regex("\\^\\{([^}]+)\\}|\\^([0-9a-zA-Z+-=()])")) { match ->
        val group = (match.groups[1]?.value ?: match.groups[2]?.value) ?: ""
        val converted = group.map { supMap[it] ?: it }.joinToString("")
        if (converted.all { it in supMap.values }) converted else "^($group)"
    }

    // 8. Subscript conversion (e.g. _0, _{i+1})
    val subMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ'
    )
    text = text.replace(Regex("_\\{([^}]+)\\}|_([0-9a-zA-Z+-=()])")) { match ->
        val group = (match.groups[1]?.value ?: match.groups[2]?.value) ?: ""
        val converted = group.map { subMap[it] ?: it }.joinToString("")
        if (converted.all { it in subMap.values }) converted else "_($group)"
    }

    return text.trim()
}

/**
 * Expandable <details><summary> component with animated rotation and embedded markdown content.
 */
@Composable
fun MarkdownDetailsView(
    details: MarkdownBlock.Details,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(details.defaultOpen) }
    val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "details_arrow")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = ClaudeTerracotta,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FormattedInlineText(
                    text = details.summary,
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
                ) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    MarkdownContent(content = details.body)
                }
            }
        }
    }
}

/**
 * Polished Error Card rendering structured model/system/network/auth errors with user summary,
 * error code badges, and an expandable verbose/JSON stack trace accordion with copy-to-clipboard.
 */
@Composable
fun AgentErrorCard(
    error: MarkdownBlock.AgentError,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    val isDark = isAppInDarkTheme()

    val cardBg = if (isDark) Color(0xFF2A1515) else Color(0xFFFDECEC)
    val cardBorder = if (isDark) Color(0xFF742A2A) else Color(0xFFF5C2C2)
    val errorColor = if (isDark) Color(0xFFFF6B6B) else Color(0xFFD32F2F)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header Row: Icon, Title, Error Code pill, Copy Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = "Error",
                    tint = errorColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = error.title.ifBlank { "Agent Execution Error" },
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = errorColor
                )
                if (error.errorCode != null && error.errorCode != 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = errorColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "Code: ${error.errorCode}",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = errorColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val copyText = buildString {
                            appendLine("Error: ${error.title}")
                            if (error.userMessage.isNotBlank()) appendLine("Message: ${error.userMessage}")
                            if (error.shortError.isNotBlank()) appendLine("Detail: ${error.shortError}")
                            if (error.errorCode != null) appendLine("Code: ${error.errorCode}")
                            if (error.errorId.isNotBlank()) appendLine("ID: ${error.errorId}")
                            if (error.fullError.isNotBlank()) appendLine("\nFull Trace:\n${error.fullError}")
                            if (error.rawJson.isNotBlank()) appendLine("\nRaw JSON:\n${error.rawJson}")
                        }.trim()
                        val clip = ClipData.newPlainText("Error Details", copyText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Error details copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Error Details",
                        tint = errorColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // User message / short error
            if (error.userMessage.isNotBlank() && error.userMessage != error.title) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error.userMessage,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    lineHeight = 18.sp
                )
            }

            if (error.shortError.isNotBlank() && error.shortError != error.userMessage) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error.shortError,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    lineHeight = 17.sp
                )
            }

            // Full Error / Stack Trace / JSON Accordion if present
            val hasVerboseDetails = error.fullError.isNotBlank() || error.rawJson.isNotBlank() || error.errorId.isNotBlank()
            if (hasVerboseDetails) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { isExpanded = !isExpanded }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (isExpanded) 180f else 0f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isExpanded) "Hide Error Details & Stack Trace" else "View Error Details & Stack Trace",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF1E1010) else Color(0xFFF7E2E2))
                            .padding(8.dp)
                    ) {
                        if (error.errorId.isNotBlank()) {
                            Text(
                                text = "Error ID: ${error.errorId}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        val verboseText = error.fullError.ifBlank { error.rawJson }
                        SelectionContainer {
                            Text(
                                text = verboseText,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders an Image in Markdown using Coil with rounded corners and full-screen viewer.
 */
@Composable
fun MarkdownImageView(
    image: MarkdownBlock.Image,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showFullDialog by remember { mutableStateOf(false) }
    var resolvedUrl by remember(image.url) {
        mutableStateOf(HubMediaResolver.getResolvedUriSync(context, image.url))
    }

    LaunchedEffect(image.url) {
        if (!HubMediaResolver.isLocalOrCached(context, image.url)) {
            val res = HubMediaResolver.resolveMediaUri(context, image.url)
            if (res.isNotBlank()) resolvedUrl = res
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    RoundedCornerShape(12.dp)
                )
                .clickable {
                    showFullDialog = true
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(resolvedUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = image.alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 340.dp)
            )
        }

        if (image.alt.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = image.alt,
                fontSize = 11.5.sp,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }

    if (showFullDialog) {
        FullScreenImageDialog(
            imageUrl = resolvedUrl,
            title = image.alt,
            onDismiss = { showFullDialog = false }
        )
    }
}

private val YOUTUBE_EXTRACT_REGEX = Regex(
    """(?:https?://)?(?:www\.|m\.)?(?:youtube\.com/(?:watch\?(?:.*&)?v=|embed/|v/|shorts/)|youtu\.be/|img\.youtube\.com/vi/)([a-zA-Z0-9_-]{11})""",
    RegexOption.IGNORE_CASE
)

fun extractYouTubeVideoId(url: String): String? {
    val match = YOUTUBE_EXTRACT_REGEX.find(url)
    return match?.groupValues?.getOrNull(1)
}

/**
 * Embedded playable YouTube Video player card with 16:9 placeholder thumbnail,
 * direct in-chat WebView iframe playback, and an open in YouTube app / browser button.
 */
@Composable
fun YouTubeVideoView(
    video: MarkdownBlock.YouTubeVideo,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val videoUrl = remember(video.videoId, video.originalUrl) {
        if (video.originalUrl.isNotBlank() && (video.originalUrl.contains("youtube.com") || video.originalUrl.contains("youtu.be"))) {
            video.originalUrl
        } else {
            "https://www.youtube.com/watch?v=${video.videoId}"
        }
    }
    val thumbnailUrl = remember(video.videoId, video.customThumbnailUrl) {
        video.customThumbnailUrl?.takeIf { it.isNotBlank() }
            ?: "https://img.youtube.com/vi/${video.videoId}/hqdefault.jpg"
    }
    val activePlayback by com.example.gemini.data.media.YouTubeMediaSessionManager.playbackState.collectAsState()
    val isPlaying = activePlayback?.videoId == video.videoId
    val onStartPlay: () -> Unit = {
        com.example.gemini.data.media.YouTubeMediaSessionManager.getOrCreatePlayer(
            context = context,
            video = video
        )
    }
    var customFullscreenView by remember { mutableStateOf<android.view.View?>(null) }
    var customViewCallback by remember { mutableStateOf<android.webkit.WebChromeClient.CustomViewCallback?>(null) }

    if (customFullscreenView != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {
                try {
                    customViewCallback?.onCustomViewHidden()
                } catch (_: Exception) {}
                customFullscreenView = null
                customViewCallback = null
            },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            val dialogView = androidx.compose.ui.platform.LocalView.current
            DisposableEffect(dialogView) {
                val window = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                    ?: (dialogView.context as? android.app.Activity)?.window
                if (window != null) {
                    val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, dialogView)
                    insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                onDispose {
                    if (window != null) {
                        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, dialogView)
                        insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .safeDrawingPadding(),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { _ ->
                        val v = customFullscreenView!!
                        (v.parent as? android.view.ViewGroup)?.removeView(v)
                        v
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // YouTube Red Icon
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFF0000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "YouTube",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = video.title?.takeIf { it.isNotBlank() } ?: "YouTube Video",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPlaying) {
                        IconButton(
                            onClick = {
                                com.example.gemini.data.media.YouTubeMediaSessionManager.release()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close Player",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Open in External App / Browser
                    IconButton(
                        onClick = {
                            try {
                                uriHandler.openUri(videoUrl)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInNew,
                            contentDescription = "Open in YouTube",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 16:9 Video Player / Thumbnail Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (!isPlaying) {
                    // Thumbnail Preview
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = video.title ?: "YouTube Video Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onStartPlay() }
                    )

                    // Soft dark gradient overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.45f)
                                    )
                                )
                            )
                            .clickable { onStartPlay() }
                    )

                    // Big Centered Play Button
                    Surface(
                        onClick = onStartPlay,
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFFFF0000).copy(alpha = 0.92f),
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(width = 62.dp, height = 42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play Video",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                } else {
                    DisposableEffect(video.videoId) {
                        onDispose {
                            com.example.gemini.data.media.YouTubeMediaSessionManager.parkActivePlayer(context)
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            com.example.gemini.data.media.YouTubeMediaSessionManager.getOrCreatePlayer(
                                context = ctx,
                                video = video,
                                onCustomView = { view, callback ->
                                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                                    customFullscreenView = view
                                    customViewCallback = callback
                                },
                                onHideCustomView = {
                                    try {
                                        customViewCallback?.onCustomViewHidden()
                                    } catch (_: Exception) {}
                                    customFullscreenView = null
                                    customViewCallback = null
                                }
                            )
                        },
                        update = { _ ->
                            com.example.gemini.data.media.YouTubeMediaSessionManager.updateCallbacks(
                                onCustomView = { view, callback ->
                                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                                    customFullscreenView = view
                                    customViewCallback = callback
                                },
                                onHideCustomView = {
                                    try {
                                        customViewCallback?.onCustomViewHidden()
                                    } catch (_: Exception) {}
                                    customFullscreenView = null
                                    customViewCallback = null
                                }
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Native Jetpack Compose Table renderer with horizontal scrolling and styled borders.
 */
@Composable
fun MarkdownTableView(
    table: MarkdownBlock.Table,
    modifier: Modifier = Modifier
) {
    val t0 = System.nanoTime()
    val isDark = isAppInDarkTheme()
    val density = LocalDensity.current

    val numCols = maxOf(
        table.headers.size,
        table.rows.maxOfOrNull { it.size } ?: 0,
        table.alignments.size
    )
    if (numCols == 0) return

    val normalizedHeaders = remember(table.headers, numCols) {
        if (table.headers.size < numCols) {
            table.headers + List(numCols - table.headers.size) { "" }
        } else {
            table.headers.take(numCols)
        }
    }
    val normalizedRows = remember(table.rows, numCols) {
        table.rows.map { row ->
            if (row.size < numCols) {
                row + List(numCols - row.size) { "" }
            } else {
                row.take(numCols)
            }
        }
    }

    // Memoize cell AnnotatedStrings so table layout & scrolling takes 0.00ms
    val cachedHeaders = remember(normalizedHeaders, isDark, density) {
        normalizedHeaders.map { header ->
            buildRichAnnotatedString(header, false, isDark, density)
        }
    }
    val cachedRows = remember(normalizedRows, isDark, density) {
        normalizedRows.map { row ->
            row.map { cell ->
                buildRichAnnotatedString(cell, false, isDark, density)
            }
        }
    }

    // Compute consistent natural column widths for every column based on maximum content length
    val colNaturalWidths = remember(normalizedHeaders, normalizedRows, numCols) {
        (0 until numCols).map { colIdx ->
            val headerLen = normalizedHeaders.getOrNull(colIdx)?.length ?: 0
            val maxRowLen = normalizedRows.maxOfOrNull { it.getOrNull(colIdx)?.length ?: 0 } ?: 0
            val maxLen = maxOf(headerLen, maxRowLen)
            // Average character width ~8.5dp at 13sp + padding (24dp) + safety buffer
            (maxLen * 8.5f + 28f).coerceIn(80f, 280f)
        }
    }

    SideEffect {
        val dt = (System.nanoTime() - t0) / 1_000_000.0
        Log.d("PERF_TRACE", "📊 [Table Comp] rows=${table.rows.size}, cols=$numCols, took=${"%.2f".format(dt)}ms")
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                RoundedCornerShape(10.dp)
            )
    ) {
        val availableWidthDp = maxWidth.value
        val hasBoundedWidth = maxWidth != androidx.compose.ui.unit.Dp.Infinity &&
                maxWidth != androidx.compose.ui.unit.Dp.Unspecified &&
                availableWidthDp > 0f
        val totalNaturalWidth = colNaturalWidths.sum()
        val dividerTotalWidth = ((numCols - 1).coerceAtLeast(0) * 1f)
        val netAvailableWidth = (availableWidthDp - dividerTotalWidth).coerceAtLeast(0f)

        // Scale widths to fill available space if it fits comfortably without wrapping too small,
        // otherwise retain natural widths and allow smooth horizontal scrolling.
        val finalWidths = remember(colNaturalWidths, netAvailableWidth, totalNaturalWidth, hasBoundedWidth) {
            if (hasBoundedWidth && netAvailableWidth > totalNaturalWidth && totalNaturalWidth > 0f) {
                val scale = netAvailableWidth / totalNaturalWidth
                if (scale <= 2.2f) {
                    colNaturalWidths.map { (it * scale).dp }
                } else {
                    colNaturalWidths.map { (it * 1.5f).dp }
                }
            } else {
                colNaturalWidths.map { it.dp }
            }
        }

        val tableTotalWidth = remember(finalWidths, numCols) {
            finalWidths.fold(0.dp) { acc, dp -> acc + dp } + (numCols - 1).coerceAtLeast(0).dp
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        ) {
            val dividerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .width(tableTotalWidth)
            ) {
                // Table Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
                ) {
                    cachedHeaders.forEachIndexed { colIdx, headerResult ->
                        if (colIdx > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(dividerColor)
                            )
                        }

                        val colWidth = finalWidths.getOrElse(colIdx) { 100.dp }
                        val alignment = table.alignments.getOrElse(colIdx) { TableAlignment.LEFT }
                        val textAlign = when (alignment) {
                            TableAlignment.CENTER -> TextAlign.Center
                            TableAlignment.RIGHT -> TextAlign.End
                            TableAlignment.LEFT -> TextAlign.Start
                        }

                        Box(
                            modifier = Modifier
                                .width(colWidth)
                                .fillMaxHeight()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.TopStart
                        ) {
                            Text(
                                text = headerResult.annotatedString,
                                inlineContent = headerResult.inlineContent,
                                modifier = Modifier.fillMaxWidth(),
                                style = TextStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = textAlign
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))

                // Table Data Rows
                cachedRows.forEachIndexed { rowIdx, rowCells ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .background(
                                if (rowIdx % 2 == 0) Color.Transparent
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                            )
                    ) {
                        rowCells.forEachIndexed { colIdx, cellResult ->
                            if (colIdx > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(1.dp)
                                        .background(dividerColor)
                                )
                            }

                            val colWidth = finalWidths.getOrElse(colIdx) { 100.dp }
                            val alignment = table.alignments.getOrElse(colIdx) { TableAlignment.LEFT }
                            val textAlign = when (alignment) {
                                TableAlignment.CENTER -> TextAlign.Center
                                TableAlignment.RIGHT -> TextAlign.End
                                TableAlignment.LEFT -> TextAlign.Start
                            }

                            Box(
                                modifier = Modifier
                                    .width(colWidth)
                                    .fillMaxHeight()
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.TopStart
                            ) {
                                Text(
                                    text = cellResult.annotatedString,
                                    inlineContent = cellResult.inlineContent,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = TextStyle(
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = textAlign
                                    )
                                )
                            }
                        }
                    }

                    if (rowIdx < cachedRows.size - 1) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}

data class FormattedInlineResult(
    val annotatedString: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>
)

fun parseHexOrRgbColor(str: String): Color? {
    val clean = str.trim()
    if (clean.matches(Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$"))) {
        return try {
            val hex = clean.removePrefix("#")
            val argb = when (hex.length) {
                3 -> {
                    val r = hex[0]; val g = hex[1]; val b = hex[2]
                    "FF$r$r$g$g$b$b".toLong(16)
                }
                6 -> "FF$hex".toLong(16)
                8 -> hex.toLong(16)
                else -> return null
            }
            Color(argb)
        } catch (_: Exception) { null }
    }
    if (clean.matches(Regex("^(?:Color\\()?0x([0-9a-fA-F]{6,8})\\)?$"))) {
        return try {
            val hex = clean.replace(Regex("^(?:Color\\()?0x"), "").removeSuffix(")")
            val argb = if (hex.length == 6) "FF$hex".toLong(16) else hex.toLong(16)
            Color(argb)
        } catch (_: Exception) { null }
    }
    val rgbMatch = Regex("^rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*([\\d.]+))?\\s*\\)$", RegexOption.IGNORE_CASE).find(clean)
    if (rgbMatch != null) {
        return try {
            val r = rgbMatch.groupValues[1].toInt().coerceIn(0, 255)
            val g = rgbMatch.groupValues[2].toInt().coerceIn(0, 255)
            val b = rgbMatch.groupValues[3].toInt().coerceIn(0, 255)
            val a = (rgbMatch.groupValues.getOrNull(4)?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
            Color(r / 255f, g / 255f, b / 255f, a)
        } catch (_: Exception) { null }
    }
    return null
}

/**
 * Formats rich inline Markdown text with native JLatexMath inline rendering for symbols & equations,
 * and live Color Swatch previews in code.
 */
private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

private val INLINE_MARKDOWN_PATTERN: Pattern = Pattern.compile(
    "(\\[(.*?)\\]\\(((?:https?|file|conversation)://[^\\s)]+|/[^\\s)]+)\\))|" +       // 1: Markdown Link [text](url)
    "(<a\\s+href=[\"']((?:https?|file|conversation)://[^\"']+|/[^\"']+)[\"']\\s*>(.*?)</a>)|" + // 4: HTML Link
    "(file:///[a-zA-Z0-9_./\\-#]+)|" +                                                // 7: Bare file link
    "(conversation://[a-zA-Z0-9_./\\-#]+)|" +                                         // 8: Bare conversation link
    "(`([^`\\n]+)`)|" +                                                               // 9: Inline code `code`
    "(<code>(.*?)</code>)|" +                                                         // 11: HTML code <code>code</code>
    "([$]{1,2}([^$\\n]+)[$]{1,2})|" +                                                 // 13: Inline Math $formula$
    "(\\\\\\((.*?)\\\\\\))|" +                                                        // 15: Inline Math \(formula\)
    "(\\*{3}(.+?)\\*{3})|" +                                                          // 17: Bold-Italic ***text***
    "(___([^_\\n]+)___)|" +                                                           // 19: Bold-Italic ___text___
    "(\\*{2}(.+?)\\*{2})|" +                                                          // 21: Bold **text**
    "(__([^_\\n]+)__)|" +                                                             // 23: Bold __text__
    "(<b>(.*?)</b>)|" +                                                               // 25: HTML bold <b>text</b>
    "(<strong>(.*?)</strong>)|" +                                                     // 27: HTML strong <strong>text</strong>
    "(~~(.+?)~~)|" +                                                                  // 29: Strikethrough ~~text~~
    "(<s>(.*?)</s>)|" +                                                               // 31: HTML strike <s>text</s>
    "(<del>(.*?)</del>)|" +                                                           // 33: HTML del <del>text</del>
    "(<strike>(.*?)</strike>)|" +                                                     // 35: HTML strike <strike>text</strike>
    "(<u>(.*?)</u>)|" +                                                               // 37: HTML underline <u>text</u>
    "(\\*(?!\\s)(.+?)(?<!\\s)\\*)|" +                                                 // 39: Italic *text*
    "(_(?!\\s)([^_\\n]+?)(?<!\\s)_)|" +                                               // 41: Italic _text_
    "(<i>(.*?)</i>)|" +                                                               // 43: HTML italic <i>text</i>
    "(<em>(.*?)</em>)|" +                                                             // 45: HTML em <em>text</em>
    "(<kbd>(.*?)</kbd>)",                                                             // 47: HTML kbd <kbd>text</kbd>
    Pattern.DOTALL or Pattern.CASE_INSENSITIVE
)

object MarkdownTextCache {
    private val cache = android.util.LruCache<Int, FormattedInlineResult>(600)

    fun prewarm(text: String, isDark: Boolean = true) {
        getOrCompute(text, isStrikethrough = false, isDark = isDark, density = null, fileLinkHandler = null)
    }

    fun getOrCompute(
        text: String,
        isStrikethrough: Boolean = false,
        isDark: Boolean = false,
        density: Density? = null,
        fileLinkHandler: FileLinkHandler? = null
    ): FormattedInlineResult {
        // Fast path for plain text without markdown tokens
        if (!text.contains('*') && !text.contains('_') && !text.contains('`') &&
            !text.contains('[') && !text.contains('<') && !text.contains('$') &&
            !text.contains('\\') && !text.contains('~')
        ) {
            val clean = if (text.contains("<br", ignoreCase = true)) text.replace(BR_REGEX, "\n") else text
            val str = if (isStrikethrough) {
                AnnotatedString(clean, spanStyles = listOf(AnnotatedString.Range(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color.Gray), 0, clean.length)))
            } else {
                AnnotatedString(clean)
            }
            return FormattedInlineResult(str, emptyMap())
        }

        val cacheKey = text.hashCode() * 31 + (if (isDark) 1 else 0) * 7 + (if (isStrikethrough) 2 else 0)
        return cache.get(cacheKey) ?: run {
            val res = buildRichAnnotatedString(text, isStrikethrough, isDark, density, fileLinkHandler)
            cache.put(cacheKey, res)
            res
        }
    }
}

@Composable
fun FormattedInlineText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        ),
        color = MaterialTheme.colorScheme.onSurface
    ),
    isStrikethrough: Boolean = false
) {
    val isDark = isAppInDarkTheme()
    val density = LocalDensity.current
    val fileLinkHandler = LocalFileLinkHandler.current

    val result = remember(text, isStrikethrough, isDark, density, fileLinkHandler) {
        MarkdownTextCache.getOrCompute(text, isStrikethrough, isDark, density, fileLinkHandler)
    }

    Text(
        text = result.annotatedString,
        inlineContent = result.inlineContent,
        style = style,
        modifier = modifier
    )
}

private fun appendFormattedContent(
    builder: AnnotatedString.Builder,
    content: String,
    isDark: Boolean,
    density: Density?,
    fileLinkHandler: FileLinkHandler?,
    inlineContentMap: MutableMap<String, InlineTextContent>,
    depth: Int
) {
    if (depth >= 3 || (!content.contains('[') && !content.contains('<') && !content.contains('`') &&
        !content.contains('$') && !content.contains('*') && !content.contains('_') &&
        !content.contains("conversation://", ignoreCase = true) && !content.contains("file://", ignoreCase = true))) {
        builder.append(content)
        return
    }

    val nested = buildRichAnnotatedString(
        text = content,
        globalStrikethrough = false,
        isDark = isDark,
        density = density,
        fileLinkHandler = fileLinkHandler,
        depth = depth + 1
    )
    builder.append(nested.annotatedString)
    inlineContentMap.putAll(nested.inlineContent)
}

private fun buildRichAnnotatedString(
    text: String,
    globalStrikethrough: Boolean = false,
    isDark: Boolean = false,
    density: Density? = null,
    fileLinkHandler: FileLinkHandler? = null,
    depth: Int = 0
): FormattedInlineResult {
    val t0 = System.nanoTime()
    val builder = AnnotatedString.Builder()
    val inlineContentMap = mutableMapOf<String, InlineTextContent>()

    if (globalStrikethrough) {
        builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color.Gray))
    }

    // Pre-process <br> tags to newlines
    val cleanText = if (text.contains("<br", ignoreCase = true)) text.replace(BR_REGEX, "\n") else text

    val matcher = INLINE_MARKDOWN_PATTERN.matcher(cleanText)
    var lastEnd = 0

    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()

        if (start > lastEnd) {
            builder.append(cleanText.substring(lastEnd, start))
        }

        val fullMatch = matcher.group()

        if (fullMatch.startsWith("[") && fullMatch.contains("](")) {
            // Markdown Link [title](url)
            val linkTitle = fullMatch.substringAfter("[").substringBefore("](")
            val linkUrl = fullMatch.substringAfter("](").substringBeforeLast(")")
            val isConversation = linkUrl.startsWith("conversation://", ignoreCase = true)
            val isFile = linkUrl.startsWith("file://", ignoreCase = true) || linkUrl.startsWith("/")

            if (isConversation) {
                val convId = linkUrl.removePrefix("conversation://").substringBefore("#").substringBefore("/").trim()
                val displayLabel = if (linkTitle.isNotBlank() && !linkTitle.startsWith("conversation://", ignoreCase = true)) {
                    linkTitle
                } else {
                    "Chat #${convId.take(8)}"
                }

                builder.pushLink(
                    LinkAnnotation.Clickable(
                        tag = linkUrl,
                        linkInteractionListener = {
                            val handler = fileLinkHandler ?: ActiveFileLinkHandlerHolder.current
                            handler?.onOpenConversation?.invoke(convId)
                        }
                    )
                )
                builder.pushStyle(
                    SpanStyle(
                        color = Color(0xFF7C3AED),
                        fontWeight = FontWeight.SemiBold,
                        background = Color(0xFF7C3AED).copy(alpha = 0.12f)
                    )
                )
                builder.append(" 💬 $displayLabel ")
                builder.pop()
                builder.pop()
            } else if (isFile) {
                val cleanPath = linkUrl.removePrefix("file://").substringBefore("#")
                val fileName = java.io.File(cleanPath).name.ifBlank { cleanPath }
                val displayLabel = if (linkTitle.isBlank() || linkTitle.startsWith("file://", ignoreCase = true) || linkTitle.startsWith("/")) {
                    fileName
                } else {
                    linkTitle
                }

                builder.pushLink(
                    LinkAnnotation.Clickable(
                        tag = linkUrl,
                        linkInteractionListener = {
                            val handler = fileLinkHandler ?: ActiveFileLinkHandlerHolder.current
                            handler?.onOpenFile?.invoke(linkUrl)
                        }
                    )
                )
                builder.pushStyle(
                    SpanStyle(
                        color = ClaudeTerracotta,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        background = ClaudeTerracotta.copy(alpha = 0.12f)
                    )
                )
                builder.append(" $displayLabel ")
                builder.pop()
                builder.pop()
            } else {
                builder.pushLink(LinkAnnotation.Url(url = linkUrl))
                builder.pushStyle(
                    SpanStyle(
                        color = GeminiBlue,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    )
                )
                builder.append(linkTitle)
                builder.pop()
                builder.pop()
            }
        } else if (fullMatch.startsWith("<a", ignoreCase = true)) {
            // HTML Link <a href="url">title</a>
            val linkUrl = matcher.group(5) ?: ""
            val linkTitle = matcher.group(6) ?: ""
            val isConversation = linkUrl.startsWith("conversation://", ignoreCase = true)
            val isFile = linkUrl.startsWith("file://", ignoreCase = true) || linkUrl.startsWith("/")

            if (isConversation) {
                val convId = linkUrl.removePrefix("conversation://").substringBefore("#").substringBefore("/").trim()
                val displayLabel = if (linkTitle.isNotBlank() && !linkTitle.startsWith("conversation://", ignoreCase = true)) {
                    linkTitle
                } else {
                    "Chat #${convId.take(8)}"
                }

                builder.pushLink(
                    LinkAnnotation.Clickable(
                        tag = linkUrl,
                        linkInteractionListener = {
                            val handler = fileLinkHandler ?: ActiveFileLinkHandlerHolder.current
                            handler?.onOpenConversation?.invoke(convId)
                        }
                    )
                )
                builder.pushStyle(
                    SpanStyle(
                        color = Color(0xFF7C3AED),
                        fontWeight = FontWeight.SemiBold,
                        background = Color(0xFF7C3AED).copy(alpha = 0.12f)
                    )
                )
                builder.append(" 💬 $displayLabel ")
                builder.pop()
                builder.pop()
            } else if (isFile) {
                val cleanPath = linkUrl.removePrefix("file://").substringBefore("#")
                val fileName = java.io.File(cleanPath).name.ifBlank { cleanPath }
                val displayLabel = if (linkTitle.isBlank() || linkTitle.startsWith("file://", ignoreCase = true) || linkTitle.startsWith("/")) fileName else linkTitle
                builder.pushLink(
                    LinkAnnotation.Clickable(
                        tag = linkUrl,
                        linkInteractionListener = {
                            val handler = fileLinkHandler ?: ActiveFileLinkHandlerHolder.current
                            handler?.onOpenFile?.invoke(linkUrl)
                        }
                    )
                )
                builder.pushStyle(
                    SpanStyle(
                        color = ClaudeTerracotta,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        background = ClaudeTerracotta.copy(alpha = 0.12f)
                    )
                )
                builder.append(" $displayLabel ")
                builder.pop()
                builder.pop()
            } else {
                builder.pushLink(LinkAnnotation.Url(url = linkUrl))
                builder.pushStyle(
                    SpanStyle(
                        color = GeminiBlue,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    )
                )
                builder.append(linkTitle)
                builder.pop()
                builder.pop()
            }
        } else if (fullMatch.startsWith("conversation://", ignoreCase = true)) {
            // Bare conversation link
            val convId = fullMatch.removePrefix("conversation://").substringBefore("#").substringBefore("/").trim()
            val displayLabel = "Chat #${convId.take(8)}"
            builder.pushLink(
                LinkAnnotation.Clickable(
                    tag = fullMatch,
                    linkInteractionListener = {
                        val handler = fileLinkHandler ?: ActiveFileLinkHandlerHolder.current
                        handler?.onOpenConversation?.invoke(convId)
                    }
                )
            )
            builder.pushStyle(
                SpanStyle(
                    color = Color(0xFF7C3AED),
                    fontWeight = FontWeight.SemiBold,
                    background = Color(0xFF7C3AED).copy(alpha = 0.12f)
                )
            )
            builder.append(" 💬 $displayLabel ")
            builder.pop()
            builder.pop()
        } else if (fullMatch.startsWith("file:///", ignoreCase = true)) {
            // Bare file link
            val cleanPath = fullMatch.removePrefix("file://").substringBefore("#")
            val fileName = java.io.File(cleanPath).name.ifBlank { cleanPath }
            builder.pushLink(
                LinkAnnotation.Clickable(
                    tag = fullMatch,
                    linkInteractionListener = {
                        val handler = fileLinkHandler ?: ActiveFileLinkHandlerHolder.current
                        handler?.onOpenFile?.invoke(fullMatch)
                    }
                )
            )
            builder.pushStyle(
                SpanStyle(
                    color = ClaudeTerracotta,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    background = ClaudeTerracotta.copy(alpha = 0.12f)
                )
            )
            builder.append(" $fileName ")
            builder.pop()
            builder.pop()
        } else if ((fullMatch.startsWith("`") && fullMatch.endsWith("`")) || fullMatch.startsWith("<code", ignoreCase = true)) {
            // Inline code `...` or <code>...</code> with Color Swatch preview support
            val codeContent = if (fullMatch.startsWith("`")) fullMatch.removeSurrounding("`") else fullMatch.replace(Regex("<[^>]+>"), "")
            val parsedColor = parseHexOrRgbColor(codeContent)
            if (parsedColor != null) {
                val swatchId = "color_swatch_${inlineContentMap.size}"
                builder.appendInlineContent(swatchId, alternateText = "● ")
                inlineContentMap[swatchId] = InlineTextContent(
                    placeholder = Placeholder(
                        width = 14.sp,
                        height = 14.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(parsedColor)
                            .border(1.dp, if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.25f), androidx.compose.foundation.shape.CircleShape)
                    )
                }
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = parsedColor.copy(alpha = if (isDark) 0.22f else 0.14f),
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color(0xFFF1F1F4) else Color(0xFF1E1E24),
                        fontSize = 12.5.sp
                    )
                )
                builder.append(" $codeContent ")
                builder.pop()
            } else {
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x24D97706),
                        fontWeight = FontWeight.SemiBold,
                        color = ClaudeTerracotta,
                        fontSize = 12.5.sp
                    )
                )
                builder.append(" $codeContent ")
                builder.pop()
            }
        } else if ((fullMatch.startsWith("$") && fullMatch.endsWith("$") && fullMatch.length > 2) || (fullMatch.startsWith("\\(") && fullMatch.endsWith("\\)"))) {
            // Native JLatexMath Inline Math
            val mathContent = if (fullMatch.startsWith("$")) fullMatch.removePrefix("$").removeSuffix("$").removePrefix("$").removeSuffix("$").trim()
            else fullMatch.removePrefix("\\(").removeSuffix("\\)").trim()

            val mathId = "inline_math_${inlineContentMap.size}"
            val textSizePx = if (density != null) with(density) { 14.5.sp.toPx() } else 38f
            val baseColor = if (isDark) android.graphics.Color.parseColor("#F59E0B") else android.graphics.Color.parseColor("#C2410C")

            val colorizedMath = colorizeLatexEquation(mathContent, isDark)
            val drawable = try {
                JLatexMathDrawable.builder(colorizedMath)
                    .textSize(textSizePx)
                    .color(baseColor)
                    .background(android.graphics.Color.TRANSPARENT)
                    .build()
            } catch (_: Exception) {
                try {
                    JLatexMathDrawable.builder(mathContent)
                        .textSize(textSizePx)
                        .color(baseColor)
                        .background(android.graphics.Color.TRANSPARENT)
                        .build()
                } catch (_: Exception) {
                    null
                }
            }

            if (drawable != null && density != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                val widthSp = with(density) { drawable.intrinsicWidth.toSp() }
                val heightSp = with(density) { drawable.intrinsicHeight.toSp() }

                builder.appendInlineContent(mathId, alternateText = mathContent)
                inlineContentMap[mathId] = InlineTextContent(
                    placeholder = Placeholder(
                        width = widthSp,
                        height = heightSp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawIntoCanvas { canvas ->
                            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                            drawable.draw(canvas.nativeCanvas)
                        }
                    }
                }
            } else {
                val formatted = formatLatexToNativeMath(mathContent)
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        color = ClaudeTerracotta,
                        fontWeight = FontWeight.Medium
                    )
                )
                builder.append(formatted)
                builder.pop()
            }
        } else if (fullMatch.startsWith("***") && fullMatch.endsWith("***") && fullMatch.length >= 6) {
            // Bold Italic ***...***
            val content = fullMatch.removeSurrounding("***")
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("___") && fullMatch.endsWith("___") && fullMatch.length >= 6) {
            // Bold Italic ___...___
            val content = fullMatch.removeSurrounding("___")
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("**") && fullMatch.endsWith("**") && fullMatch.length >= 4) {
            // Bold **...**
            val content = fullMatch.removeSurrounding("**")
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("__") && fullMatch.endsWith("__") && fullMatch.length >= 4) {
            // Bold __...__
            val content = fullMatch.removeSurrounding("__")
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("<b", ignoreCase = true) || fullMatch.startsWith("<strong", ignoreCase = true)) {
            // HTML Bold <b>...</b> or <strong>...</strong>
            val content = fullMatch.replace(Regex("<[^>]+>"), "")
            builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("~~") && fullMatch.endsWith("~~") && fullMatch.length >= 4) {
            // Strikethrough ~~...~~
            val content = fullMatch.removeSurrounding("~~")
            builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color.Gray))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("<u", ignoreCase = true)) {
            // HTML Underline <u>...</u>
            val content = fullMatch.replace(Regex("<[^>]+>"), "")
            builder.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("*") && fullMatch.endsWith("*") && fullMatch.length >= 2) {
            // Italic *...*
            val content = fullMatch.removeSurrounding("*")
            builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("_") && fullMatch.endsWith("_") && fullMatch.length >= 2) {
            // Italic _..._
            val content = fullMatch.removeSurrounding("_")
            builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("<i", ignoreCase = true) || fullMatch.startsWith("<em", ignoreCase = true)) {
            // HTML Italic <i>...</i> or <em>...</em>
            val content = fullMatch.replace(Regex("<[^>]+>"), "")
            builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("<s", ignoreCase = true) || fullMatch.startsWith("<del", ignoreCase = true) || fullMatch.startsWith("<strike", ignoreCase = true)) {
            // HTML Strikethrough <s>, <del>, <strike>
            val content = fullMatch.replace(Regex("<[^>]+>"), "")
            builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color.Gray))
            appendFormattedContent(builder, content, isDark, density, fileLinkHandler, inlineContentMap, depth)
            builder.pop()
        } else if (fullMatch.startsWith("<kbd", ignoreCase = true)) {
            // HTML Keyboard Key <kbd>key</kbd>
            val content = fullMatch.replace(Regex("<[^>]+>"), "").trim()
            builder.pushStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    background = if (isDark) Color(0xFF2A2B36) else Color(0xFFE2E8F0),
                    color = if (isDark) Color(0xFFF1F1F4) else Color(0xFF1E293B)
                )
            )
            builder.append(" $content ")
            builder.pop()
        } else {
            builder.append(fullMatch)
        }

        lastEnd = end
    }

    if (lastEnd < cleanText.length) {
        builder.append(cleanText.substring(lastEnd))
    }

    if (globalStrikethrough) {
        builder.pop()
    }

    return FormattedInlineResult(builder.toAnnotatedString(), inlineContentMap)
}

/**
 * Full Markdown block parser supporting:
 * Math ($$, \[\], ```math), Fenced Code, <details><summary>, GFM Tables, Headers (1-6), Images, Task Checklists, Blockquotes, Lists, Dividers, Paragraphs.
 */
private val THOUGHT_START_REGEX = Regex("<(?:!--\\s*)?thought(?:[:\\s]+(streaming))?(?:[:\\s]+(?:duration=)?[\"']?([0-9]+)[\"']?)?(?:\\s*--)?>", RegexOption.IGNORE_CASE)
private val THOUGHT_END_REGEX = Regex("<(?:!--\\s*)?/thought(?:\\s*--)?>", RegexOption.IGNORE_CASE)
private val ERROR_START_REGEX = Regex("<(?:!--\\s*)?error(?:[:\\s]+([a-zA-Z0-9_-]+))?(?:\\s*--)?>", RegexOption.IGNORE_CASE)
private val ERROR_END_REGEX = Regex("<(?:!--\\s*)?/error(?:\\s*--)?>", RegexOption.IGNORE_CASE)
private val TOOL_TAG_PATTERN = Regex("<\\s*(tool_call|execute_command|web_search|read_url|ask_choices|user_choice|tool_|execute_|web_|read_|ask_|user_)", RegexOption.IGNORE_CASE)
private val TOOL_MARKER_REGEX = Regex("<!--\\s*tool_call:([a-zA-Z0-9_-]+)\\s*-->")
private val UNIFIED_TOOL_REGEX = Regex("<tool_call\\s+name=[\"']?([a-zA-Z0-9_-]+)[\"']?\\s*>([\\s\\S]*?)</tool_call>", RegexOption.IGNORE_CASE)
private val CHOICE_REGEX = Regex("<(ask_choices|user_choice)>([\\s\\S]*?)</(ask_choices|user_choice)>")
private val EXEC_CMD_REGEX = Regex("<execute_command>([\\s\\S]*?)</execute_command>")
private val WEB_SEARCH_REGEX = Regex("<web_search>([\\s\\S]*?)</web_search>")
private val READ_URL_REGEX = Regex("<read_url>([\\s\\S]*?)</read_url>")
private val CHAT_TITLE_REGEX = Regex("<chat_title>[\\s\\S]*?</chat_title>\\s*", RegexOption.IGNORE_CASE)
private val INFLIGHT_CHAT_TITLE_REGEX = Regex("<\\s*chat_title[\\s\\S]*", RegexOption.IGNORE_CASE)

private fun parseErrorPayloadToBlock(raw: String): MarkdownBlock.AgentError {
    try {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val json = Json.parseToJsonElement(trimmed).jsonObject
            val title = json["title"]?.jsonPrimitive?.content ?: "Agent Execution Error"
            val userMessage = json["userMessage"]?.jsonPrimitive?.content ?: ""
            val shortError = json["shortError"]?.jsonPrimitive?.content ?: ""
            val fullError = json["fullError"]?.jsonPrimitive?.content ?: ""
            val errorCode = json["errorCode"]?.jsonPrimitive?.intOrNull
            val errorId = json["errorId"]?.jsonPrimitive?.content ?: ""
            val rawJson = json["rawJson"]?.jsonPrimitive?.content ?: ""
            return MarkdownBlock.AgentError(
                title = title,
                userMessage = userMessage,
                shortError = shortError,
                fullError = fullError,
                errorCode = errorCode,
                errorId = errorId,
                rawJson = rawJson
            )
        }
    } catch (_: Exception) {}

    val clean = raw.removePrefix("⚠️").trim()
    val lines = clean.lines()
    val firstLine = lines.firstOrNull() ?: "Agent Execution Error"
    val rest = if (lines.size > 1) lines.drop(1).joinToString("\n").trim() else ""
    val isStackOrTrace = rest.contains("stack trace", ignoreCase = true) || rest.contains("Wraps:", ignoreCase = true)
    return MarkdownBlock.AgentError(
        title = if (firstLine.length < 50 && !firstLine.contains('\n')) firstLine else "Execution Notice",
        userMessage = if (firstLine.length >= 50) firstLine else (if (rest.isNotBlank() && !isStackOrTrace) rest else firstLine),
        shortError = if (rest.isNotBlank() && !isStackOrTrace) rest else (if (firstLine.length >= 50) firstLine else ""),
        fullError = if (isStackOrTrace) rest else (if (clean.contains("stack trace", ignoreCase = true)) clean else "")
    )
}

fun parseMarkdownBlocks(
    rawText: String,
    toolCalls: List<com.example.gemini.domain.model.ToolCall> = emptyList()
): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()

    // Filter out <chat_title> so it never appears in UI
    val cleanedText = if (rawText.contains('<')) {
        rawText.replace(CHAT_TITLE_REGEX, "").replace(INFLIGHT_CHAT_TITLE_REGEX, "")
    } else rawText

    val lines = cleanedText.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fast-path for tool tags, errors & thoughts
        if (line.contains('<')) {
            // -0.5. Structured Agent Error Block <!-- error -->...<!-- /error -->
            val errorStartMatch = ERROR_START_REGEX.find(line)
            if (errorStartMatch != null) {
                val errorLines = mutableListOf<String>()
                if (line.contains("</error>", ignoreCase = true) || line.contains("<!-- /error -->", ignoreCase = true)) {
                    val raw = line.replace(ERROR_START_REGEX, "").replace(ERROR_END_REGEX, "").trim()
                    if (raw.isNotBlank()) {
                        result.add(parseErrorPayloadToBlock(raw))
                    }
                    i++
                    continue
                }

                val firstLine = line.replace(ERROR_START_REGEX, "").trim()
                if (firstLine.isNotBlank()) errorLines.add(firstLine)

                i++
                while (i < lines.size) {
                    val curr = lines[i]
                    if (curr.contains("</error>", ignoreCase = true) || curr.contains("<!-- /error -->", ignoreCase = true)) {
                        val endContent = curr.replace(ERROR_END_REGEX, "").trim()
                        if (endContent.isNotBlank()) errorLines.add(endContent)
                        i++
                        break
                    }
                    errorLines.add(curr)
                    i++
                }

                val finalErrorPayload = errorLines.joinToString("\n").trim()
                if (finalErrorPayload.isNotBlank()) {
                    result.add(parseErrorPayloadToBlock(finalErrorPayload))
                }
                continue
            }

            // -1. Sequential Agent Thought Block <!-- thought -->...<!-- /thought --> or <thought>
            val thoughtStartMatch = THOUGHT_START_REGEX.find(line)
            if (thoughtStartMatch != null) {
                val isExplicitStreaming = line.contains(":streaming", ignoreCase = true) || line.contains(" streaming", ignoreCase = true)
                val durationMs = Regex("""(?:duration=|:)([0-9]+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    ?: thoughtStartMatch.groupValues.getOrNull(2)?.toLongOrNull()
                val thoughtLines = mutableListOf<String>()

                if (line.contains("</thought>", ignoreCase = true) || line.contains("<!-- /thought -->", ignoreCase = true)) {
                    val raw = line.replace(THOUGHT_START_REGEX, "").replace(THOUGHT_END_REGEX, "").trim()
                    if (raw.isNotBlank()) {
                        result.add(MarkdownBlock.AgentThought(raw, durationMs, isStreaming = isExplicitStreaming))
                    }
                    i++
                    continue
                }

                val firstLine = line.replace(THOUGHT_START_REGEX, "").trim()
                if (firstLine.isNotBlank()) thoughtLines.add(firstLine)

                i++
                var closed = false
                while (i < lines.size) {
                    val curr = lines[i]
                    if (curr.contains("</thought>", ignoreCase = true) || curr.contains("<!-- /thought -->", ignoreCase = true)) {
                        val endContent = curr.replace(THOUGHT_END_REGEX, "").trim()
                        if (endContent.isNotBlank()) thoughtLines.add(endContent)
                        closed = true
                        i++
                        break
                    }
                    thoughtLines.add(curr)
                    i++
                }

                val finalThought = thoughtLines.joinToString("\n").trim()
                val isStreamingThought = isExplicitStreaming || !closed
                if (finalThought.isNotBlank()) {
                    result.add(MarkdownBlock.AgentThought(finalThought, durationMs, isStreaming = isStreamingThought))
                } else if (!closed || isExplicitStreaming) {
                    result.add(MarkdownBlock.AgentThought("Thinking...", durationMs, isStreaming = true))
                }
                continue
            }

            // 0. Inline Agent Tool Call Marker <!-- tool_call:ID --> or <tool_call>
            val toolMarkerMatch = TOOL_MARKER_REGEX.find(line)
            if (toolMarkerMatch != null) {
                val toolId = toolMarkerMatch.groupValues[1]
                val handledIds = result.filterIsInstance<MarkdownBlock.AgentTool>().map { it.toolCall.id }.toSet()
                val targetStep = toolId.substringAfterLast("_")
                val matchedTool = toolCalls.find { it.id == toolId }
                    ?: toolCalls.find { tc ->
                        tc.id !in handledIds && tc.id.substringAfterLast("_") == targetStep
                    }
                    ?: toolCalls.find { tc ->
                        tc.id !in handledIds && (
                            com.example.gemini.data.remote.AgyHubClient.normalizeToolName(tc.name) == com.example.gemini.data.remote.AgyHubClient.normalizeToolName(toolId.substringBefore("_").removePrefix("tool_"))
                        )
                    }
                if (matchedTool != null) {
                    result.add(MarkdownBlock.AgentTool(matchedTool))
                }
                i++
                continue
            }

            val unifiedToolMatch = UNIFIED_TOOL_REGEX.find(line)
            if (unifiedToolMatch != null) {
                val name = unifiedToolMatch.groupValues[1].trim().lowercase()
                val payload = unifiedToolMatch.groupValues[2].trim()
                if (name == "interactive_ui" || name == "render_ui" || name == "create_interactive_app" || name == "interactive_app") {
                    result.add(MarkdownBlock.InteractiveUi(htmlCode = payload))
                    i++
                    continue
                }
                val matchedTool = toolCalls.find { it.command == payload }
                    ?: com.example.gemini.domain.model.ToolCall(name = name, command = payload, status = if (name == "ask_choices") "AWAITING_CHOICE" else "RUNNING")
                result.add(MarkdownBlock.AgentTool(matchedTool))
                i++
                continue
            }

            val choiceMatch = CHOICE_REGEX.find(line)
            if (choiceMatch != null) {
                val json = choiceMatch.groupValues[2].trim()
                val matchedTool = toolCalls.find { it.command == json && (it.name == "ask_choices" || it.name == "user_choice") }
                    ?: com.example.gemini.domain.model.ToolCall(name = "ask_choices", command = json, status = "AWAITING_CHOICE")
                result.add(MarkdownBlock.AgentTool(matchedTool))
                i++
                continue
            }

            val execCmdMatch = EXEC_CMD_REGEX.find(line)
            if (execCmdMatch != null) {
                val cmd = execCmdMatch.groupValues[1].trim()
                val matchedTool = toolCalls.find { it.command == cmd } ?: com.example.gemini.domain.model.ToolCall(command = cmd, status = "RUNNING")
                result.add(MarkdownBlock.AgentTool(matchedTool))
                i++
                continue
            }

            val webSearchMatch = WEB_SEARCH_REGEX.find(line)
            if (webSearchMatch != null) {
                val query = webSearchMatch.groupValues[1].trim()
                val matchedTool = toolCalls.find { it.command == query && it.name == "web_search" } ?: com.example.gemini.domain.model.ToolCall(name = "web_search", command = query, status = "RUNNING")
                result.add(MarkdownBlock.AgentTool(matchedTool))
                i++
                continue
            }

            val readUrlMatch = READ_URL_REGEX.find(line)
            if (readUrlMatch != null) {
                val url = readUrlMatch.groupValues[1].trim()
                val matchedTool = toolCalls.find { it.command == url && it.name == "read_url" } ?: com.example.gemini.domain.model.ToolCall(name = "read_url", command = url, status = "RUNNING")
                result.add(MarkdownBlock.AgentTool(matchedTool))
                i++
                continue
            }
        }

        // 0.5 Warning notice / fallback error notice starting with ⚠️
        if (line.trimStart().startsWith("⚠️")) {
            val errorLines = mutableListOf<String>()
            errorLines.add(line.trimStart())
            i++
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith('#') && !lines[i].startsWith('<') && !lines[i].startsWith("```") && !lines[i].startsWith("$$")) {
                errorLines.add(lines[i])
                i++
            }
            val raw = errorLines.joinToString("\n").trim()
            result.add(parseErrorPayloadToBlock(raw))
            continue
        }

        // 1. Math block starting with $$
        if (line.trimStart().startsWith("$$")) {
            val firstLineContent = line.trimStart().removePrefix("$$")
            if (firstLineContent.endsWith("$$") && firstLineContent.length >= 2) {
                // Single line $$ ... $$
                val math = firstLineContent.removeSuffix("$$").trim()
                result.add(MarkdownBlock.Math(latex = math, isDisplay = true))
                i++
                continue
            } else {
                // Multi-line $$ ... $$
                val mathLines = mutableListOf<String>()
                if (firstLineContent.isNotBlank()) mathLines.add(firstLineContent)
                i++
                while (i < lines.size && !lines[i].contains("$$")) {
                    mathLines.add(lines[i])
                    i++
                }
                if (i < lines.size) {
                    val lastLineContent = lines[i].substringBefore("$$").trim()
                    if (lastLineContent.isNotBlank()) mathLines.add(lastLineContent)
                }
                result.add(MarkdownBlock.Math(latex = mathLines.joinToString("\n").trim(), isDisplay = true))
                i++
                continue
            }
        }

        // 2. Math block starting with \[
        if (line.trimStart().startsWith("\\[")) {
            val firstLineContent = line.trimStart().removePrefix("\\[")
            if (firstLineContent.contains("\\]")) {
                val math = firstLineContent.substringBefore("\\]").trim()
                result.add(MarkdownBlock.Math(latex = math, isDisplay = true))
                i++
                continue
            } else {
                val mathLines = mutableListOf<String>()
                if (firstLineContent.isNotBlank()) mathLines.add(firstLineContent)
                i++
                while (i < lines.size && !lines[i].contains("\\]")) {
                    mathLines.add(lines[i])
                    i++
                }
                if (i < lines.size) {
                    val lastLineContent = lines[i].substringBefore("\\]").trim()
                    if (lastLineContent.isNotBlank()) mathLines.add(lastLineContent)
                }
                result.add(MarkdownBlock.Math(latex = mathLines.joinToString("\n").trim(), isDisplay = true))
                i++
                continue
            }
        }

        // 3. Fenced Code block starts: ``` (checks if language is math/latex/katex)
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (lang.equals("math", ignoreCase = true) || lang.equals("latex", ignoreCase = true) || lang.equals("katex", ignoreCase = true)) {
                result.add(MarkdownBlock.Math(latex = codeLines.joinToString("\n"), isDisplay = true))
            } else if (lang.equals("mermaid", ignoreCase = true)) {
                result.add(MarkdownBlock.Mermaid(code = codeLines.joinToString("\n").trim()))
            } else if (lang.equals("interactive_ui", ignoreCase = true) || lang.equals("interactive_app", ignoreCase = true) || lang.equals("html_app", ignoreCase = true) || lang.equals("canvas_app", ignoreCase = true) || lang.equals("widget", ignoreCase = true) || lang.equals("ui", ignoreCase = true) || lang.equals("mini_app", ignoreCase = true)) {
                result.add(MarkdownBlock.InteractiveUi(htmlCode = codeLines.joinToString("\n").trim()))
            } else {
                result.add(MarkdownBlock.Code(language = lang, code = codeLines.joinToString("\n")))
            }
            i++
            continue
        }

        // 4. <details> and <summary> Collapsible Sections
        if (line.trimStart().startsWith("<details", ignoreCase = true)) {
            val isOpen = line.contains("open", ignoreCase = true)
            var summary = "Details"
            val bodyLines = mutableListOf<String>()
            i++

            while (i < lines.size && !lines[i].trimStart().startsWith("</details>", ignoreCase = true)) {
                val currLine = lines[i]
                if (currLine.trimStart().startsWith("<summary>", ignoreCase = true)) {
                    summary = currLine.replace(Regex("</?summary>", RegexOption.IGNORE_CASE), "").trim()
                } else {
                    bodyLines.add(currLine)
                }
                i++
            }
            result.add(MarkdownBlock.Details(summary = summary, body = bodyLines.joinToString("\n").trim(), defaultOpen = isOpen))
            i++
            continue
        }

        // 5a. Linked Markdown Images: [![alt](imgUrl)](linkUrl)
        val linkedImageMatch = Regex("""^\s*\[!\[(.*?)\]\((.*?)\)\]\((.*?)\)\s*$""", RegexOption.IGNORE_CASE).find(line)
        if (linkedImageMatch != null) {
            val alt = linkedImageMatch.groupValues[1]
            val imgUrl = linkedImageMatch.groupValues[2]
            val linkUrl = linkedImageMatch.groupValues[3]
            val ytId = extractYouTubeVideoId(linkUrl) ?: extractYouTubeVideoId(imgUrl)
            if (ytId != null) {
                result.add(
                    MarkdownBlock.YouTubeVideo(
                        videoId = ytId,
                        originalUrl = if (linkUrl.isNotBlank()) linkUrl else "https://www.youtube.com/watch?v=$ytId",
                        title = alt.ifBlank { "YouTube Video" },
                        customThumbnailUrl = imgUrl.takeIf { it.isNotBlank() }
                    )
                )
            } else {
                result.add(MarkdownBlock.Image(alt = alt, url = imgUrl))
            }
            i++
            continue
        }

        // 5b. Standalone Markdown Images: ![alt](url)
        val imageMatch = Regex("""^\s*!\[(.*?)\]\(((?:https?://|file://|content://|data:image/)[^\s)]+)\)\s*$""", RegexOption.IGNORE_CASE).find(line)
        if (imageMatch != null) {
            val alt = imageMatch.groupValues[1]
            val url = imageMatch.groupValues[2]
            val ytId = extractYouTubeVideoId(url)
            if (ytId != null) {
                result.add(
                    MarkdownBlock.YouTubeVideo(
                        videoId = ytId,
                        originalUrl = if (url.contains("youtube.com/watch") || url.contains("youtu.be")) url else "https://www.youtube.com/watch?v=$ytId",
                        title = alt.ifBlank { "YouTube Video" },
                        customThumbnailUrl = if (url.contains("img.youtube.com")) url else null
                    )
                )
            } else {
                result.add(MarkdownBlock.Image(alt = alt, url = url))
            }
            i++
            continue
        }

        // 5b. Standalone file or image links: [alt](path.jpg/png) or ![alt](path.jpg)
        val linkImgMatch = Regex("^\\s*!?\\[(.*?)\\]\\(([^\\s)]+\\.(?:png|jpe?g|webp|gif|svg)(?:\\?[^\\s)]*)?)\\)\\s*$", RegexOption.IGNORE_CASE).find(line)
        if (linkImgMatch != null) {
            val alt = linkImgMatch.groupValues[1]
            val url = linkImgMatch.groupValues[2]
            result.add(MarkdownBlock.Image(alt = alt, url = url))
            i++
            continue
        }

        // 5c. Bare image URLs on a single line
        val bareImageMatch = Regex("^\\s*((?:https?://|file://)\\S+\\.(?:png|jpe?g|webp|gif|svg)(?:\\?\\S*)?)\\s*$", RegexOption.IGNORE_CASE).find(line)
        if (bareImageMatch != null) {
            val url = bareImageMatch.groupValues[1]
            result.add(MarkdownBlock.Image(alt = "", url = url))
            i++
            continue
        }

        // 6. GFM Tables: | header | header |
        if (line.contains("|") && i + 1 < lines.size) {
            val tableResult = parseTable(lines, i)
            if (tableResult != null) {
                result.add(tableResult.first)
                i = tableResult.second
                continue
            }
        }

        // 7. Headers: # to ######
        val headerMatch = Regex("^(#{1,6})\\s+(.*)").find(line.trimStart())
        if (headerMatch != null) {
            val level = headerMatch.groupValues[1].length
            val title = headerMatch.groupValues[2].trim()
            result.add(MarkdownBlock.Header(level, title))
        }
        // 8. Task List: - [ ] or - [x]
        else if (line.trimStart().matches(Regex("^[-*]\\s+\\[([ xX])\\]\\s+.*"))) {
            val taskMatch = Regex("^[-*]\\s+\\[([ xX])\\]\\s+(.*)").find(line.trimStart())
            if (taskMatch != null) {
                val isChecked = taskMatch.groupValues[1].trim().lowercase() == "x"
                val taskText = taskMatch.groupValues[2].trim()
                result.add(MarkdownBlock.Task(isChecked, taskText))
            } else {
                result.add(MarkdownBlock.Paragraph(line))
            }
        }
        // 9. Blockquotes & Callout Alerts: >
        else if (line.trimStart().startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            val rawFirst = line.trimStart().removePrefix(">").trim()
            quoteLines.add(rawFirst)
            i++
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quoteLines.add(lines[i].trimStart().removePrefix(">").trim())
                i++
            }

            // Check if first line starts with [!NOTE], [!TIP], [!IMPORTANT], [!WARNING], [!CAUTION], [!INFO]
            val firstLine = quoteLines.firstOrNull() ?: ""
            val alertMatch = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION|INFO)\\](.*)", RegexOption.IGNORE_CASE).find(firstLine)
            if (alertMatch != null) {
                val tag = alertMatch.groupValues[1].uppercase()
                val customTitleOrInline = alertMatch.groupValues[2].trim()
                val alertType = when (tag) {
                    "TIP" -> AlertType.TIP
                    "IMPORTANT" -> AlertType.IMPORTANT
                    "WARNING" -> AlertType.WARNING
                    "CAUTION" -> AlertType.CAUTION
                    else -> AlertType.NOTE
                }
                val bodyLines = mutableListOf<String>()
                if (customTitleOrInline.isNotBlank()) {
                    bodyLines.add(customTitleOrInline)
                }
                if (quoteLines.size > 1) {
                    bodyLines.addAll(quoteLines.drop(1))
                }
                val alertBody = bodyLines.joinToString("\n").trim()
                result.add(MarkdownBlock.Alert(type = alertType, text = alertBody))
            } else {
                result.add(MarkdownBlock.Blockquote(quoteLines.joinToString("\n").trim()))
            }
            continue
        }
        // 9b. Standalone Alert Callout: [!NOTE], [!TIP], etc. without >
        else if (line.trimStart().matches(Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION|INFO)\\](\\s+.*)?", RegexOption.IGNORE_CASE))) {
            val alertMatch = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION|INFO)\\](.*)", RegexOption.IGNORE_CASE).find(line.trimStart())
            if (alertMatch != null) {
                val tag = alertMatch.groupValues[1].uppercase()
                val customTitleOrInline = alertMatch.groupValues[2].trim()
                val alertType = when (tag) {
                    "TIP" -> AlertType.TIP
                    "IMPORTANT" -> AlertType.IMPORTANT
                    "WARNING" -> AlertType.WARNING
                    "CAUTION" -> AlertType.CAUTION
                    else -> AlertType.NOTE
                }
                val bodyLines = mutableListOf<String>()
                if (customTitleOrInline.isNotBlank()) {
                    bodyLines.add(customTitleOrInline)
                }
                i++
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#") && !lines[i].startsWith("```") && !lines[i].startsWith(">") && !lines[i].startsWith("<") && !lines[i].matches(Regex("^[-*]\\s+.*")) && !lines[i].matches(Regex("^\\d+\\.\\s+.*"))) {
                    bodyLines.add(lines[i].trim())
                    i++
                }
                result.add(MarkdownBlock.Alert(type = alertType, text = bodyLines.joinToString("\n").trim()))
                continue
            }
        }
        // 10. Horizontal Rules: ---, ***, ___
        else if (line.trim() == "---" || line.trim() == "***" || line.trim() == "___") {
            result.add(MarkdownBlock.HorizontalRule)
        }
        // 11. Numbered Lists: 1. , 2. 
        else if (line.trimStart().matches(Regex("^\\d+\\.\\s+.*"))) {
            val match = Regex("^(\\d+\\.)\\s+(.*)").find(line.trimStart())
            if (match != null) {
                val num = match.groupValues[1]
                val content = match.groupValues[2]
                result.add(MarkdownBlock.Numbered(num, content))
            } else {
                result.add(MarkdownBlock.Paragraph(line))
            }
        }
        // 12. Bullet Lists (with nested indentation support)
        else if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") || line.trimStart().startsWith("+ ")) {
            val leadingSpaces = line.takeWhile { it == ' ' || it == '\t' }.length
            val indentLevel = (leadingSpaces / 2).coerceIn(0, 3)
            val bulletContent = line.trimStart().substring(2).trim()
            result.add(MarkdownBlock.Bullet(indent = indentLevel, text = bulletContent))
        }
        // 13. Regular Paragraphs
        else if (line.isNotBlank()) {
            result.add(MarkdownBlock.Paragraph(line))
        }

        i++
    }

    // Append any tool calls that were not explicitly embedded in the text
    val handledTools = result.filterIsInstance<MarkdownBlock.AgentTool>().map { it.toolCall }
    val handledToolIds = handledTools.map { it.id }.toSet()
    toolCalls.filter { it.id !in handledToolIds }.forEach { orphanTool ->
        val orphanNorm = com.example.gemini.data.remote.AgyHubClient.normalizeToolName(orphanTool.name)
        val alreadyCovered = handledTools.any {
            com.example.gemini.data.remote.AgyHubClient.normalizeToolName(it.name) == orphanNorm &&
            (it.command == orphanTool.command || it.command.isBlank() || orphanTool.command.isBlank())
        }
        if (!alreadyCovered) {
            result.add(MarkdownBlock.AgentTool(orphanTool))
        }
    }

    return result
}

/**
 * Splits a markdown table row by '|', handling escaped pipes '\|' and trimming whitespace.
 */
private fun splitTableRow(line: String): List<String> {
    var trimmed = line.trim()
    if (trimmed.startsWith("|")) trimmed = trimmed.substring(1)
    if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.substring(0, trimmed.length - 1)

    val placeholder = "\u0000"
    val safeLine = trimmed.replace("\\|", placeholder)
    return safeLine.split("|").map { cell ->
        cell.replace(placeholder, "|").trim()
    }
}

/**
 * Parses markdown table header, separator, and data rows.
 */
private fun parseTable(lines: List<String>, startIndex: Int): Pair<MarkdownBlock.Table, Int>? {
    if (startIndex + 1 >= lines.size) return null
    val headerLine = lines[startIndex].trim()
    val sepLine = lines[startIndex + 1].trim()

    if (!headerLine.contains("|") || !sepLine.contains("|")) return null

    val sepCells = splitTableRow(sepLine)
    if (sepCells.isEmpty() || !sepCells.all { it.replace(" ", "").matches(Regex("^:?-+:?$")) }) return null

    val rawHeaders = splitTableRow(headerLine)
    val alignments = sepCells.map { cell ->
        val clean = cell.replace(" ", "")
        val left = clean.startsWith(":")
        val right = clean.endsWith(":")
        if (left && right) TableAlignment.CENTER
        else if (right) TableAlignment.RIGHT
        else TableAlignment.LEFT
    }

    val numCols = maxOf(rawHeaders.size, sepCells.size)
    val headers = if (rawHeaders.isNotEmpty()) {
        if (rawHeaders.size < numCols) rawHeaders + List(numCols - rawHeaders.size) { "" }
        else rawHeaders.take(numCols)
    } else {
        (1..numCols).map { "Column $it" }
    }

    val normalizedAlignments = if (alignments.size < numCols) {
        alignments + List(numCols - alignments.size) { TableAlignment.LEFT }
    } else {
        alignments.take(numCols)
    }

    val rows = mutableListOf<List<String>>()
    var currIndex = startIndex + 2
    while (currIndex < lines.size) {
        val rowLine = lines[currIndex].trim()
        if (rowLine.isBlank() || !rowLine.contains("|")) break
        val rawCells = splitTableRow(rowLine)
        if (rawCells.isNotEmpty()) {
            val normalizedCells = if (rawCells.size < numCols) {
                rawCells + List(numCols - rawCells.size) { "" }
            } else {
                rawCells.take(numCols)
            }
            rows.add(normalizedCells)
            currIndex++
        } else {
            break
        }
    }

    return Pair(MarkdownBlock.Table(headers, rows, normalizedAlignments), currIndex)
}
