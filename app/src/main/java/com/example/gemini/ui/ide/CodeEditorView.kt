package com.example.gemini.ui.ide

import androidx.compose.ui.graphics.toArgb

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import androidx.core.graphics.ColorUtils

class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "CodeEditorDebug"
    }

    private var currentFile = ""
    private var isSettingContentProgrammatically = false

    val editor = CodeEditor(context).apply {
        typefaceText = Typeface.MONOSPACE
        typefaceLineNumber = Typeface.MONOSPACE
        setTextSize(14f)
        // VS Code dark until the screen hands over the app theme's colors (applyThemeColors)
        colorScheme = buildColorScheme(EditorThemeColors.VS_DARK)
        isLineNumberEnabled = true
        setPinLineNumber(true)
        isWordwrap = false
        isEditable = true
    }

    private var appliedTheme: EditorThemeColors? = null

    /** Colors the editor with the app theme's IDE colors; does nothing when they did not change. */
    fun applyThemeColors(theme: EditorThemeColors) {
        if (theme == appliedTheme) return
        appliedTheme = theme
        editor.colorScheme = buildColorScheme(theme)
    }

    private fun buildColorScheme(t: EditorThemeColors): EditorColorScheme {
        val bg = t.background or 0xFF000000.toInt()
        val dark = ColorUtils.calculateLuminance(bg) < 0.4
        // every editor color starts from the matching built-in scheme, so anything not set below still suits the background
        val scheme = if (dark) SchemeDarcula() else EditorColorScheme()
        // syntax and text colors are kept readable on the background: one too close to it is moved lighter / darker
        val text = readable(t.text, bg, 4.5)
        val muted = readable(t.muted, bg, 2.5)
        fun syntax(c: Int, min: Double = 3.0) = readable(c, bg, min)
        val accent = readable(t.accent, bg, 3.0)

        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, bg)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bg)
        scheme.setColor(EditorColorScheme.LINE_NUMBER, muted)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, text)
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, t.line)
        scheme.setColor(EditorColorScheme.CURRENT_LINE, t.panel)
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, t.selection)
        scheme.setColor(EditorColorScheme.SELECTION_HANDLE, accent)
        scheme.setColor(EditorColorScheme.SELECTION_INSERT, accent)
        scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, ColorUtils.setAlphaComponent(accent, 0x55))
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, accent)
        scheme.setColor(EditorColorScheme.BLOCK_LINE, t.line)
        scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, ColorUtils.setAlphaComponent(muted, 0xAA))
        scheme.setColor(EditorColorScheme.SIDE_BLOCK_LINE, t.line)
        scheme.setColor(EditorColorScheme.NON_PRINTABLE_CHAR, muted)
        scheme.setColor(EditorColorScheme.HARD_WRAP_MARKER, t.line)
        scheme.setColor(EditorColorScheme.STICKY_SCROLL_DIVIDER, t.border)
        scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB, ColorUtils.setAlphaComponent(muted, 0x66))
        scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, ColorUtils.setAlphaComponent(muted, 0xAA))
        scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, t.panel)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, text)
        scheme.setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, t.panel)
        scheme.setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, text)

        scheme.setColor(EditorColorScheme.TEXT_NORMAL, text)
        scheme.setColor(EditorColorScheme.IDENTIFIER_NAME, text)
        scheme.setColor(EditorColorScheme.IDENTIFIER_VAR, text)
        scheme.setColor(EditorColorScheme.KEYWORD, syntax(t.keyword))
        scheme.setColor(EditorColorScheme.LITERAL, syntax(t.string))
        scheme.setColor(EditorColorScheme.COMMENT, syntax(t.comment, 2.5))
        scheme.setColor(EditorColorScheme.OPERATOR, syntax(t.punctuation))
        scheme.setColor(EditorColorScheme.FUNCTION_NAME, syntax(t.function))
        scheme.setColor(EditorColorScheme.ANNOTATION, syntax(t.annotation))
        scheme.setColor(EditorColorScheme.HTML_TAG, syntax(t.tag))
        scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, syntax(t.attribute))
        scheme.setColor(EditorColorScheme.ATTRIBUTE_VALUE, syntax(t.string))
        return scheme
    }

    /** [color] (made opaque) if it reaches [minContrast] on [bg]; otherwise the same hue made darker / lighter until it does. */
    private fun readable(color: Int, bg: Int, minContrast: Double): Int {
        var c = color or 0xFF000000.toInt()
        if (ColorUtils.calculateContrast(c, bg) >= minContrast) return c
        val towardDark = ColorUtils.calculateLuminance(bg) >= 0.4
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        repeat(25) {
            hsl[2] = (hsl[2] + if (towardDark) -0.04f else 0.04f).coerceIn(0f, 1f)
            c = ColorUtils.HSLToColor(hsl)
            if (ColorUtils.calculateContrast(c, bg) >= minContrast) return c
        }
        return if (towardDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    var onContentChangeListener: ((String) -> Unit)? = null
    var onUndoRedoStateListener: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
    var onSearchResultListener: ((matchCount: Int, currentIndex: Int) -> Unit)? = null

    var isWordWrapEnabled: Boolean
        get() = editor.isWordwrap
        set(value) {
            editor.isWordwrap = value
        }

    var isReadOnly: Boolean
        get() = !editor.isEditable
        set(value) {
            editor.isEditable = !value
        }


    init {
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Disable auto-completion popup hints
        editor.getComponent(EditorAutoCompletion::class.java).isEnabled = false
        editor.searcher.setCyclicJumping(true)

        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (isSettingContentProgrammatically) return@subscribeEvent
            Log.d(TAG, "ContentChangeEvent: editorTextLen=${editor.text.length}")
            onContentChangeListener?.invoke(editor.text.toString())
            onUndoRedoStateListener?.invoke(editor.canUndo(), editor.canRedo())
        }

        editor.subscribeEvent(io.github.rosemoe.sora.event.PublishSearchResultEvent::class.java) { _, _ ->
            val searcher = editor.searcher
            if (searcher.hasQuery()) {
                try {
                    onSearchResultListener?.invoke(searcher.matchedPositionCount, searcher.currentMatchedPositionIndex)
                } catch (_: Exception) {
                    onSearchResultListener?.invoke(0, -1)
                }
            } else {
                onSearchResultListener?.invoke(0, -1)
            }
        }
    }

    fun undo() {
        if (editor.canUndo()) {
            editor.undo()
            onUndoRedoStateListener?.invoke(editor.canUndo(), editor.canRedo())
        }
    }

    fun redo() {
        if (editor.canRedo()) {
            editor.redo()
            onUndoRedoStateListener?.invoke(editor.canUndo(), editor.canRedo())
        }
    }

    fun canUndo(): Boolean = editor.canUndo()
    fun canRedo(): Boolean = editor.canRedo()
    fun getText(): String = editor.text.toString()

    fun search(query: String, caseSensitive: Boolean = false) {
        if (query.isEmpty()) {
            stopSearch()
        } else {
            try {
                val options = io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions(
                    io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions.TYPE_NORMAL,
                    !caseSensitive
                )
                editor.searcher.search(query, options)
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
                onSearchResultListener?.invoke(0, -1)
            }
        }
    }

    fun findNext() {
        val searcher = editor.searcher
        if (searcher.hasQuery()) {
            try {
                searcher.gotoNext()
                onSearchResultListener?.invoke(searcher.matchedPositionCount, searcher.currentMatchedPositionIndex)
            } catch (_: Exception) {
            }
        }
    }

    fun findPrevious() {
        val searcher = editor.searcher
        if (searcher.hasQuery()) {
            try {
                searcher.gotoPrevious()
                onSearchResultListener?.invoke(searcher.matchedPositionCount, searcher.currentMatchedPositionIndex)
            } catch (_: Exception) {
            }
        }
    }

    fun stopSearch() {
        try {
            if (editor.searcher.hasQuery()) {
                editor.searcher.stopSearch()
            }
        } catch (_: Exception) {
        }
        onSearchResultListener?.invoke(0, -1)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null) {
            val edgeThreshold = 20 * resources.displayMetrics.density
            if (ev.x > edgeThreshold) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    fun updateContentIfDifferent(content: String) {
        if (editor.text.toString() != content) {
            val targetBottomLine = editor.lastVisibleLine
            isSettingContentProgrammatically = true
            try {
                editor.setText(content)
                if (targetBottomLine > 0) {
                    editor.post {
                        try {
                            val safeLine = targetBottomLine.coerceIn(0, (editor.lineCount - 1).coerceAtLeast(0))
                            editor.setSelection(safeLine, 0)
                            editor.ensureSelectionVisible()
                        } catch (_: Exception) {}
                    }
                }
            } finally {
                isSettingContentProgrammatically = false
            }
        }
    }

    fun setFile(fileName: String, content: String) {
        Log.d(TAG, "setFile: requestFile=$fileName, currentFile=$currentFile, editorLength=${editor.text.length}")
        if (currentFile != fileName) {
            currentFile = fileName
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val lang = when (ext) {
                "c", "cpp", "h", "hpp", "java", "kt", "kts", "cs" -> io.github.rosemoe.sora.langs.java.JavaLanguage()
                else -> UniversalCodeLanguage(ext)
            }
            Log.d(TAG, "setFile: setting language ${lang.javaClass.simpleName} for ext=$ext, contentLen=${content.length}")
            editor.setEditorLanguage(lang)
            isSettingContentProgrammatically = true
            try {
                editor.setText(content)
            } finally {
                isSettingContentProgrammatically = false
            }
        } else {
            updateContentIfDifferent(content)
        }
    }
}

/** The IDE editor's colors (ARGB ints), taken from the app theme's editor and syntax colors. */
data class EditorThemeColors(
    val background: Int,
    val panel: Int,
    val line: Int,
    val border: Int,
    val text: Int,
    val muted: Int,
    val selection: Int,
    val accent: Int,
    val keyword: Int,
    val string: Int,
    val comment: Int,
    val function: Int,
    val punctuation: Int,
    val annotation: Int,
    val tag: Int,
    val attribute: Int
) {
    companion object {
        /** The look the editor had before it followed the theme (VS Code dark). */
        val VS_DARK = EditorThemeColors(
            background = 0xFF1E1E1E.toInt(), panel = 0xFF252526.toInt(), line = 0xFF2D2D2D.toInt(), border = 0xFF3C3C3C.toInt(),
            text = 0xFFD4D4D4.toInt(), muted = 0xFF6E7681.toInt(), selection = 0xFF264F78.toInt(), accent = 0xFF007ACC.toInt(),
            keyword = 0xFFC586C0.toInt(), string = 0xFFCE9178.toInt(), comment = 0xFF6A9955.toInt(), function = 0xFFDCDCAA.toInt(),
            punctuation = 0xFFD4D4D4.toInt(), annotation = 0xFFDCDCAA.toInt(), tag = 0xFF569CD6.toInt(), attribute = 0xFF9CDCFE.toInt()
        )

        /** The current app theme's IDE colors (reading them in Compose follows theme changes). */
        fun fromAppTheme(): EditorThemeColors {
            val c = com.example.gemini.theme.AppColors
            fun i(color: androidx.compose.ui.graphics.Color) = color.toArgb()
            return EditorThemeColors(
                background = i(c.editorBg), panel = i(c.editorPanel), line = i(c.editorLine), border = i(c.editorBorder),
                text = i(c.editorText), muted = i(c.editorMuted), selection = i(c.editorSelection), accent = i(c.primary),
                keyword = i(c.syntaxKeyword), string = i(c.syntaxString), comment = i(c.syntaxComment), function = i(c.syntaxFunction),
                punctuation = i(c.syntaxPunctuation), annotation = i(c.syntaxAnnotation), tag = i(c.syntaxTag), attribute = i(c.syntaxAttribute)
            )
        }
    }
}
