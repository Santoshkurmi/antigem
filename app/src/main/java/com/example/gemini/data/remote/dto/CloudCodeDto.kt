package com.example.gemini.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class CloudCodeRequest(
    val project: String,
    val model: String,
    val requestType: String = "agent",
    val requestId: String,
    val userAgent: String = "antigravity",
    val request: CloudCodeInnerRequest
)

@Serializable
data class CloudCodeInnerRequest(
    val sessionId: String,
    val contents: List<ContentPartDto>,
    val systemInstruction: SystemInstructionDto? = null,
    val generationConfig: JsonObject? = null
)

@Serializable
data class SystemInstructionDto(
    val role: String = "user",
    val parts: List<TextPartDto>
)

@Serializable
data class ContentPartDto(
    val role: String,
    val parts: List<TextPartDto>
)

@Serializable
data class TextPartDto(
    val text: String? = null,
    val thought: Boolean? = null,
    val thoughtSignature: String? = null
)

// LoadCodeAssist Response
@Serializable
data class LoadCodeAssistResponse(
    val cloudaicompanionProject: JsonElement? = null,
    val currentTier: TierDto? = null,
    val paidTier: TierDto? = null,
    val allowedTiers: List<TierDto>? = null
)

@Serializable
data class TierDto(
    val id: String? = null,
    val name: String? = null,
    val isDefault: Boolean? = null
)

// FetchAvailableModels Response
@Serializable
data class FetchAvailableModelsResponse(
    val models: Map<String, ModelDetailDto> = emptyMap()
)

@Serializable
data class ModelDetailDto(
    val displayName: String? = null,
    val quotaInfo: QuotaInfoDto? = null
)

@Serializable
data class QuotaInfoDto(
    val remainingFraction: Float? = null,
    val resetTime: String? = null
)

// Stream Chunk
@Serializable
data class StreamCandidateChunk(
    val response: InnerResponseDto? = null,
    val candidates: List<CandidateDto>? = null,
    val usageMetadata: UsageMetadataDto? = null
) {
    val activeCandidates: List<CandidateDto>?
        get() = response?.candidates ?: candidates

    val activeUsage: UsageMetadataDto?
        get() = response?.usageMetadata ?: usageMetadata
}

@Serializable
data class InnerResponseDto(
    val candidates: List<CandidateDto>? = null,
    val usageMetadata: UsageMetadataDto? = null
)

@Serializable
data class CandidateDto(
    val content: ContentDto? = null,
    val finishReason: String? = null
)

@Serializable
data class ContentDto(
    val role: String? = null,
    val parts: List<TextPartDto>? = null
)

@Serializable
data class UsageMetadataDto(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val totalTokenCount: Int? = null,
    val cachedContentTokenCount: Int? = null,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null
)

