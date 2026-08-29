package com.example.gemini.data.local

import android.content.Context
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LocalChatStorage(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val conversationsFile: File
        get() = File(context.filesDir, "conversations.json")

    private fun getMessagesFile(conversationId: String): File {
        val dir = File(context.filesDir, "messages")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$conversationId.json")
    }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun init() {
        loadConversations()
    }

    suspend fun loadConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        try {
            if (conversationsFile.exists()) {
                val text = conversationsFile.readText()
                val list: List<Conversation> = json.decodeFromString(text)
                val sorted = list.sortedByDescending { it.updatedAt }
                _conversations.value = sorted
                return@withContext sorted
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        emptyList()
    }

    suspend fun saveConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val current = _conversations.value.toMutableList()
        val index = current.indexOfFirst { it.id == conversation.id }
        if (index >= 0) {
            current[index] = conversation
        } else {
            current.add(0, conversation)
        }
        val sorted = current.sortedByDescending { it.updatedAt }
        _conversations.value = sorted
        conversationsFile.writeText(json.encodeToString(sorted))
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        val current = _conversations.value.filter { it.id != conversationId }
        _conversations.value = current
        conversationsFile.writeText(json.encodeToString(current))
        val msgFile = getMessagesFile(conversationId)
        if (msgFile.exists()) {
            msgFile.delete()
        }
    }

    suspend fun getMessages(conversationId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val file = getMessagesFile(conversationId)
        if (file.exists()) {
            try {
                return@withContext json.decodeFromString<List<ChatMessage>>(file.readText())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        emptyList()
    }

    suspend fun saveMessages(
        conversationId: String,
        messages: List<ChatMessage>,
        touchTimestamp: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val file = getMessagesFile(conversationId)
        file.writeText(json.encodeToString(messages))
        if (touchTimestamp) {
            val conv = _conversations.value.find { it.id == conversationId }
            if (conv != null) {
                saveConversation(conv.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun updateConversationId(oldId: String, newId: String) = withContext(Dispatchers.IO) {
        if (oldId == newId) return@withContext
        val current = _conversations.value.toMutableList()
        val index = current.indexOfFirst { it.id == oldId }
        if (index >= 0) {
            val conv = current[index]
            current[index] = conv.copy(id = newId)
            val sorted = current.sortedByDescending { it.updatedAt }
            _conversations.value = sorted
            conversationsFile.writeText(json.encodeToString(sorted))
        }

        val oldMsgFile = getMessagesFile(oldId)
        val newMsgFile = getMessagesFile(newId)
        if (oldMsgFile.exists()) {
            try {
                val msgsText = oldMsgFile.readText()
                val msgs: List<ChatMessage> = json.decodeFromString(msgsText)
                val updatedMsgs = msgs.map { it.copy(conversationId = newId) }
                newMsgFile.writeText(json.encodeToString(updatedMsgs))
                oldMsgFile.delete()
            } catch (_: Exception) {
                oldMsgFile.renameTo(newMsgFile)
            }
        }
    }

    suspend fun mergeAgyConversations(agyList: List<com.example.gemini.data.remote.AgyConversationSummary>) = withContext(Dispatchers.IO) {
        val current = _conversations.value.toMutableList()
        var modified = false

        for (agy in agyList) {
            val existingIndex = current.indexOfFirst { it.id == agy.id }
            val agyTime = try {
                java.time.Instant.parse(agy.createdAt).toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }

            if (existingIndex >= 0) {
                val existing = current[existingIndex]
                val isGeneric = existing.title == "New Chat" || existing.title == "Antigravity Chat"
                val newTitle = if (isGeneric && agy.title.isNotBlank() && agy.title != "New Chat") agy.title else existing.title
                if (existing.title != newTitle) {
                    current[existingIndex] = existing.copy(title = newTitle)
                    modified = true
                }
            } else {
                // Check if there is an existing local conversation with the same non-generic title to prevent duplicate rows
                val matchingTitleIndex = current.indexOfFirst {
                    it.id != agy.id && it.title.equals(agy.title, ignoreCase = true) && agy.title != "New Chat" && agy.title != "Antigravity Chat"
                }

                if (matchingTitleIndex >= 0) {
                    val oldConv = current[matchingTitleIndex]
                    current[matchingTitleIndex] = oldConv.copy(id = agy.id)
                    val oldMsgFile = getMessagesFile(oldConv.id)
                    val newMsgFile = getMessagesFile(agy.id)
                    if (oldMsgFile.exists() && !newMsgFile.exists()) {
                        oldMsgFile.renameTo(newMsgFile)
                    } else if (oldMsgFile.exists()) {
                        oldMsgFile.delete()
                    }
                    modified = true
                } else {
                    val newConv = Conversation(
                        id = agy.id,
                        title = if (agy.title.isNotBlank()) agy.title else "Antigravity Chat",
                        modelId = "gemini-3.7-flash-high",
                        sessionId = java.util.UUID.randomUUID().toString(),
                        createdAt = agyTime,
                        updatedAt = agyTime
                    )
                    current.add(newConv)
                    modified = true
                }
            }
        }

        if (modified) {
            val deduplicated = current.distinctBy { it.id }
            val sorted = deduplicated.sortedByDescending { it.updatedAt }
            _conversations.value = sorted
            conversationsFile.writeText(json.encodeToString(sorted))
        }
    }
}
