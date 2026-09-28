package com.example.gemini.data.remote.services

import com.example.gemini.data.preferences.AuthPreferences
import exa.language_server_pb.EndAudioSessionRequest
import exa.language_server_pb.GetTranscriptionRequest
import exa.language_server_pb.Metadata
import exa.language_server_pb.SendAudioChunkRequest
import exa.language_server_pb.StartAudioTranscriptionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okio.ByteString
import okio.ByteString.Companion.decodeBase64

/**
 * Dedicated RPC service for speech-to-text audio transcription and audio chunk streaming.
 * Uses typed Square Wire AgyLanguageService gRPC client.
 */
class AgyAudioService {
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
        val bytes = audioBase64.decodeBase64() ?: ByteString.EMPTY
        val req = GetTranscriptionRequest(
            audio_data = bytes,
            metadata = Metadata()
        )
        AgyLanguageService.GetTranscription().executeSafely(req).map { res ->
            res.transcribed_text
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
        val req = StartAudioTranscriptionRequest(
            cascade_id = cascadeId,
            pre_cursor_text = preCursorText,
            post_cursor_text = postCursorText,
            mime_type = mimeType
        )
        return AgyLanguageService.StreamAudioTranscription().asFlowSafely(req).mapNotNull { res ->
            res.transcription?.text?.takeIf { it.isNotBlank() }
        }
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
        val bytes = dataBase64.decodeBase64() ?: ByteString.EMPTY
        val req = SendAudioChunkRequest(
            session_id = sessionId,
            data_ = bytes,
            sequence_number = sequenceNumber.toInt()
        )
        AgyLanguageService.SendAudioChunk().executeSafely(req).map { }
    }

    /**
     * Ends the audio transcription session.
     */
    suspend fun endAudioSession(
        sessionId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val req = EndAudioSessionRequest(session_id = sessionId)
        AgyLanguageService.EndAudioSession().executeSafely(req).map { }
    }
}
