package com.example.gemini.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.*
import kotlinx.coroutines.delay
import java.util.regex.Pattern

// Syntax theme colors (One Dark / VS Code inspired)
private val SynKeyword = Color(0xFFC678DD)      // Magenta / Purple
private val SynType = Color(0xFFE5C07B)         // Gold / Yellow
private val SynString = Color(0xFF98C379)       // Soft Green
private val SynNumber = Color(0xFFD19A66)       // Orange / Peach
private val SynComment = Color(0xFF7F848E)      // Muted Slate Gray
private val SynAnnotation = Color(0xFFE5C07B)   // Warm Gold
private val SynFunction = Color(0xFF61AFEF)     // Sky Blue
private val SynPunctuation = Color(0xFFABB2BF)  // Light Gray

@Composable
fun CodeBlock(
    code: String,
    language: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2500)
            isCopied = false
        }
    }

    val highlightedText = remember(code, language) {
        highlightSyntax(code, language.lowercase().trim())
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(CodeBlockBgDark)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CodeBlockBgDark.copy(alpha = 0.9f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.ifEmpty { "code" }.uppercase(),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = ClaudeTerracotta,
                letterSpacing = 0.5.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Code", code)
                    clipboard.setPrimaryClip(clip)
                    isCopied = true
                    Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(28.dp)
            ) {
                if (isCopied) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Copied",
                        tint = QuotaGreen,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        tint = TextPrimaryDark.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        HorizontalDivider(thickness = 0.7.dp, color = TextPrimaryDark.copy(alpha = 0.08f))

        // Code Content with syntax highlighting
        Text(
            text = highlightedText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

/**
 * Fast regex-based lexical tokenizer for syntax highlighting.
 */
private fun highlightSyntax(code: String, lang: String): AnnotatedString {
    val builder = AnnotatedString.Builder(code)

    // Helper data class for spans
    data class Span(val start: Int, val end: Int, val style: SpanStyle)
    val spans = mutableListOf<Span>()

    fun addMatches(pattern: String, style: SpanStyle) {
        try {
            val matcher = Pattern.compile(pattern, Pattern.MULTILINE).matcher(code)
            while (matcher.find()) {
                spans.add(Span(matcher.start(), matcher.end(), style))
            }
        } catch (_: Exception) {}
    }

    // 1. Comments
    val commentPattern = when (lang) {
        "python", "py", "bash", "sh", "shell", "yaml", "yml", "dockerfile" -> "#.*"
        "sql" -> "(--.*|/\\*[\\s\\S]*?\\*/)"
        else -> "(//.*|/\\*[\\s\\S]*?\\*/|#.*)"
    }
    addMatches(commentPattern, SpanStyle(color = SynComment, fontStyle = FontStyle.Italic))

    // 2. Strings ("...", '...', `...`)
    val stringPattern = "(\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'|`(\\\\.|[^`\\\\])*`)"
    addMatches(stringPattern, SpanStyle(color = SynString))

    // 3. Numbers
    val numberPattern = "\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fFL]?)\\b"
    addMatches(numberPattern, SpanStyle(color = SynNumber))

    // 4. Annotations / Decorators (@Composable, @Override, @app.route)
    val annotationPattern = "@[A-Za-z0-9_.]+"
    addMatches(annotationPattern, SpanStyle(color = SynAnnotation, fontWeight = FontWeight.SemiBold))

    // 5. Keywords
    val keywords = when (lang) {
        "kotlin", "kt" -> listOf(
            "package", "import", "fun", "val", "var", "class", "interface", "object", "enum",
            "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
            "throw", "override", "private", "public", "protected", "internal", "abstract",
            "data", "sealed", "open", "const", "suspend", "inline", "is", "as", "in", "by",
            "companion", "lateinit", "typealias", "this", "super", "null", "true", "false"
        )
        "python", "py" -> listOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "try", "except",
            "finally", "raise", "import", "from", "as", "with", "lambda", "async", "await",
            "yield", "pass", "break", "continue", "global", "nonlocal", "in", "is", "not",
            "and", "or", "None", "True", "False", "self", "cls"
        )
        "javascript", "js", "typescript", "ts", "jsx", "tsx" -> listOf(
            "function", "const", "let", "var", "return", "if", "else", "switch", "case",
            "default", "for", "while", "do", "try", "catch", "finally", "throw", "import",
            "export", "from", "as", "class", "extends", "interface", "type", "async",
            "await", "yield", "new", "this", "super", "null", "undefined", "true", "false",
            "typeof", "instanceof", "in", "of", "void"
        )
        "sql" -> listOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "UPDATE", "DELETE", "JOIN", "LEFT",
            "RIGHT", "INNER", "OUTER", "FULL", "ON", "GROUP", "BY", "ORDER", "HAVING",
            "LIMIT", "OFFSET", "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "VIEW", "AS",
            "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "UNION", "ALL",
            "DISTINCT", "COUNT", "SUM", "AVG", "MIN", "MAX", "CASE", "WHEN", "THEN", "ELSE", "END"
        )
        else -> listOf(
            "fun", "val", "var", "class", "interface", "def", "function", "const", "let",
            "return", "if", "else", "for", "while", "try", "catch", "finally", "throw",
            "import", "package", "public", "private", "protected", "override", "async",
            "await", "new", "this", "super", "null", "true", "false", "SELECT", "FROM", "WHERE"
        )
    }

    val kwRegex = "\\b(${keywords.joinToString("|")})\\b"
    addMatches(kwRegex, SpanStyle(color = SynKeyword, fontWeight = FontWeight.Bold))

    // 6. Types / Classes
    val types = listOf(
        "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short",
        "List", "Map", "Set", "Array", "Any", "Unit", "Nothing", "Throwable", "Exception",
        "Result", "StateFlow", "Flow", "MutableStateFlow", "Promise", "Observable",
        "void", "int", "float", "double", "bool", "char", "number", "string", "boolean",
        "any", "unknown", "never", "dict", "tuple", "str"
    )
    val typeRegex = "\\b(${types.joinToString("|")})\\b"
    addMatches(typeRegex, SpanStyle(color = SynType, fontWeight = FontWeight.SemiBold))

    // 7. Function invocations: foo(...)
    val fnRegex = "\\b([a-zA-Z_][a-zA-Z0-9_]*)(?=\\s*\\()"
    try {
        val fnMatcher = Pattern.compile(fnRegex).matcher(code)
        while (fnMatcher.find()) {
            val name = fnMatcher.group(1) ?: ""
            if (!keywords.contains(name)) {
                spans.add(Span(fnMatcher.start(1), fnMatcher.end(1), SpanStyle(color = SynFunction)))
            }
        }
    } catch (_: Exception) {}

    // Sort spans by priority (Comments & Strings override others)
    // Apply default text color
    builder.addStyle(SpanStyle(color = TextPrimaryDark), 0, code.length)

    // Apply all non-overlapping / prioritized spans
    spans.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end }).forEach { span ->
        val s = span.start.coerceIn(0, code.length)
        val e = span.end.coerceIn(0, code.length)
        if (s < e) {
            builder.addStyle(span.style, s, e)
        }
    }

    return builder.toAnnotatedString()
}

