package com.example.gemini.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.FileProvider
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.domain.chat.TrajectoryEngine
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import exa.language_server_pb.Step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SharedConversationData(
    val title: String,
    val conversationId: String,
    val exportedAt: String,
    val modelId: String = "gemini",
    val messages: List<ChatMessage>,
    val rawSteps: List<Step> = emptyList()
)

object ConversationShareHelper {
    private const val TAG = "ConversationShare"

    suspend fun shareConversation(
        context: Context,
        conversationId: String,
        title: String,
        activeMessages: List<ChatMessage>? = null,
        agyHubClient: AgyHubClient = AgyHubClient(),
        snackbarHostState: SnackbarHostState? = null
    ) = withContext(Dispatchers.IO) {
        try {
            var rawSteps: List<Step>? = null
            var messages: List<ChatMessage> = if (!activeMessages.isNullOrEmpty()) {
                activeMessages
            } else {
                val stepsRes = agyHubClient.getCascadeTrajectorySteps(conversationId)
                if (stepsRes.isSuccess) {
                    val steps = stepsRes.getOrThrow()
                    rawSteps = steps
                    val engine = TrajectoryEngine()
                    engine.ingestStepsDirect(steps, conversationId)
                } else {
                    withContext(Dispatchers.Main) {
                        AppToastHelper.showToast("Failed to load conversation: ${stepsRes.exceptionOrNull()?.message}", ChatToastType.ERROR)
                    }
                    return@withContext
                }
            }

            if (messages.isEmpty()) {
                withContext(Dispatchers.Main) {
                    AppToastHelper.showToast("No messages to share", ChatToastType.INFO)
                }
                return@withContext
            }

            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40).ifBlank { "conversation" }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "${sanitizedTitle}_${timeStamp}.jsonl.antigem"

            val content = generateAntigemJsonl(title, conversationId, rawSteps, messages)
            val bytes = content.toByteArray(Charsets.UTF_8)

            // 1. Auto-save to Downloads/AntiGem/Exports (Same as Markdown & HTML exports)
            val savedFileName = saveToDownloadsExports(context, fileName, bytes)

            // 2. Save a copy to cache for sharing via FileProvider
            val cacheDir = File(context.cacheDir, "shared_chats")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val shareFile = File(cacheDir, fileName)
            shareFile.writeBytes(bytes)

            val shareUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
            } catch (e: Exception) {
                Log.e(TAG, "FileProvider error: ${e.message}", e)
                Uri.fromFile(shareFile)
            }

            withContext(Dispatchers.Main) {
                // Immediately launch share sheet so user doesn't wait
                launchShareChooser(context, shareUri, title, fileName)

                if (snackbarHostState != null) {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        snackbarHostState.showSnackbar(
                            message = "$savedFileName saved to Downloads/AntiGem/Exports",
                            duration = SnackbarDuration.Short
                        )
                    }
                } else {
                    AppToastHelper.showToast("$savedFileName saved to Downloads/AntiGem/Exports", ChatToastType.SUCCESS)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: ${e.message}", e)
            withContext(Dispatchers.Main) {
                AppToastHelper.showToast("Share error: ${e.message}", ChatToastType.ERROR)
            }
        }
    }

    private fun launchShareChooser(context: Context, uri: Uri, title: String, fileName: String) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$title (AntiGem Trajectory)")
                putExtra(Intent.EXTRA_TEXT, "AntiGem Chat Trajectory: $title")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Share AntiGem Chat").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch share chooser: ${e.message}")
            AppToastHelper.showToast("Could not open share chooser", ChatToastType.ERROR)
        }
    }

    private fun saveToDownloadsExports(context: Context, fileName: String, bytes: ByteArray): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AntiGem/Exports")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        out.write(bytes)
                        out.flush()
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)
                }
            } else {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AntiGem/Exports")
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, fileName)
                targetFile.writeBytes(bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving to Downloads/AntiGem/Exports: ${e.message}", e)
        }
        return fileName
    }

    private fun generateAntigemJsonl(
        title: String,
        conversationId: String,
        rawSteps: List<Step>?,
        messages: List<ChatMessage>
    ): String {
        val sb = StringBuilder()
        val dateStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

        // Header Line
        val header = JSONObject().apply {
            put("version", 1)
            put("format", "antigem_trajectory")
            put("title", title)
            put("conversation_id", conversationId)
            put("exported_at", dateStr)
            put("step_count", rawSteps?.size ?: messages.size)
        }
        sb.append(header.toString()).append("\n")

        if (!rawSteps.isNullOrEmpty()) {
            rawSteps.forEachIndexed { idx, step ->
                val encodedBytes = Step.ADAPTER.encode(step)
                val base64 = Base64.encodeToString(encodedBytes, Base64.NO_WRAP)
                val lineObj = JSONObject().apply {
                    put("type", "proto_step")
                    put("index", idx)
                    put("data", base64)
                }
                sb.append(lineObj.toString()).append("\n")
            }
        } else {
            for (msg in messages) {
                val lineObj = JSONObject().apply {
                    put("type", "chat_message")
                    put("id", msg.id)
                    put("role", msg.role.name)
                    put("content", msg.content)
                    put("createdAt", msg.createdAt)
                }
                sb.append(lineObj.toString()).append("\n")
            }
        }

        return sb.toString()
    }

    /**
     * Parses an incoming .antigem or .jsonl stream into SharedConversationData.
     */
    fun parseSharedConversation(inputStream: InputStream): SharedConversationData {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        var title = "Shared Conversation"
        var conversationId = "shared_${System.currentTimeMillis()}"
        var exportedAt = ""
        val steps = mutableListOf<Step>()
        val fallbackMessages = mutableListOf<ChatMessage>()

        reader.useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                try {
                    val json = JSONObject(trimmed)
                    if (json.optString("format") == "antigem_trajectory" || json.has("version")) {
                        title = json.optString("title", title)
                        conversationId = json.optString("conversation_id", conversationId)
                        exportedAt = json.optString("exported_at", "")
                        continue
                    }

                    val type = json.optString("type")
                    if (type == "proto_step" && json.has("data")) {
                        val base64 = json.getString("data")
                        val bytes = Base64.decode(base64, Base64.NO_WRAP)
                        val step = Step.ADAPTER.decode(bytes)
                        steps.add(step)
                    } else if (type == "chat_message" || json.has("role")) {
                        val id = json.optString("id", "msg_${System.currentTimeMillis()}_${fallbackMessages.size}")
                        val roleStr = json.optString("role", "ASSISTANT")
                        val role = try { MessageRole.valueOf(roleStr) } catch (_: Exception) { MessageRole.ASSISTANT }
                        val content = json.optString("content", json.optString("text", ""))
                        val createdAt = json.optLong("createdAt", json.optLong("timestamp", System.currentTimeMillis()))
                        fallbackMessages.add(
                            ChatMessage(
                                id = id,
                                conversationId = conversationId,
                                role = role,
                                content = content,
                                createdAt = createdAt
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing jsonl line: ${e.message}")
                }
            }
        }

        val messages = if (steps.isNotEmpty()) {
            val engine = TrajectoryEngine()
            engine.ingestStepsDirect(steps, conversationId)
        } else {
            fallbackMessages
        }

        return SharedConversationData(
            title = title,
            conversationId = conversationId,
            exportedAt = exportedAt,
            messages = messages,
            rawSteps = steps
        )
    }
}
