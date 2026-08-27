package com.example.gemini.ui.ide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.OverScroller
import java.util.regex.Pattern

class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    private var isScrollingGesture = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var savedSelectionStart = 0
    private var savedSelectionEnd = 0

    private val scroller = OverScroller(context)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (!isScrollingGesture) {
                isScrollingGesture = true
                cancelLongPress()
                isCursorVisible = false
            }
            val l = layout ?: return false
            val maxScrollX = (l.width + paddingLeft + paddingRight - width).coerceAtLeast(0)
            val maxScrollY = (l.height + paddingTop + paddingBottom - height).coerceAtLeast(0)

            val newX = (scrollX + distanceX.toInt()).coerceIn(0, maxScrollX)
            val newY = (scrollY + distanceY.toInt()).coerceIn(0, maxScrollY)
            scrollTo(newX, newY)
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            isCursorVisible = false
            val l = layout ?: return false
            val maxScrollX = (l.width + paddingLeft + paddingRight - width).coerceAtLeast(0)
            val maxScrollY = (l.height + paddingTop + paddingBottom - height).coerceAtLeast(0)

            scroller.fling(
                scrollX,
                scrollY,
                (-velocityX).toInt(),
                (-velocityY).toInt(),
                0,
                maxScrollX,
                0,
                maxScrollY
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        } else if (!isScrollingGesture) {
            updateCursorVisibility()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isScrollingGesture = false
                savedSelectionStart = selectionStart
                savedSelectionEnd = selectionEnd
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    if (!isScrollingGesture) {
                        isScrollingGesture = true
                        cancelLongPress()
                        isCursorVisible = false
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isScrollingGesture) {
                    updateCursorVisibility()
                }
            }
        }

        gestureDetector.onTouchEvent(event)

        // If actively scrolling, consume MOVE to prevent text selection cursor/magnifier drag
        if (isScrollingGesture && event.actionMasked == MotionEvent.ACTION_MOVE) {
            return true
        }

        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        updateCursorVisibility()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        updateCursorVisibility()
    }

    private fun updateCursorVisibility() {
        val l = layout ?: return
        val cursorOffset = selectionStart
        if (cursorOffset < 0 || cursorOffset > (text?.length ?: 0)) {
            isCursorVisible = false
            return
        }
        val cursorLine = l.getLineForOffset(cursorOffset)
        val lineTop = l.getLineTop(cursorLine)
        val lineBottom = l.getLineBottom(cursorLine)
        val isVisible = lineBottom >= (scrollY - 20) && lineTop <= (scrollY + height + 20)
        if (isCursorVisible != isVisible) {
            isCursorVisible = isVisible
        }
    }

    private val lineNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#6E7681")
        textSize = 34f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }

    private val gutterBgPaint = Paint().apply {
        color = AndroidColor.parseColor("#1E1E1E")
        style = Paint.Style.FILL
    }

    private val dividerPaint = Paint().apply {
        color = AndroidColor.parseColor("#333333")
        strokeWidth = 2f
    }

    private val lineBoundsRect = Rect()
    private var gutterWidthPx = 110
    private var isFormatting = false
    private var fileExtension = ""

    var onContentChangeListener: ((String) -> Unit)? = null

    var isWordWrapEnabled: Boolean = false
        set(value) {
            field = value
            setHorizontallyScrolling(!value)
            requestLayout()
        }

    init {
        typeface = Typeface.MONOSPACE
        textSize = 13.5f
        setTextColor(AndroidColor.parseColor("#D4D4D4"))
        setBackgroundColor(AndroidColor.parseColor("#1E1E1E"))
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setHorizontallyScrolling(true)
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        isLongClickable = true
        updateGutterPadding()

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isFormatting || s == null) return
                updateGutterPadding()
                onContentChangeListener?.invoke(s.toString())
                highlightSyntax(s)
            }
        })
    }

    fun setFile(fileName: String, content: String) {
        fileExtension = fileName.substringAfterLast('.', "").lowercase()
        if (text?.toString() != content) {
            isFormatting = true
            setText(content)
            setSelection(0)
            updateGutterPadding()
            highlightSyntax(text)
            isFormatting = false
        }
    }

    private fun updateGutterPadding() {
        val lineDigits = (lineCount.coerceAtLeast(1).toString().length).coerceAtLeast(2)
        gutterWidthPx = (lineDigits * 26) + 40
        setPadding(gutterWidthPx + 20, 20, 20, 20)
    }

    private fun highlightSyntax(editable: Editable?) {
        if (editable == null || editable.length > 50000) return
        isFormatting = true
        try {
            val existingSpans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
            for (span in existingSpans) {
                editable.removeSpan(span)
            }

            val code = editable.toString()
            val ext = fileExtension

            // 1. Strings
            applyColor(editable, DOUBLE_QUOTE_STRING.matcher(code), AndroidColor.parseColor("#CE9178"))
            applyColor(editable, SINGLE_QUOTE_STRING.matcher(code), AndroidColor.parseColor("#CE9178"))
            applyColor(editable, BACKTICK_STRING.matcher(code), AndroidColor.parseColor("#CE9178"))

            // 2. Tags for HTML / JSX
            if (ext in listOf("html", "xml", "svg", "jsx", "tsx")) {
                applyColor(editable, HTML_TAG_PATTERN.matcher(code), AndroidColor.parseColor("#569CD6"))
            }

            // 3. Numbers
            applyColor(editable, NUMBER_PATTERN.matcher(code), AndroidColor.parseColor("#B5CEA8"))

            // 4. Keywords
            val wordMatcher = WORD_PATTERN.matcher(code)
            while (wordMatcher.find()) {
                val word = wordMatcher.group()
                val start = wordMatcher.start()
                val end = wordMatcher.end()
                when {
                    CONTROL_KEYWORDS.contains(word) -> {
                        editable.setSpan(ForegroundColorSpan(AndroidColor.parseColor("#D16D9E")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    KEYWORDS.contains(word) -> {
                        editable.setSpan(ForegroundColorSpan(AndroidColor.parseColor("#C586C0")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    TYPES.contains(word) -> {
                        editable.setSpan(ForegroundColorSpan(AndroidColor.parseColor("#4EC9B0")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            // 5. Function calls
            val funcMatcher = FUNC_CALL_PATTERN.matcher(code)
            while (funcMatcher.find()) {
                val name = funcMatcher.group(1) ?: continue
                val start = funcMatcher.start(1)
                val end = funcMatcher.end(1)
                if (!KEYWORDS.contains(name) && !CONTROL_KEYWORDS.contains(name)) {
                    editable.setSpan(ForegroundColorSpan(AndroidColor.parseColor("#DCDCAA")), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // 6. Comments
            applyColor(editable, SINGLE_LINE_COMMENT.matcher(code), AndroidColor.parseColor("#6A9955"))
            applyColor(editable, MULTI_LINE_COMMENT.matcher(code), AndroidColor.parseColor("#6A9955"))
        } finally {
            isFormatting = false
        }
    }

    private fun applyColor(editable: Editable, matcher: java.util.regex.Matcher, color: Int) {
        while (matcher.find()) {
            editable.setSpan(ForegroundColorSpan(color), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Draw normal text
        super.onDraw(canvas)

        val l = layout ?: return
        val totalLines = lineCount
        if (totalLines <= 0) return

        val sX = scrollX.toFloat()
        val sY = scrollY.toFloat()
        val h = height.toFloat()
        val gutterRight = sX + gutterWidthPx

        // 1. Draw solid gutter background over scrolled text so code scrolls under the gutter cleanly
        canvas.drawRect(sX, sY, gutterRight, sY + h, gutterBgPaint)

        // 2. Draw vertical divider
        canvas.drawLine(gutterRight, sY, gutterRight, sY + h, dividerPaint)

        // 3. Draw line numbers pinned to left gutter
        val firstLine = l.getLineForVertical(scrollY)
        val lastLine = l.getLineForVertical(scrollY + height).coerceAtMost(totalLines - 1)
        val contentStr = text?.toString() ?: ""
        val numX = gutterRight - 12f

        for (line in firstLine..lastLine) {
            val startOffset = l.getLineStart(line)
            val isRealLineStart = (startOffset == 0 || (contentStr.length > startOffset - 1 && contentStr[startOffset - 1] == '\n'))

            if (isRealLineStart) {
                val baseline = getLineBounds(line, lineBoundsRect)
                val lineStr = (line + 1).toString()
                canvas.drawText(lineStr, numX, baseline.toFloat(), lineNumPaint)
            }
        }
    }

    companion object {
        private val KEYWORDS = setOf(
            "package", "import", "func", "def", "class", "struct", "interface", "type",
            "val", "var", "const", "let", "function", "fn", "pub", "mut", "impl", "trait",
            "enum", "union", "typedef", "namespace", "using", "template", "typename",
            "public", "private", "protected", "override", "abstract", "sealed", "virtual",
            "static", "final", "volatile", "transient", "synchronized", "native", "extern",
            "inline", "constexpr", "explicit", "friend", "operator", "alias", "as"
        )
        private val CONTROL_KEYWORDS = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "default", "break",
            "continue", "return", "goto", "yield", "await", "async", "defer", "go",
            "try", "catch", "finally", "throw", "throws", "raise", "except", "with",
            "select", "range", "in", "is", "not", "and", "or"
        )
        private val TYPES = setOf(
            "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32",
            "float", "float32", "float64", "double", "bool", "boolean", "string", "byte", "char",
            "void", "any", "object", "nil", "null", "none", "true", "false", "self", "this"
        )
        private val SINGLE_LINE_COMMENT = Pattern.compile("(//.*|#.*)")
        private val MULTI_LINE_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/")
        private val DOUBLE_QUOTE_STRING = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"")
        private val SINGLE_QUOTE_STRING = Pattern.compile("'([^'\\\\]|\\\\.)*'")
        private val BACKTICK_STRING = Pattern.compile("`([^`\\\\]|\\\\.)*`")
        private val NUMBER_PATTERN = Pattern.compile("\\b(0x[0-9a-fA-F]+|\\d+(\\.\\d+)?)\\b")
        private val WORD_PATTERN = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
        private val FUNC_CALL_PATTERN = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?=\\()")
        private val HTML_TAG_PATTERN = Pattern.compile("</?[a-zA-Z0-9:-]+(\\s+[^>]*)?/?>")
    }
}
