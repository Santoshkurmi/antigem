package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.core.AgyGrpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Dedicated RPC service for speech-to-text audio transcription and audio chunk streaming.
 */
class AgyAudioService(
    private val grpcClient: AgyGrpcClient = AgyGrpcClient.instance
) {
    companion object {
        private const val TAG = "AgyAudioService"
        val instance by lazy { AgyAudioService() }
    }

    /**
     * Speech-to-text audio transcription service via daemon GetTranscription RPC
     */
    suspend fun getTranscription(
        audioBase64: String,
        prompt: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        val token = grpcClient.csrfManager.getCsrfToken(hubUrl)
        val base = hubUrl.trimEnd('/')
        val url = "$base/exa.language_server_pb.LanguageServerService/GetTranscription"
        val payload = JSONObject().apply {
            put("audioData", audioBase64)
            put("audioBase64", audioBase64)
            if (prompt.isNotBlank()) put("prompt", prompt)
        }.toString()

        // 1. Try standard Connect-RPC JSON first
        try {
            val jsonReq = Request.Builder()
                .url(url)
                .post(payload.toRequestBody(AgyGrpcClient.JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("Connect-Protocol-Version", "1")
                .apply {
                    if (token.isNotBlank()) header("x-codeium-csrf-token", token)
                }
                .build()

            grpcClient.okHttpClient.newCall(jsonReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: ""
                    if (bodyStr.isNotBlank()) {
                        val json = JSONObject(bodyStr)
                        val text = json.optString("transcribedText").ifBlank { json.optString("text", "") }
                        if (text.isNotBlank()) {
                            return@withContext Result.success(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connect-RPC GetTranscription failed: ${e.message}, falling back to gRPC-Web")
        }

        // 2. Fallback to gRPC-Web framed call
        grpcClient.executeGrpcWebCall("GetTranscription", payload, hubUrl).map { res ->
            val firstFrameStr = res.frames.firstOrNull() ?: "{}"
            val firstJson = try { JSONObject(firstFrameStr) } catch (_: Exception) { JSONObject() }
            firstJson.optString("transcribedText").ifBlank { firstJson.optString("text", "") }
        }
    }

    /**
     * Live streaming audio transcription RPC.
     */
    fun streamAudioTranscription(
        cascadeId: String = "",
        preCursorText: String = "",
        postCursorText: String = "",
        mimeType: String = "audio/pcm;rate=16000",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Flow<String> {
        val payload = JSONObject().apply {
            put("mimeType", mimeType)
            put("cascadeId", cascadeId)
            put("preCursorText", preCursorText)
            put("postCursorText", postCursorText)
        }.toString()
        return grpcClient.callStream("StreamAudioTranscription", payload, hubUrl)
    }

    /**
     * Sends an audio PCM chunk to the active audio transcription session.
     */
    suspend fun sendAudioChunk(
        sessionId: String,
        dataBase64: String,
        sequenceNumber: Long,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
            put("data", dataBase64)
            put("sequenceNumber", sequenceNumber)
        }.toString()
        grpcClient.callUnary("SendAudioChunk", payload, hubUrl).map { }
    }

    /**
     * Ends the audio transcription session.
     */
    suspend fun endAudioSession(
        sessionId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("sessionId", sessionId)
        }.toString()
        grpcClient.callUnary("EndAudioSession", payload, hubUrl).map { }
    }
}

