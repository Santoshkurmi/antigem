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

    suspend fun saveMessages(conversationId: String, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        val file = getMessagesFile(conversationId)
        file.writeText(json.encodeToString(messages))
        // Update conversation updated_at
        val conv = _conversations.value.find { it.id == conversationId }
        if (conv != null) {
            saveConversation(conv.copy(updatedAt = System.currentTimeMillis()))
        }
    }
}
