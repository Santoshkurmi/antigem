package com.example.gemini.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.FileProvider
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.domain.chat.TrajectoryEngine
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConversationExportHelper {
    private const val TAG = "ConversationExport"

    enum class ExportFormat(val extension: String, val mimeType: String, val displayName: String) {
        MARKDOWN("md", "text/markdown", "Markdown (.md)"),
        HTML("html", "text/html", "Standalone HTML (.html)")
    }

    /**
     * Exports a conversation by ID, saving it to Downloads/AntiGem/Exports and opening it.
     */
    suspend fun exportConversation(
        context: Context,
        conversationId: String,
        title: String,
        format: ExportFormat,
        activeMessages: List<ChatMessage>? = null,
        agyHubClient: AgyHubClient = AgyHubClient(),
        snackbarHostState: SnackbarHostState? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val messages: List<ChatMessage> = if (!activeMessages.isNullOrEmpty()) {
                activeMessages
            } else {
                // Fetch trajectory steps from hub and parse
                val stepsRes = agyHubClient.getCascadeTrajectorySteps(conversationId)
                if (stepsRes.isSuccess) {
                    val engine = TrajectoryEngine()
                    engine.ingestStepsDirect(stepsRes.getOrThrow(), conversationId)
                } else {
                    withContext(Dispatchers.Main) {
                        AppToastHelper.showToast("Failed to load conversation: ${stepsRes.exceptionOrNull()?.message}", ChatToastType.ERROR)
                    }
                    return@withContext
                }
            }

            if (messages.isEmpty()) {
                withContext(Dispatchers.Main) {
                    AppToastHelper.showToast("No messages to export", ChatToastType.INFO)
                }
                return@withContext
            }

            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40).ifBlank { "conversation" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${sanitizedTitle}_${timeStamp}.${format.extension}"

            val content = when (format) {
                ExportFormat.MARKDOWN -> generateMarkdown(title, conversationId, messages)
                ExportFormat.HTML -> generateHtml(title, conversationId, messages)
            }

            // Save to Downloads/AntiGem/Exports
            val (savedUri, mimeType, savedFileName) = saveToAntiGemExports(context, fileName, content, format.mimeType)

            withContext(Dispatchers.Main) {
                if (savedUri != null) {
                    if (snackbarHostState != null) {
                        val result = snackbarHostState.showSnackbar(
                            message = "$savedFileName saved to Downloads/AntiGem/Exports",
                            actionLabel = "Open",
                            duration = SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            openFile(context, savedUri, mimeType)
                        }
                    } else {
                        AppToastHelper.showToast("$savedFileName saved to Downloads/AntiGem/Exports", ChatToastType.SUCCESS)
                        openFile(context, savedUri, mimeType)
                    }
                } else {
                    AppToastHelper.showToast("Failed to save export to Downloads", ChatToastType.ERROR)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            withContext(Dispatchers.Main) {
                AppToastHelper.showToast("Export error: ${e.message}", ChatToastType.ERROR)
            }
        }
    }

    private fun openFile(context: Context, uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open Exported Conversation").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.w(TAG, "No default app to open file: ${e.message}")
            try {
                Toast.makeText(context, "No app found to open file", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private fun saveToAntiGemExports(
        context: Context,
        fileName: String,
        content: String,
        mimeType: String
    ): Triple<Uri?, String, String> {
        val bytes = content.toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AntiGem/Exports")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues) ?: return Triple(null, mimeType, fileName)

                resolver.openOutputStream(itemUri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)

                return Triple(itemUri, mimeType, fileName)
            } else {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AntiGem/Exports")
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, fileName)
                targetFile.writeBytes(bytes)

                val fileUri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                } catch (_: Exception) {
                    Uri.fromFile(targetFile)
                }
                return Triple(fileUri, mimeType, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving to AntiGem/Exports: ${e.message}", e)
            return Triple(null, mimeType, fileName)
        }
    }

    private fun generateMarkdown(title: String, conversationId: String, messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        sb.append("# ").append(title.ifBlank { "Antigravity Chat" }).append("\n\n")
        sb.append("> **Exported on:** ").append(dateStr).append("\n")
        sb.append("> **Conversation ID:** `").append(conversationId).append("`\n\n")
        sb.append("---\n\n")

        for (msg in messages) {
            when (msg.role) {
                MessageRole.USER -> {
                    sb.append("### 👤 User\n\n")
                    if (msg.attachments.isNotEmpty()) {
                        sb.append("**Attachments:**\n")
                        for (att in msg.attachments) {
                            sb.append("- 📎 ").append(att.name).append("\n")
                        }
                        sb.append("\n")
                    }
                    sb.append(msg.content.trim()).append("\n\n")
                    sb.append("---\n\n")
                }
                MessageRole.ASSISTANT -> {
                    sb.append("### 🤖 Assistant\n\n")

                    // Thinking
                    if (!msg.thoughtText.isNullOrBlank()) {
                        sb.append("<details><summary>💭 <i>Thinking Process</i></summary>\n\n")
                        sb.append(msg.thoughtText.trim()).append("\n\n")
                        sb.append("</details>\n\n")
                    }

                    // Tools
                    if (msg.toolCalls.isNotEmpty()) {
                        for (tc in msg.toolCalls) {
                            val statusBadge = if (tc.exitCode != null) " (exit ${tc.exitCode})" else ""
                            sb.append("<details><summary>🔧 <b>${tc.name}</b>: <code>${escapeMdInline(tc.command)}</code>$statusBadge</summary>\n\n")
                            if (tc.output.isNotBlank()) {
                                sb.append("```\n").append(tc.output.trim()).append("\n```\n\n")
                            }
                            sb.append("</details>\n\n")
                        }
                    }

                    // Content
                    if (msg.content.isNotBlank()) {
                        sb.append(msg.content.trim()).append("\n\n")
                    }
                    sb.append("---\n\n")
                }
                MessageRole.SYSTEM -> {
                    sb.append("> ⚙️ **System:** ").append(msg.content.trim()).append("\n\n---\n\n")
                }
                else -> {
                    if (msg.content.isNotBlank()) {
                        sb.append(msg.content.trim()).append("\n\n---\n\n")
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun generateHtml(title: String, conversationId: String, messages: List<ChatMessage>): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val htmlTitle = escapeHtml(title.ifBlank { "Antigravity Chat" })

        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n")
        sb.append("<html lang=\"en\">\n<head>\n")
        sb.append("  <meta charset=\"UTF-8\">\n")
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        sb.append("  <title>").append(htmlTitle).append("</title>\n")
        sb.append("  <style>\n")
        sb.append("""
    :root {
      --bg: #0B0C10;
      --card-bg: #141721;
      --card-border: #232738;
      --user-bg: #1C2030;
      --user-border: #333952;
      --accent-terracotta: #CC785C;
      --accent-blue: #8AB4F8;
      --text-main: #E2E8F0;
      --text-muted: #94A3B8;
      --tool-bg: #0E1017;
      --code-bg: #08090C;
      --badge-green: #10B981;
      --badge-red: #EF4444;
      --badge-amber: #F59E0B;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background-color: var(--bg);
      color: var(--text-main);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      line-height: 1.6;
      padding: 24px 16px 64px 16px;
    }
    .container {
      max-width: 860px;
      margin: 0 auto;
    }
    header {
      padding-bottom: 20px;
      margin-bottom: 28px;
      border-bottom: 1px solid var(--card-border);
    }
    h1 {
      font-size: 1.65rem;
      font-weight: 700;
      color: #FFFFFF;
      margin-bottom: 8px;
    }
    .meta-bar {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      font-size: 0.85rem;
      color: var(--text-muted);
    }
    .meta-tag {
      background: var(--card-bg);
      padding: 3px 8px;
      border-radius: 6px;
      border: 1px solid var(--card-border);
      font-family: monospace;
    }
    .chat-flow {
      display: flex;
      flex-direction: column;
      gap: 24px;
    }
    .turn {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .turn-user {
      align-items: flex-end;
    }
    .turn-assistant {
      align-items: flex-start;
    }
    .bubble-user {
      background: var(--user-bg);
      border: 1px solid var(--user-border);
      border-radius: 16px 16px 4px 16px;
      padding: 14px 18px;
      max-width: 85%;
      color: #FFFFFF;
      font-size: 0.95rem;
      white-space: pre-wrap;
      word-break: break-word;
      box-shadow: 0 4px 12px rgba(0,0,0,0.25);
    }
    .bubble-assistant {
      background: var(--card-bg);
      border: 1px solid var(--card-border);
      border-radius: 16px 16px 16px 4px;
      padding: 18px 20px;
      width: 100%;
      font-size: 0.95rem;
      box-shadow: 0 4px 14px rgba(0,0,0,0.25);
    }
    .role-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 0.8rem;
      font-weight: 600;
      margin-bottom: 8px;
      color: var(--text-muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    .role-badge.user-badge { color: var(--accent-terracotta); }
    .role-badge.assistant-badge { color: var(--accent-blue); }

    /* Thinking Process Accordion */
    details.thinking-box {
      background: rgba(138, 180, 248, 0.05);
      border: 1px solid rgba(138, 180, 248, 0.18);
      border-radius: 10px;
      padding: 10px 14px;
      margin: 10px 0;
      font-size: 0.88rem;
      color: #CBD5E1;
    }
    details.thinking-box summary {
      cursor: pointer;
      font-weight: 600;
      color: var(--accent-blue);
      user-select: none;
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .thinking-content {
      margin-top: 10px;
      white-space: pre-wrap;
      font-family: monospace;
      font-size: 0.82rem;
      line-height: 1.5;
      color: #94A3B8;
      border-top: 1px dashed rgba(138, 180, 248, 0.15);
      padding-top: 8px;
    }

    /* Tool Call Card */
    details.tool-card {
      background: var(--tool-bg);
      border: 1px solid var(--card-border);
      border-radius: 10px;
      padding: 8px 14px;
      margin: 10px 0;
      font-size: 0.88rem;
    }
    details.tool-card summary {
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      user-select: none;
    }
    .tool-header-left {
      display: flex;
      align-items: center;
      gap: 8px;
      font-family: monospace;
    }
    .tool-name {
      font-weight: 700;
      color: #FFFFFF;
    }
    .tool-cmd {
      color: var(--text-muted);
      font-size: 0.82rem;
      max-width: 480px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .tool-badge {
      font-size: 0.72rem;
      font-weight: bold;
      padding: 2px 6px;
      border-radius: 4px;
      font-family: monospace;
    }
    .badge-success { background: rgba(16,185,129,0.15); color: var(--badge-green); }
    .badge-failed { background: rgba(239,68,68,0.15); color: var(--badge-red); }
    .tool-output {
      margin-top: 10px;
      background: var(--code-bg);
      border-radius: 6px;
      padding: 10px 12px;
      font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, Courier, monospace;
      font-size: 0.82rem;
      white-space: pre-wrap;
      word-break: break-all;
      color: #A7F3D0;
      max-height: 380px;
      overflow-y: auto;
      border: 1px solid rgba(255,255,255,0.05);
    }

    /* Markdown styling inside content */
    .rendered-content p { margin: 10px 0; }
    .rendered-content h1, .rendered-content h2, .rendered-content h3 { margin: 16px 0 8px 0; color: #FFFFFF; }
    .rendered-content ul, .rendered-content ol { margin: 8px 0 8px 24px; }
    .rendered-content li { margin: 4px 0; }
    .rendered-content pre {
      background: var(--code-bg);
      border: 1px solid var(--card-border);
      border-radius: 8px;
      padding: 12px 14px;
      overflow-x: auto;
      margin: 12px 0;
      font-family: "SFMono-Regular", Consolas, monospace;
      font-size: 0.85rem;
      color: #F8FAFC;
    }
    .rendered-content code:not(pre code) {
      background: rgba(255,255,255,0.08);
      padding: 2px 6px;
      border-radius: 4px;
      font-family: monospace;
      font-size: 0.88em;
      color: #FCA5A5;
    }
    .rendered-content table {
      border-collapse: collapse;
      width: 100%;
      margin: 14px 0;
      font-size: 0.88rem;
    }
    .rendered-content th, .rendered-content td {
      border: 1px solid var(--card-border);
      padding: 8px 12px;
      text-align: left;
    }
    .rendered-content th { background: rgba(255,255,255,0.04); font-weight: 600; color: #FFFFFF; }
    .rendered-content blockquote {
      border-left: 3px solid var(--accent-blue);
      padding-left: 12px;
      margin: 10px 0;
      color: var(--text-muted);
    }
    .attachment-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: rgba(204, 120, 92, 0.15);
      border: 1px solid rgba(204, 120, 92, 0.3);
      padding: 4px 10px;
      border-radius: 8px;
      font-size: 0.82rem;
      margin-bottom: 8px;
      color: #FFD4C4;
    }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <h1>$htmlTitle</h1>
      <div class="meta-bar">
        <span>📅 Exported: <b>$dateStr</b></span>
        <span class="meta-tag">ID: $conversationId</span>
      </div>
    </header>

    <div class="chat-flow">
""")

        for (msg in messages) {
            when (msg.role) {
                MessageRole.USER -> {
                    sb.append("      <div class=\"turn turn-user\">\n")
                    sb.append("        <div class=\"role-badge user-badge\">👤 User</div>\n")
                    sb.append("        <div class=\"bubble-user\">\n")
                    if (msg.attachments.isNotEmpty()) {
                        for (att in msg.attachments) {
                            sb.append("          <div class=\"attachment-pill\">📎 ").append(escapeHtml(att.name)).append("</div>\n")
                        }
                    }
                    sb.append("          ").append(escapeHtml(msg.content.trim())).append("\n")
                    sb.append("        </div>\n")
                    sb.append("      </div>\n")
                }
                MessageRole.ASSISTANT -> {
                    sb.append("      <div class=\"turn turn-assistant\">\n")
                    sb.append("        <div class=\"role-badge assistant-badge\">🤖 Assistant</div>\n")
                    sb.append("        <div class=\"bubble-assistant\">\n")

                    // Thinking
                    if (!msg.thoughtText.isNullOrBlank()) {
                        sb.append("          <details class=\"thinking-box\">\n")
                        sb.append("            <summary>💭 Model Thinking</summary>\n")
                        sb.append("            <div class=\"thinking-content\">").append(escapeHtml(msg.thoughtText.trim())).append("</div>\n")
                        sb.append("          </details>\n")
                    }

                    // Tools
                    if (msg.toolCalls.isNotEmpty()) {
                        for (tc in msg.toolCalls) {
                            renderHtmlToolCall(sb, tc)
                        }
                    }

                    // Content
                    if (msg.content.isNotBlank()) {
                        sb.append("          <div class=\"rendered-content\">").append(simpleMarkdownToHtml(msg.content.trim())).append("</div>\n")
                    }

                    sb.append("        </div>\n")
                    sb.append("      </div>\n")
                }
                MessageRole.SYSTEM -> {
                    sb.append("      <div class=\"turn\">\n")
                    sb.append("        <div class=\"role-badge\">⚙️ System</div>\n")
                    sb.append("        <div class=\"bubble-assistant\" style=\"border-left: 3px solid #F59E0B;\">\n")
                    sb.append("          <div class=\"rendered-content\">").append(escapeHtml(msg.content.trim())).append("</div>\n")
                    sb.append("        </div>\n")
                    sb.append("      </div>\n")
                }
                else -> {
                    if (msg.content.isNotBlank()) {
                        sb.append("      <div class=\"turn\">\n")
                        sb.append("        <div class=\"bubble-assistant\">\n")
                        sb.append("          <div class=\"rendered-content\">").append(escapeHtml(msg.content.trim())).append("</div>\n")
                        sb.append("        </div>\n")
                        sb.append("      </div>\n")
                    }
                }
            }
        }

        sb.append("    </div>\n")
        sb.append("  </div>\n")
        sb.append("</body>\n")
        sb.append("</html>\n")
        return sb.toString()
    }

    private fun renderHtmlToolCall(sb: StringBuilder, tc: ToolCall) {
        val isSuccess = tc.exitCode == 0 || (tc.exitCode == null && tc.status == "SUCCESS")
        val badgeClass = if (isSuccess) "badge-success" else "badge-failed"
        val badgeText = if (tc.exitCode != null) "exit ${tc.exitCode}" else (tc.status.ifBlank { "DONE" })

        sb.append("          <details class=\"tool-card\">\n")
        sb.append("            <summary>\n")
        sb.append("              <div class=\"tool-header-left\">\n")
        sb.append("                <span>🔧</span>\n")
        sb.append("                <span class=\"tool-name\">").append(escapeHtml(tc.name)).append("</span>\n")
        sb.append("                <span class=\"tool-cmd\">").append(escapeHtml(tc.command)).append("</span>\n")
        sb.append("              </div>\n")
        sb.append("              <span class=\"tool-badge ").append(badgeClass).append("\">").append(badgeText).append("</span>\n")
        sb.append("            </summary>\n")
        if (tc.output.isNotBlank()) {
            sb.append("            <pre class=\"tool-output\">").append(escapeHtml(tc.output.trim())).append("</pre>\n")
        }
        sb.append("          </details>\n")
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun escapeMdInline(text: String): String {
        return text.replace("`", "\\`").replace("\n", " ")
    }

    private fun simpleMarkdownToHtml(text: String): String {
        var out = escapeHtml(text)

        // Triple backtick code blocks
        val codeBlockRegex = Regex("```([a-zA-Z0-9_-]*)\\n([\\s\\S]*?)```")
        out = codeBlockRegex.replace(out) { match ->
            val lang = match.groupValues[1]
            val code = match.groupValues[2]
            "<pre><code class=\"language-$lang\">$code</code></pre>"
        }

        // Inline code
        out = Regex("`([^`]+)`").replace(out, "<code>$1</code>")

        // Headers
        out = Regex("(?m)^### (.*?)$").replace(out, "<h3>$1</h3>")
        out = Regex("(?m)^## (.*?)$").replace(out, "<h2>$1</h2>")
        out = Regex("(?m)^# (.*?)$").replace(out, "<h1>$1</h1>")

        // Bold & Italic
        out = Regex("\\*\\*([^*]+)\\*\\*").replace(out, "<b>$1</b>")
        out = Regex("\\*([^*]+)\\*").replace(out, "<i>$1</i>")

        // Lists
        out = Regex("(?m)^[-*] (.*?)$").replace(out, "<li>$1</li>")

        // Line breaks (except inside pre)
        val parts = out.split("<pre>", "</pre>")
        val newParts = mutableListOf<String>()
        for (i in parts.indices) {
            if (i % 2 == 0) {
                newParts.add(parts[i].replace("\n", "<br>\n"))
            } else {
                newParts.add("<pre>" + parts[i] + "</pre>")
            }
        }
        return newParts.joinToString("")
    }
}
