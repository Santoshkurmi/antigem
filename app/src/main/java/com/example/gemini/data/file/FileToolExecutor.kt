package com.example.gemini.data.file

import android.util.Log
import com.example.gemini.data.ssh.TermuxSshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * File Tool Executor — hash-locked file I/O for the AI agent.
 *
 * ## Why hash locking?
 * The AI reads a file, thinks, then writes. In that window the file could change.
 * Line-based writes (replace lines 10-20) are fragile: if even one line is
 * inserted above, everything shifts. The 0%-error solution is:
 *
 *   READ  -> returns content + MD5 hash of current file state
 *   WRITE -> requires the same MD5 from the last read
 *           -> recalculates current hash before writing
 *           -> if hashes differ -> REJECT ("File changed, re-read first")
 *           -> if hashes match  -> write safely, return new hash
 *
 * EDIT (search-and-replace) is even safer: no line numbers at all.
 * The AI provides the exact string to find. If not found -> file changed -> re-read.
 *
 * Transport: base64 is used for all content I/O to survive any special characters,
 * null bytes, quotes, or newlines in the file content.
 */
object FileToolExecutor {

    private const val TAG = "FileToolExecutor"

    // Result types

    data class ReadResult(
        val path: String,
        val content: String,
        val hash: String,
        val totalLines: Int,
        val truncated: Boolean = false
    )

    data class WriteResult(
        val path: String,
        val bytesWritten: Int,
        val newHash: String
    )

    data class EditResult(
        val path: String,
        val replaced: Boolean,
        val newHash: String = ""
    )

    // SSH helpers

    private suspend fun runSsh(
        cmd: String,
        host: String, port: Int, user: String, pass: String
    ): Pair<String, Int> {
        val result = TermuxSshManager.executeCommand(
            command = cmd,
            host = host, port = port, user = user, pass = pass,
            targetTabId = "primary"
        )
        return Pair(result.output.trim(), result.exitCode ?: -1)
    }

    /**
     * Read a file (or line range) over SSH.
     * Returns content with line numbers prepended + MD5 hash of the FULL file.
     * The hash must be sent back with any write/edit call.
     */
    suspend fun readFile(
        path: String,
        host: String, port: Int, user: String, pass: String,
        startLine: Int? = null,
        endLine: Int? = null
    ): Result<ReadResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[read_file] path=$path")

            val (hashOut, hashExit) = runSsh("md5sum \"$path\" 2>&1 | awk '{print \$1}'", host, port, user, pass)
            if (hashExit != 0 || hashOut.isBlank()) {
                return@withContext Result.failure(Exception("File not found or unreadable: $path"))
            }
            val hash = hashOut.trim()

            val (linesOut, _) = runSsh("wc -l < \"$path\"", host, port, user, pass)
            val totalLines = linesOut.trim().toIntOrNull() ?: 0

            val sedRange = when {
                startLine != null && endLine != null -> "${startLine},${endLine}p"
                startLine != null -> "${startLine},\$p"
                endLine != null -> "1,${endLine}p"
                else -> null
            }
            val readCmd = if (sedRange != null) {
                "sed -n '${sedRange}' \"$path\" | base64"
            } else {
                "base64 \"$path\""
            }
            val (b64Out, readExit) = runSsh(readCmd, host, port, user, pass)
            if (readExit != 0) {
                return@withContext Result.failure(Exception("Failed to read file: $path"))
            }

            val rawContent = try {
                String(android.util.Base64.decode(b64Out.replace("\\s".toRegex(), ""), android.util.Base64.DEFAULT))
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Failed to decode file content: ${e.message}"))
            }

            val effectiveStart = startLine ?: 1
            val numberedContent = rawContent.lines().mapIndexed { idx, line ->
                "${effectiveStart + idx}: $line"
            }.joinToString("\n")

            Log.d(TAG, "[read_file] SUCCESS path=$path hash=$hash lines=$totalLines")
            Result.success(ReadResult(path = path, content = numberedContent, hash = hash, totalLines = totalLines))
        } catch (e: Exception) {
            Log.e(TAG, "[read_file] Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Overwrite a file completely. Requires expectedHash from a prior read_file call.
     * Pass expectedHash = "NEW" for new files that don't exist yet.
     */
    suspend fun writeFile(
        path: String,
        content: String,
        expectedHash: String,
        host: String, port: Int, user: String, pass: String
    ): Result<WriteResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[write_file] path=$path expectedHash=$expectedHash")

            if (expectedHash.uppercase() != "NEW") {
                val (currentHash, hashExit) = runSsh("md5sum \"$path\" 2>&1 | awk '{print \$1}'", host, port, user, pass)
                if (hashExit == 0 && currentHash.trim() != expectedHash) {
                    Log.w(TAG, "[write_file] HASH MISMATCH expected=$expectedHash current=${currentHash.trim()}")
                    return@withContext Result.failure(Exception(
                        "WRITE REJECTED: File has changed since your last read.\n" +
                        "Expected hash: $expectedHash\n" +
                        "Current hash:  ${currentHash.trim()}\n" +
                        "Please use read_file again to get the latest version before writing."
                    ))
                }
            }

            val b64Content = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val parentDir = path.substringBeforeLast("/")
            if (parentDir.isNotBlank() && parentDir != path) {
                runSsh("mkdir -p \"$parentDir\"", host, port, user, pass)
            }
            val (_, writeExit) = runSsh("printf '%s' '$b64Content' | base64 -d > \"$path\"", host, port, user, pass)
            if (writeExit != 0) {
                return@withContext Result.failure(Exception("Failed to write file: $path (exit code $writeExit)"))
            }

            val (newHash, _) = runSsh("md5sum \"$path\" | awk '{print \$1}'", host, port, user, pass)
            val (sizeOut, _) = runSsh("wc -c < \"$path\"", host, port, user, pass)
            val bytesWritten = sizeOut.trim().toIntOrNull() ?: content.length

            Log.d(TAG, "[write_file] SUCCESS path=$path newHash=${newHash.trim()} bytes=$bytesWritten")
            Result.success(WriteResult(path = path, bytesWritten = bytesWritten, newHash = newHash.trim()))
        } catch (e: Exception) {
            Log.e(TAG, "[write_file] Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Search-and-replace edit — safest way for AI to make partial edits.
     * No line numbers: AI provides exact string to find and replacement.
     * If old_str not found -> file changed -> AI must re-read.
     */
    suspend fun editFile(
        path: String,
        oldStr: String,
        newStr: String,
        expectedHash: String,
        host: String, port: Int, user: String, pass: String,
        replaceAll: Boolean = false
    ): Result<EditResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[edit_file] path=$path expectedHash=$expectedHash replaceAll=$replaceAll")

            val (currentHash, hashExit) = runSsh("md5sum \"$path\" 2>&1 | awk '{print \$1}'", host, port, user, pass)
            if (hashExit != 0) {
                return@withContext Result.failure(Exception("File not found: $path"))
            }
            if (currentHash.trim() != expectedHash) {
                Log.w(TAG, "[edit_file] HASH MISMATCH expected=$expectedHash current=${currentHash.trim()}")
                return@withContext Result.failure(Exception(
                    "EDIT REJECTED: File has changed since your last read.\n" +
                    "Expected hash: $expectedHash\n" +
                    "Current hash:  ${currentHash.trim()}\n" +
                    "Please use read_file again before editing."
                ))
            }

            val (b64Current, readExit) = runSsh("base64 \"$path\"", host, port, user, pass)
            if (readExit != 0) {
                return@withContext Result.failure(Exception("Failed to read file for edit: $path"))
            }
            val currentContent = try {
                String(android.util.Base64.decode(b64Current.replace("\\s".toRegex(), ""), android.util.Base64.DEFAULT))
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Failed to decode file: ${e.message}"))
            }

            if (!currentContent.contains(oldStr)) {
                Log.w(TAG, "[edit_file] old_str NOT FOUND in $path")
                return@withContext Result.success(EditResult(path = path, replaced = false))
            }

            val newContent = if (replaceAll) currentContent.replace(oldStr, newStr) else currentContent.replaceFirst(oldStr, newStr)
            val b64New = android.util.Base64.encodeToString(newContent.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val (_, writeExit) = runSsh("printf '%s' '$b64New' | base64 -d > \"$path\"", host, port, user, pass)
            if (writeExit != 0) {
                return@withContext Result.failure(Exception("Failed to write edited file: $path"))
            }

            val (newHash, _) = runSsh("md5sum \"$path\" | awk '{print \$1}'", host, port, user, pass)
            Log.d(TAG, "[edit_file] SUCCESS path=$path newHash=${newHash.trim()}")
            Result.success(EditResult(path = path, replaced = true, newHash = newHash.trim()))
        } catch (e: Exception) {
            Log.e(TAG, "[edit_file] Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    // JSON payload parsers

    data class ParsedReadPayload(val path: String, val startLine: Int?, val endLine: Int?)
    data class ParsedWritePayload(val path: String, val content: String, val expectedHash: String)
    data class ParsedEditPayload(val path: String, val oldStr: String, val newStr: String, val expectedHash: String, val replaceAll: Boolean)

    fun parseReadPayload(json: String): ParsedReadPayload {
        val obj = JSONObject(json)
        return ParsedReadPayload(
            path = obj.getString("path"),
            startLine = if (obj.has("start_line")) obj.getInt("start_line") else null,
            endLine = if (obj.has("end_line")) obj.getInt("end_line") else null
        )
    }

    fun parseWritePayload(json: String): ParsedWritePayload {
        val obj = JSONObject(json)
        return ParsedWritePayload(
            path = obj.getString("path"),
            content = obj.getString("content"),
            expectedHash = obj.optString("expected_hash", "NEW")
        )
    }

    fun parseEditPayload(json: String): ParsedEditPayload {
        val obj = JSONObject(json)
        return ParsedEditPayload(
            path = obj.getString("path"),
            oldStr = obj.getString("old_str"),
            newStr = obj.getString("new_str"),
            expectedHash = obj.getString("expected_hash"),
            replaceAll = obj.optBoolean("replace_all", false)
        )
    }

    // Format results as tool output strings for the AI

    fun formatReadOutput(result: ReadResult): String = buildString {
        append("File: ${result.path}\n")
        append("Total lines: ${result.totalLines}\n")
        append("Hash (required for write/edit): ${result.hash}\n")
        append("---\n")
        append(result.content)
    }

    fun formatWriteOutput(result: WriteResult): String =
        "File written: ${result.path}\nBytes written: ${result.bytesWritten}\nNew hash: ${result.newHash}\n(Use this hash as expected_hash in next write/edit)"

    fun formatEditOutput(result: EditResult): String = if (result.replaced) {
        "Edit applied: ${result.path}\nNew hash: ${result.newHash}\n(Use this hash as expected_hash in next write/edit)"
    } else {
        "EDIT FAILED: old_str not found in ${result.path}\nThe file may have changed. Use read_file again to get the latest content."
    }
}
