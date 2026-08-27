package com.example.gemini.ui.ide

import android.util.Log
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

data class CodeToken(val column: Int, val colorId: Int)

class UniversalCodeLanguage(val fileExt: String) : EmptyLanguage() {
    private val analyzeManager = UniversalCodeAnalyzer(fileExt)

    override fun getAnalyzeManager(): AnalyzeManager {
        return analyzeManager
    }
}

class UniversalCodeAnalyzer(private val fileExt: String) : AsyncIncrementalAnalyzeManager<Int, CodeToken>() {

    companion object {
        private const val TAG = "CodeEditorDebug"

        private val HTML_EXTS = setOf("html", "htm", "xml", "svg", "vue", "jsx", "tsx", "php")
        private val HASH_COMMENT_EXTS = setOf("py", "sh", "bash", "php", "yaml", "yml", "env", "conf", "properties", "dockerfile")

        private val KEYWORDS = setOf(
            // PHP
            "php", "echo", "print", "require", "require_once", "include", "include_once",
            "namespace", "use", "function", "class", "trait", "interface", "extends", "implements",
            "public", "private", "protected", "static", "final", "abstract", "global", "var",
            "new", "clone", "return", "if", "else", "elseif", "while", "do", "for", "foreach",
            "as", "break", "continue", "switch", "case", "default", "try", "catch", "finally",
            "throw", "isset", "empty", "unset", "die", "exit", "null", "true", "false", "array",
            // JS / TS / JSX / TSX
            "const", "let", "var", "import", "export", "from", "default", "async", "await",
            "yield", "typeof", "instanceof", "undefined", "NaN", "null", "true", "false",
            "this", "super", "constructor", "get", "set", "of", "in", "type", "enum", "declare",
            "interface", "implements", "extends", "class", "function", "return", "new",
            // Python
            "def", "lambda", "elif", "except", "raise", "with", "pass", "assert", "nonlocal",
            "True", "False", "None", "self", "cls", "import", "from", "as", "return",
            // Java / Kotlin / Go / Rust / C / C++ / SQL
            "package", "val", "fun", "object", "data", "sealed", "companion", "override",
            "func", "struct", "chan", "go", "defer", "select", "range", "nil", "fn", "mut",
            "impl", "pub", "crate", "unsafe", "match", "loop", "where", "auto", "template",
            "typename", "constexpr", "inline", "virtual", "explicit", "friend", "operator",
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "TABLE", "CREATE"
        )
    }

    override fun getInitialState(): Int = 0

    override fun stateEquals(p0: Int, p1: Int): Boolean = p0 == p1

    override fun tokenizeLine(
        line: CharSequence,
        state: Int,
        lineIndex: Int
    ): IncrementalAnalyzeManager.LineTokenizeResult<Int, CodeToken> {
        try {
            val len = line.length
            if (len == 0) {
                return IncrementalAnalyzeManager.LineTokenizeResult(0, emptyList())
            }

            val tokens = ArrayList<CodeToken>()
            var i = 0
            var currentState = state

            var lastColor = -1
            fun addToken(col: Int, colorId: Int) {
                if (colorId != lastColor) {
                    lastColor = colorId
                    tokens.add(CodeToken(col, colorId))
                }
            }

            val isHtmlLike = fileExt.lowercase() in HTML_EXTS
            val isHashCommentLang = fileExt.lowercase() in HASH_COMMENT_EXTS

            while (i < len) {
                // Check multi-line comment state (/* ... */)
                if (currentState == 1) {
                    addToken(i, EditorColorScheme.COMMENT)
                    val endComment = line.indexOf("*/", i)
                    if (endComment != -1) {
                        currentState = 0
                        i = endComment + 2
                        continue
                    } else {
                        i = len
                        break
                    }
                }

                // Check multi-line HTML comment state (<!-- ... -->)
                if (currentState == 2) {
                    addToken(i, EditorColorScheme.COMMENT)
                    val endHtmlComment = line.indexOf("-->", i)
                    if (endHtmlComment != -1) {
                        currentState = 0
                        i = endHtmlComment + 3
                        continue
                    } else {
                        i = len
                        break
                    }
                }

                val ch = line[i]

                // 1. Single line or multi-line comment (// or /*)
                if (ch == '/' && i + 1 < len) {
                    val next = line[i + 1]
                    if (next == '/') {
                        addToken(i, EditorColorScheme.COMMENT)
                        break
                    } else if (next == '*') {
                        addToken(i, EditorColorScheme.COMMENT)
                        currentState = 1
                        val endComment = line.indexOf("*/", i + 2)
                        if (endComment != -1) {
                            currentState = 0
                            i = endComment + 2
                            continue
                        } else {
                            i = len
                            break
                        }
                    }
                }

                // 2. Hash comments (#) for Python, Bash, PHP, YAML, etc.
                if (ch == '#' && isHashCommentLang) {
                    addToken(i, EditorColorScheme.COMMENT)
                    break
                }

                // 3. HTML comments (<!-- -->)
                if (ch == '<' && i + 3 < len && line[i + 1] == '!' && line[i + 2] == '-' && line[i + 3] == '-') {
                    addToken(i, EditorColorScheme.COMMENT)
                    currentState = 2
                    val endHtml = line.indexOf("-->", i + 4)
                    if (endHtml != -1) {
                        currentState = 0
                        i = endHtml + 3
                        continue
                    } else {
                        i = len
                        break
                    }
                }

                // 4. String literals ("...", '...', `...`)
                if (ch == '"' || ch == '\'' || ch == '`') {
                    addToken(i, EditorColorScheme.LITERAL)
                    val quote = ch
                    i++
                    while (i < len) {
                        if (line[i] == '\\' && i + 1 < len) {
                            i += 2
                        } else if (line[i] == quote) {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                    continue
                }

                // 5. HTML tags: <tag ...> or </tag>
                if (ch == '<' && isHtmlLike && i + 1 < len && (line[i + 1].isLetter() || line[i + 1] == '/')) {
                    addToken(i, EditorColorScheme.HTML_TAG)
                    while (i < len && line[i] != '>') {
                        i++
                    }
                    if (i < len && line[i] == '>') {
                        i++
                    }
                    continue
                }

                // 6. Numbers
                if (ch.isDigit()) {
                    addToken(i, EditorColorScheme.OPERATOR)
                    while (i < len && (line[i].isDigit() || line[i] == '.' || line[i] == 'x' || line[i] == 'X' || (line[i] in 'a'..'f') || (line[i] in 'A'..'F'))) {
                        i++
                    }
                    continue
                }

                // 7. Identifiers / Keywords / Functions
                if (ch.isLetter() || ch == '_' || ch == '$') {
                    val start = i
                    while (i < len && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '$')) {
                        i++
                    }
                    val word = line.subSequence(start, i).toString()

                    var j = i
                    while (j < len && line[j].isWhitespace()) j++
                    val isFunc = j < len && line[j] == '('

                    if (KEYWORDS.contains(word)) {
                        addToken(start, EditorColorScheme.KEYWORD)
                    } else if (isFunc) {
                        addToken(start, EditorColorScheme.FUNCTION_NAME)
                    } else {
                        addToken(start, EditorColorScheme.TEXT_NORMAL)
                    }
                    continue
                }

                // 8. Normal symbols / whitespace
                addToken(i, EditorColorScheme.TEXT_NORMAL)
                i++
            }

            return IncrementalAnalyzeManager.LineTokenizeResult(currentState, tokens)
        } catch (e: Throwable) {
            Log.e(TAG, "tokenizeLine ERROR at line $lineIndex", e)
            return IncrementalAnalyzeManager.LineTokenizeResult(0, emptyList())
        }
    }

    override fun generateSpansForLine(result: IncrementalAnalyzeManager.LineTokenizeResult<Int, CodeToken>): List<Span> {
        try {
            val tokens = result.tokens
            if (tokens.isNullOrEmpty()) {
                return listOf(Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
            }
            val spans = ArrayList<Span>(tokens.size + 1)
            if (tokens[0].column != 0) {
                spans.add(Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
            }
            for (token in tokens) {
                spans.add(Span.obtain(token.column, TextStyle.makeStyle(token.colorId)))
            }
            return spans
        } catch (e: Throwable) {
            Log.e(TAG, "generateSpansForLine ERROR", e)
            return listOf(Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
        }
    }

    override fun computeBlocks(
        content: Content,
        delegate: CodeBlockAnalyzeDelegate
    ): Nothing? {
        return null
    }
}
