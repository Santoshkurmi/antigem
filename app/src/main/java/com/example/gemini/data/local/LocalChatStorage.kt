package com.example.gemini.data.local

import android.content.Context
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LocalChatStorage(private val context: Context) {

    // Pure in-RAM state for conversations and active messages while app is running
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun init() = withContext(Dispatchers.IO) {
        // Clean up legacy cache files if any existed to prevent stale disk state
        try {
            val convFile = File(context.filesDir, "conversations.json")
            if (convFile.exists()) convFile.delete()
            val msgDir = File(context.filesDir, "messages")
            if (msgDir.exists()) msgDir.deleteRecursively()
        } catch (e: Exception) {}
    }

    suspend fun setConversations(list: List<Conversation>) = withContext(Dispatchers.Default) {
        val currentMap = _conversations.value.associateBy { it.id }.toMutableMap()
        for (c in list) {
            currentMap[c.id] = c
        }
        _conversations.value = currentMap.values.toList().sortedByDescending { it.updatedAt }
    }

    suspend fun loadConversations(): List<Conversation> = withContext(Dispatchers.Default) {
        _conversations.value
    }

    suspend fun saveConversation(conversation: Conversation) = withContext(Dispatchers.Default) {
        val current = _conversations.value.toMutableList()
        val index = current.indexOfFirst { it.id == conversation.id }
        if (index >= 0) {
            current[index] = conversation
        } else {
            current.add(0, conversation)
        }
        _conversations.value = current.distinctBy { it.id }.sortedByDescending { it.updatedAt }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.Default) {
        _conversations.value = _conversations.value.filter { it.id != conversationId }
    }

    suspend fun getMessages(conversationId: String): List<ChatMessage> = withContext(Dispatchers.Default) {
        // No caching of chat messages - all messages are loaded directly from the daemon API
        emptyList()
    }

    suspend fun saveMessages(
        conversationId: String,
        messages: List<ChatMessage>,
        touchTimestamp: Boolean = false
    ) = withContext(Dispatchers.Default) {
        // No caching of chat messages - all messages are loaded directly from the daemon API
    }

    suspend fun updateConversationId(oldId: String, newId: String) = withContext(Dispatchers.Default) {
        if (oldId == newId) return@withContext
        val current = _conversations.value.toMutableList()
        val index = current.indexOfFirst { it.id == oldId }
        if (index >= 0) {
            val conv = current[index]
            current[index] = conv.copy(id = newId)
            _conversations.value = current.distinctBy { it.id }.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun mergeAgyConversations(agyList: List<com.example.gemini.data.remote.AgyConversationSummary>) = withContext(Dispatchers.Default) {
        val convList = agyList.map { agy ->
            val agyTime = parseIsoTimestamp(agy.createdAt)
            Conversation(
                id = agy.id,
                title = if (agy.title.isNotBlank()) agy.title else "Antigravity Chat",
                modelId = "gemini-3.7-flash-high",
                sessionId = agy.id,
                createdAt = agyTime,
                updatedAt = agyTime
            )
        }
        setConversations(convList)
    }

    private fun parseIsoTimestamp(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return System.currentTimeMillis()
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val clean = isoString.substringBefore(".").removeSuffix("Z")
            sdf.parse(clean)?.time ?: System.currentTimeMillis()
        } catch (e: Throwable) {
            System.currentTimeMillis()
        }
    }
}
