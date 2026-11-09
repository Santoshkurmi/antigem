package com.example.gemini.data.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Works out what a tapped file path is before opening it: a folder, a missing file, text (opens in the editor) or
 * anything else (video, archive, binary: the user picks an app). Text is judged from the first bytes, not the
 * extension, the same way the bridge does it: no NUL bytes and valid UTF-8.
 */
object FileProbe {

    private const val SAMPLE = 4096

    sealed class Result {
        /** [local]: the app reads it itself, so opening it needs no bridge. [mime]: from the content when known. */
        data class Found(val path: String, val name: String, val size: Long, val textual: Boolean, val mime: String, val local: Boolean) : Result()
        data class Folder(val path: String) : Result()
        data class Unavailable(val message: String) : Result()
    }

    suspend fun probe(path: String): Result = withContext(Dispatchers.IO) {
        val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        val file = File(path)
        // readable here: the bridge runs on this phone, or the file is a copy downloaded into the app
        if (runCatching { file.isFile && file.canRead() }.getOrDefault(false)) {
            val textual = runCatching { looksLikeText(file) }.getOrElse { FileKinds.of(name).isTextual }
            return@withContext Result.Found(path, name, file.length(), textual, FileKinds.mimeOf(name), local = true)
        }
        FileExplorerApi.stat(path).fold(
            onSuccess = { s ->
                if (s.isDir) Result.Folder(s.path.ifBlank { path })
                else {
                    val textual = when (s.category) {
                        "text" -> true
                        "image", "binary" -> false
                        else -> FileKinds.of(name).isTextual // older bridge: fall back to the extension
                    }
                    val mime = s.sniffedMime.substringBefore(';').trim()
                        .takeIf { it.isNotBlank() && it != "application/octet-stream" && it != "text/plain" }
                        ?: s.mime.ifBlank { FileKinds.mimeOf(name) }
                    Result.Found(s.path.ifBlank { path }, name, s.size, textual, mime, local = false)
                }
            },
            onFailure = { Result.Unavailable(it.message?.takeIf { m -> m.isNotBlank() } ?: "Cannot open $name") }
        )
    }

    private fun looksLikeText(file: File): Boolean {
        val buf = ByteArray(SAMPLE + 1) // one extra byte tells whether the file goes on past the sample
        val read = file.inputStream().use { input ->
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            n
        }
        if (read == 0) return true
        val truncated = read > SAMPLE
        val n = minOf(read, SAMPLE)
        for (i in 0 until n) if (buf[i] == 0.toByte()) return false
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // when the sample was cut, a character split at the end is not an error (endOfInput = false)
        return !decoder.decode(ByteBuffer.wrap(buf, 0, n), CharBuffer.allocate(n), !truncated).isError
    }
}
