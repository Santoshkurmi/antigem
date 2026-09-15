package com.example.gemini.data.local

import java.io.ByteArrayOutputStream

/**
 * High-performance parser and decoder for tmux Control Mode (-CC).
 * Decodes octal-escaped pane output streams and processes tmux control events.
 */
class TmuxControlParser(
    private val onPaneOutput: (paneId: String, data: ByteArray) -> Unit,
    private val onRawFallbackOutput: ((data: ByteArray, offset: Int, length: Int) -> Unit)? = null,
    private val onWindowAdd: ((windowId: String) -> Unit)? = null,
    private val onWindowClose: ((windowId: String) -> Unit)? = null,
    private val onCommandResponse: ((cmdNumber: Long, output: ByteArray, isError: Boolean) -> Unit)? = null,
    private val onUnhandledEvent: ((eventLine: String) -> Unit)? = null
) {
    private var inCommandBlock = false
    private var currentCmdNumber: Long = -1
    private val commandOutputBuffer = StringBuilder()
    private val lineBuffer = StringBuilder()

    var isControlModeActive: Boolean = false
        private set

    /**
     * Feeds incoming raw stream data from SSH channel into the parser.
     */
    fun feedData(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isControlModeActive) {
            // Check for tmux -CC startup signature
            val preview = String(data, offset, minOf(length, 128), Charsets.UTF_8)
            if (preview.contains("\u001bP1000p") || preview.startsWith("%begin") || preview.startsWith("%output") || preview.startsWith("%window-add")) {
                isControlModeActive = true
            } else if (!preview.contains("%") && !preview.contains("\u001bP1000p")) {
                // Raw non-tmux shell stream fallback
                onRawFallbackOutput?.invoke(data, offset, length)
                return
            }
        }

        val text = String(data, offset, length, Charsets.UTF_8)
        for (i in text.indices) {
            val c = text[i]
            if (c == '\n') {
                val line = lineBuffer.toString().trimEnd('\r')
                lineBuffer.setLength(0)
                processLine(line)
            } else {
                lineBuffer.append(c)
            }
        }
    }

    /**
     * Process a single line received from tmux -CC.
     */
    fun processLine(line: String) {
        if (line.isEmpty()) return

        // Strip initial \033P1000p DCS preamble if present on the first line
        val cleanLine = if (line.startsWith("\u001bP1000p")) {
            isControlModeActive = true
            line.substring(7).trim()
        } else {
            line
        }
        if (cleanLine.isEmpty()) return

        android.util.Log.d("TmuxParserDebug", "RAW_LINE: $cleanLine")

        if (cleanLine.startsWith("%")) {
            isControlModeActive = true
            val firstSpace = cleanLine.indexOf(' ')
            val tag = if (firstSpace != -1) cleanLine.substring(0, firstSpace) else cleanLine
            val rest = if (firstSpace != -1) cleanLine.substring(firstSpace + 1).trim() else ""

            when (tag) {
                "%output" -> {
                    val secondSpace = rest.indexOf(' ')
                    if (secondSpace != -1) {
                        val paneId = rest.substring(0, secondSpace)
                        val escapedData = rest.substring(secondSpace + 1)
                        val decodedBytes = decodeOctal(escapedData)
                        val cleanBytes = sanitizePaneOutput(decodedBytes)
                        android.util.Log.d("TmuxParserDebug", "PANE_OUTPUT ($paneId): ${String(cleanBytes, Charsets.UTF_8).replace("\n", "\\n").replace("\r", "\\r")}")
                        onPaneOutput(paneId, cleanBytes)
                    } else if (rest.isNotEmpty()) {
                        val paneId = rest
                        onPaneOutput(paneId, ByteArray(0))
                    }
                    return
                }
                "%begin" -> {
                    inCommandBlock = true
                    val parts = rest.split(" ")
                    currentCmdNumber = parts.getOrNull(1)?.toLongOrNull() ?: -1
                    commandOutputBuffer.setLength(0)
                    android.util.Log.d("TmuxParserDebug", "BEGIN_CMD: $currentCmdNumber")
                    return
                }
                "%end" -> {
                    inCommandBlock = false
                    val parts = rest.split(" ")
                    val cmdNum = parts.getOrNull(1)?.toLongOrNull() ?: currentCmdNumber
                    val rawText = commandOutputBuffer.toString().trimEnd('\n')
                    val decoded = if (rawText.isNotEmpty()) decodeOctal(rawText.replace("\n", "\r\n")) else ByteArray(0)
                    android.util.Log.d("TmuxParserDebug", "END_CMD: $cmdNum, len=${decoded.size}, text=${rawText.take(100)}")
                    onCommandResponse?.invoke(cmdNum, decoded, false)
                    commandOutputBuffer.setLength(0)
                    currentCmdNumber = -1
                    return
                }
                "%error" -> {
                    inCommandBlock = false
                    val parts = rest.split(" ")
                    val cmdNum = parts.getOrNull(1)?.toLongOrNull() ?: currentCmdNumber
                    val rawText = commandOutputBuffer.toString().trimEnd('\n')
                    val decoded = if (rawText.isNotEmpty()) decodeOctal(rawText.replace("\n", "\r\n")) else ByteArray(0)
                    android.util.Log.d("TmuxParserDebug", "ERROR_CMD: $cmdNum, text=${rawText.take(100)}")
                    onCommandResponse?.invoke(cmdNum, decoded, true)
                    commandOutputBuffer.setLength(0)
                    currentCmdNumber = -1
                    return
                }
                "%window-add" -> {
                    onWindowAdd?.invoke(rest)
                    return
                }
                "%window-close" -> {
                    onWindowClose?.invoke(rest)
                    return
                }
                else -> {
                    onUnhandledEvent?.invoke(cleanLine)
                    return
                }
            }
        }

        if (inCommandBlock) {
            if (commandOutputBuffer.isNotEmpty()) {
                commandOutputBuffer.append("\n")
            }
            commandOutputBuffer.append(cleanLine)
        } else {
            onUnhandledEvent?.invoke(cleanLine)
        }
    }

    companion object {
        /**
         * Decodes tmux -CC octal escapes (\nnn and \134) into raw binary bytes.
         */
        fun decodeOctal(input: String): ByteArray {
            val out = ByteArrayOutputStream(input.length)
            var i = 0
            val len = input.length
            while (i < len) {
                val c = input[i]
                if (c == '\\' && i + 3 < len &&
                    isOctalDigit(input[i + 1]) &&
                    isOctalDigit(input[i + 2]) &&
                    isOctalDigit(input[i + 3])
                ) {
                    val b = ((input[i + 1] - '0') shl 6) or
                            ((input[i + 2] - '0') shl 3) or
                            (input[i + 3] - '0')
                    out.write(b)
                    i += 4
                } else {
                    out.write(c.code)
                    i++
                }
            }
            return out.toByteArray()
        }

        /**
         * Encodes a ByteArray into hex format for `send-keys -H`.
         */
        fun encodeToHex(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size): String {
            if (count <= 0) return ""
            val sb = StringBuilder(count * 2)
            for (i in offset until (offset + count)) {
                val b = bytes[i].toInt() and 0xFF
                val hex = Integer.toHexString(b)
                if (hex.length == 1) sb.append('0')
                sb.append(hex)
            }
            return sb.toString()
        }

        /**
         * Strips screen/tmux proprietary title escape sequences (e.g. ESC k <title> ESC \ or ESC k <title> BEL)
         * so they don't get printed as literal text into standard terminal emulators.
         */
        fun sanitizePaneOutput(data: ByteArray): ByteArray {
            if (data.isEmpty()) return data
            var hasEscK = false
            for (idx in 0 until data.size - 1) {
                if (data[idx] == 0x1B.toByte() && data[idx + 1] == 'k'.code.toByte()) {
                    hasEscK = true
                    break
                }
            }
            if (!hasEscK) return data

            val out = ByteArrayOutputStream(data.size)
            var i = 0
            while (i < data.size) {
                if (i < data.size - 1 && data[i] == 0x1B.toByte() && data[i + 1] == 'k'.code.toByte()) {
                    i += 2
                    while (i < data.size) {
                        if (data[i] == 0x07.toByte()) {
                            i++
                            break
                        }
                        if (data[i] == 0x1B.toByte()) {
                            if (i + 1 < data.size && data[i + 1] == '\\'.code.toByte()) {
                                i += 2
                                break
                            }
                        }
                        i++
                    }
                } else {
                    out.write(data[i].toInt())
                    i++
                }
            }
            return out.toByteArray()
        }

        private fun isOctalDigit(c: Char): Boolean = c in '0'..'7'
    }
}
