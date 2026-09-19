package com.example.gemini.ui.ide

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeVS2019

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
        colorScheme = SchemeVS2019().apply {
            setColor(EditorColorScheme.WHOLE_BACKGROUND, Color.parseColor("#1E1E1E"))
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, Color.parseColor("#1E1E1E"))
            setColor(EditorColorScheme.LINE_NUMBER, Color.parseColor("#6E7681"))
            setColor(EditorColorScheme.LINE_NUMBER_CURRENT, Color.parseColor("#CCCCCC"))
            setColor(EditorColorScheme.LINE_DIVIDER, Color.parseColor("#2D2D2D"))
            setColor(EditorColorScheme.CURRENT_LINE, Color.parseColor("#252526"))
            setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, Color.parseColor("#264F78"))
            setColor(EditorColorScheme.SELECTION_HANDLE, Color.parseColor("#007ACC"))
            setColor(EditorColorScheme.SELECTION_INSERT, Color.parseColor("#007ACC"))
            // Explicitly define syntax token colors to avoid black-on-black text
            setColor(EditorColorScheme.TEXT_NORMAL, Color.parseColor("#D4D4D4"))
            setColor(EditorColorScheme.HTML_TAG, Color.parseColor("#569CD6"))
            setColor(EditorColorScheme.KEYWORD, Color.parseColor("#C586C0"))
            setColor(EditorColorScheme.LITERAL, Color.parseColor("#CE9178"))
            setColor(EditorColorScheme.COMMENT, Color.parseColor("#6A9955"))
            setColor(EditorColorScheme.FUNCTION_NAME, Color.parseColor("#DCDCAA"))
            setColor(EditorColorScheme.OPERATOR, Color.parseColor("#D4D4D4"))
        }
        isLineNumberEnabled = true
        setPinLineNumber(true)
        isWordwrap = false
        isEditable = true
    }

    var onContentChangeListener: ((String) -> Unit)? = null

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

        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (isSettingContentProgrammatically) return@subscribeEvent
            Log.d(TAG, "ContentChangeEvent: editorTextLen=${editor.text.length}")
            onContentChangeListener?.invoke(editor.text.toString())
        }
    }

    fun updateContentIfDifferent(content: String) {
        if (editor.text.toString() != content) {
            isSettingContentProgrammatically = true
            try {
                editor.setText(content)
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
