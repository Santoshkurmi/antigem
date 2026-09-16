package com.example.gemini.data.local

import java.io.ByteArrayOutputStream

/**
 * Robust, high-performance binary parser and decoder for tmux Control Mode (-CC).
 * Operates at the raw byte stream level to avoid UTF-8 boundary corruption,
 * decodes octal-escaped pane output streams into exact binary byte arrays,
 * and processes tmux control events without lossy heuristics.
 */
class TmuxControlParser(
    private val onPaneOutput: (paneId: String, data: ByteArray) -> Unit,
    private val onRawFallbackOutput: ((data: ByteArray, offset: Int, length: Int) -> Unit)? = null,
    private val onControlModeStarted: (() -> Unit)? = null,
    private val onWindowAdd: ((windowId: String) -> Unit)? = null,
    private val onWindowClose: ((windowId: String) -> Unit)? = null,
    private val onPaneExited: ((paneId: String) -> Unit)? = null,
    private val onExit: ((reason: String?) -> Unit)? = null,
    private val onCommandResponse: ((cmdNumber: Long, output: ByteArray, isError: Boolean) -> Unit)? = null,
    private val onUnhandledEvent: ((eventLine: String) -> Unit)? = null
) {
    private var inCommandBlock = false
    private var currentCmdNumber: Long = -1
    private val commandOutputBytes = ByteArrayOutputStream()
    private val rawLineBuffer = ByteArrayOutputStream(4096)

    var isControlModeActive: Boolean = false
        private set

    private fun activateControlMode() {
        if (!isControlModeActive) {
            isControlModeActive = true
            onControlModeStarted?.invoke()
        }
    }

    /**
     * Feeds incoming raw stream data from SSH channel into the parser.
     * Operates strictly on bytes so multi-byte UTF-8 sequences and octal escapes
     * crossing chunk boundaries are never truncated or corrupted into \uFFFD.
     */
    fun feedData(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isControlModeActive) {
            // Check for tmux -CC startup signature in initial preview bytes
            val previewLen = minOf(length, 128)
            val preview = String(data, offset, previewLen, Charsets.ISO_8859_1)
            if (preview.contains("\u001bP1000p") || preview.startsWith("%begin") ||
                preview.startsWith("%output") || preview.startsWith("%window-add") ||
                preview.startsWith("%layout-change") || preview.startsWith("%session-changed")
            ) {
                activateControlMode()
            } else if (!preview.contains("%") && !preview.contains("\u001bP1000p")) {
                // Raw non-tmux shell stream fallback
                onRawFallbackOutput?.invoke(data, offset, length)
                return
            }
        }

        val end = offset + length
        for (i in offset until end) {
            val b = data[i]
            if (b == 0x0A.toByte()) { // '\n'
                var lineBytes = rawLineBuffer.toByteArray()
                rawLineBuffer.reset()
                // Strip trailing '\r' (0x0D) if present
                if (lineBytes.isNotEmpty() && lineBytes[lineBytes.size - 1] == 0x0D.toByte()) {
                    lineBytes = lineBytes.copyOf(lineBytes.size - 1)
                }
                processRawLine(lineBytes)
            } else {
                rawLineBuffer.write(b.toInt() and 0xFF)
            }
        }
    }

    /**
     * Processes a single raw binary line received from tmux -CC.
     */
    private fun processRawLine(line: ByteArray) {
        if (line.isEmpty()) return

        var startIndex = 0
        var len = line.size

        // Strip initial \033P1000p DCS preamble if present on the first line
        if (len >= 7 &&
            line[0] == 0x1B.toByte() &&
            line[1] == 'P'.code.toByte() &&
            line[2] == '1'.code.toByte() &&
            line[3] == '0'.code.toByte() &&
            line[4] == '0'.code.toByte() &&
            line[5] == '0'.code.toByte() &&
            line[6] == 'p'.code.toByte()
        ) {
            activateControlMode()
            startIndex = 7
            len -= 7
        }

        if (len <= 0) return

        val firstByte = line[startIndex]

        if (firstByte == '%'.code.toByte()) {
            activateControlMode()

            // Find first space (tag boundary)
            var firstSpace = -1
            for (i in startIndex until (startIndex + len)) {
                if (line[i] == ' '.code.toByte()) {
                    firstSpace = i
                    break
                }
            }

            val tag = if (firstSpace != -1) {
                String(line, startIndex, firstSpace - startIndex, Charsets.US_ASCII)
            } else {
                String(line, startIndex, len, Charsets.US_ASCII)
            }

            val restOffset = if (firstSpace != -1) firstSpace + 1 else startIndex + len
            val restLen = if (firstSpace != -1) (startIndex + len) - (firstSpace + 1) else 0

            when (tag) {
                "%output" -> {
                    // Syntax: %output %<pane_id> <octal_data>
                    var secondSpace = -1
                    for (i in restOffset until (restOffset + restLen)) {
                        if (line[i] == ' '.code.toByte()) {
                            secondSpace = i
                            break
                        }
                    }

                    if (secondSpace != -1) {
                        val paneId = String(line, restOffset, secondSpace - restOffset, Charsets.US_ASCII)
                        val dataOffset = secondSpace + 1
                        val dataLen = (restOffset + restLen) - dataOffset
                        val decodedBytes = decodeOctalBytes(line, dataOffset, dataLen)
                        val cleanBytes = stripScreenTitle(decodedBytes)
                        onPaneOutput(paneId, cleanBytes)
                    } else if (restLen > 0) {
                        val paneId = String(line, restOffset, restLen, Charsets.US_ASCII)
                        onPaneOutput(paneId, ByteArray(0))
                    }
                    return
                }
                "%begin" -> {
                    inCommandBlock = true
                    val restStr = if (restLen > 0) String(line, restOffset, restLen, Charsets.US_ASCII) else ""
                    val parts = restStr.split(" ")
                    currentCmdNumber = parts.getOrNull(1)?.toLongOrNull() ?: -1
                    commandOutputBytes.reset()
                    return
                }
                "%end" -> {
                    inCommandBlock = false
                    val restStr = if (restLen > 0) String(line, restOffset, restLen, Charsets.US_ASCII) else ""
                    val parts = restStr.split(" ")
                    val cmdNum = parts.getOrNull(1)?.toLongOrNull() ?: currentCmdNumber
                    val rawBytes = commandOutputBytes.toByteArray()
                    val output = decodeOctalBytes(rawBytes, 0, rawBytes.size)
                    commandOutputBytes.reset()
                    currentCmdNumber = -1
                    onCommandResponse?.invoke(cmdNum, output, false)
                    return
                }
                "%error" -> {
                    inCommandBlock = false
                    val restStr = if (restLen > 0) String(line, restOffset, restLen, Charsets.US_ASCII) else ""
                    val parts = restStr.split(" ")
                    val cmdNum = parts.getOrNull(1)?.toLongOrNull() ?: currentCmdNumber
                    val rawBytes = commandOutputBytes.toByteArray()
                    val output = decodeOctalBytes(rawBytes, 0, rawBytes.size)
                    commandOutputBytes.reset()
                    currentCmdNumber = -1
                    onCommandResponse?.invoke(cmdNum, output, true)
                    return
                }
                "%window-add" -> {
                    val restStr = if (restLen > 0) String(line, restOffset, restLen, Charsets.UTF_8) else ""
                    onWindowAdd?.invoke(restStr)
                    return
                }
                "%window-close" -> {
                    val restStr = if (restLen > 0) String(line, restOffset, restLen, Charsets.UTF_8) else ""
                    onWindowClose?.invoke(restStr)
                    return
                }
                else -> {
                    val eventStr = String(line, startIndex, len, Charsets.UTF_8)
                    onUnhandledEvent?.invoke(eventStr)
                    return
                }
            }
        }

        if (inCommandBlock) {
            if (commandOutputBytes.size() > 0) {
                commandOutputBytes.write('\n'.code)
            }
            commandOutputBytes.write(line, startIndex, len)
        } else {
            val eventStr = String(line, startIndex, len, Charsets.UTF_8)
            onUnhandledEvent?.invoke(eventStr)
        }
    }

    companion object {
        /**
         * Decodes tmux -CC octal escapes (\nnn and \134) into exact binary bytes.
         * Pure byte manipulation prevents any UTF-8 decoding corruption.
         */
        fun decodeOctalBytes(input: ByteArray, offset: Int = 0, length: Int = input.size): ByteArray {
            val out = ByteArrayOutputStream(length)
            var i = offset
            val end = offset + length
            while (i < end) {
                val b = input[i]
                if (b == '\\'.code.toByte() && i + 3 < end &&
                    isOctalDigit(input[i + 1]) &&
                    isOctalDigit(input[i + 2]) &&
                    isOctalDigit(input[i + 3])
                ) {
                    val b1 = (input[i + 1].toInt() - '0'.code) and 0x07
                    val b2 = (input[i + 2].toInt() - '0'.code) and 0x07
                    val b3 = (input[i + 3].toInt() - '0'.code) and 0x07
                    val octVal = (b1 shl 6) or (b2 shl 3) or b3
                    out.write(octVal)
                    i += 4
                } else {
                    out.write(b.toInt() and 0xFF)
                    i++
                }
            }
            return out.toByteArray()
        }

        /**
         * Decodes tmux -CC octal escapes from a String into raw binary bytes.
         */
        fun decodeOctal(input: String): ByteArray {
            val bytes = input.toByteArray(Charsets.ISO_8859_1)
            return decodeOctalBytes(bytes, 0, bytes.size)
        }

        /**
         * Encodes a ByteArray into space-separated hex bytes format for `send-keys -H` (e.g. "1b 5b 41").
         */
        fun encodeToHex(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size): String {
            if (count <= 0) return ""
            val sb = StringBuilder(count * 3)
            for (i in offset until (offset + count)) {
                if (sb.isNotEmpty()) sb.append(' ')
                val b = bytes[i].toInt() and 0xFF
                val hex = Integer.toHexString(b)
                if (hex.length == 1) sb.append('0')
                sb.append(hex)
            }
            return sb.toString()
        }

        /**
         * Strips GNU Screen / Tmux window title escape sequence: ESC k <title> (ESC \ | BEL)
         * which Zsh / Oh-My-Zsh / Bash preexec outputs to update window titles, but standard
         * xterm / Termux emulators do not implement, causing <title> to be printed as literal text.
         */
        fun stripScreenTitle(data: ByteArray): ByteArray {
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
            val n = data.size
            while (i < n) {
                if (i + 1 < n && data[i] == 0x1B.toByte() && data[i + 1] == 'k'.code.toByte()) {
                    i += 2
                    while (i < n) {
                        if (data[i] == 0x07.toByte()) { // BEL
                            i++
                            break
                        }
                        if (data[i] == 0x1B.toByte()) {
                            if (i + 1 < n && data[i + 1] == '\\'.code.toByte()) {
                                i += 2
                                break
                            }
                        }
                        i++
                    }
                } else {
                    out.write(data[i].toInt() and 0xFF)
                    i++
                }
            }
            return out.toByteArray()
        }

        private fun isOctalDigit(b: Byte): Boolean = b in '0'.code.toByte()..'7'.code.toByte()
    }
}
