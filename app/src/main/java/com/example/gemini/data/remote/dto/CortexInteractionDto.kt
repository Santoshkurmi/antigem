package com.example.gemini.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Request payload for /exa.language_server_pb.LanguageServerService/HandleCascadeUserInteraction
 * Verified directly against recorded live traffic logs on port 1235/8091.
 */
@Serializable
data class HandleCascadeUserInteractionRequestDto(
    val cascadeId: String,
    val interaction: CascadeInteractionPayloadDto
)

/**
 * Request payload for /exa.language_server_pb.LanguageServerService/CancelCascadeSteps
 */
@Serializable
data class CancelCascadeStepsRequestDto(
    val cascadeId: String = "",
    val stepIndices: List<Int> = emptyList()
)

@Serializable
data class CascadeInteractionPayloadDto(
    val trajectoryId: String = "",
    val stepIndex: Int,
    val permission: PermissionResolutionDto? = null,
    val questionnaire: QuestionnaireResolutionDto? = null,
    val askQuestion: AskQuestionInteractionDto? = null
)

@Serializable
data class AskQuestionInteractionDto(
    val responses: List<AskQuestionResponseItemDto> = emptyList()
)

@Serializable
data class AskQuestionResponseItemDto(
    val question: String,
    val options: List<AskQuestionOptionDto> = emptyList(),
    val selectedOptionIds: List<String>? = null,
    val isMultiSelect: Boolean? = null,
    val writeInResponse: String? = null,
    val skipped: Boolean? = null
)

@Serializable(with = FlexibleAskQuestionOptionSerializer::class)
data class AskQuestionOptionDto(
    val id: String = "",
    val text: String = "",
    val label: String = "",
    val description: String = ""
)

object FlexibleAskQuestionOptionSerializer : kotlinx.serialization.KSerializer<AskQuestionOptionDto> {
    override val descriptor: kotlinx.serialization.descriptors.SerialDescriptor =
        kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("AskQuestionOptionDto", kotlinx.serialization.descriptors.PrimitiveKind.STRING)

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: AskQuestionOptionDto) {
        val jsonEncoder = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw kotlinx.serialization.SerializationException("Only JSON is supported")
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(value.id))
            put("text", kotlinx.serialization.json.JsonPrimitive(value.text.ifBlank { value.label }))
            if (value.description.isNotBlank()) {
                put("description", kotlinx.serialization.json.JsonPrimitive(value.description))
            }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): AskQuestionOptionDto {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: throw kotlinx.serialization.SerializationException("Only JSON is supported")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is kotlinx.serialization.json.JsonPrimitive -> {
                val str = element.contentOrNull ?: element.toString()
                AskQuestionOptionDto(id = "1", text = str, label = str)
            }
            is kotlinx.serialization.json.JsonObject -> {
                val id = element["id"]?.let {
                    if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull ?: it.toString() else "1"
                } ?: "1"
                val text = element["text"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull ?: it.toString() else null }
                    ?: element["label"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull ?: it.toString() else null }
                    ?: element["name"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull ?: it.toString() else null }
                    ?: element["option"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull ?: it.toString() else null }
                    ?: ""
                val label = element["label"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull ?: it.toString() else null } ?: text
                val desc = element["description"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull ?: it.toString() else null } ?: ""
                AskQuestionOptionDto(id = id, text = text, label = label, description = desc)
            }
            else -> AskQuestionOptionDto()
        }
    }
}

@Serializable
data class CancelCascadeInvocationRequestDto(
    val cascadeId: String
)

@Serializable
data class PermissionResolutionDto(
    val allow: Boolean,
    val scope: String = PermissionScopes.ONCE,
    val persistGrants: PersistGrantsDto? = null,
    val userDenyInstruction: String? = null
)

@Serializable
data class PersistGrantsDto(
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList()
)

@Serializable
data class QuestionnaireResolutionDto(
    val selectedChoices: List<String> = emptyList(),
    val freeformText: String = ""
)

/**
 * Incoming interaction request emitted by Cortex when status == CORTEX_STEP_STATUS_WAITING
 */
@Serializable
data class CortexRequestedInteractionDto(
    val type: String = "",                      // USER_INTERACTION_TYPE_CONFIRMATION, USER_INTERACTION_TYPE_QUESTIONNAIRE
    val permission: RequestedPermissionDto? = null,
    val questionnaire: QuestionnaireDto? = null
)

@Serializable
data class RequestedPermissionDto(
    val resource: PermissionResourceDto? = null,
    val actionDescription: String = "",
    val triggerSource: JsonObject? = null
)

@Serializable
data class PermissionResourceDto(
    val action: String = "",                    // "mcp", "bash", "network", etc.
    val target: String = ""                     // e.g. "dummy-mcp/dummy_add_numbers"
)

@Serializable
data class QuestionnaireDto(
    val questions: List<QuestionChoiceDto> = emptyList()
)

@Serializable
data class QuestionChoiceDto(
    val question: String = "",
    val options: List<String> = emptyList(),
    val isMultiSelect: Boolean = false
)

/**
 * Historical record of approved/denied interaction in completedInteractions list
 */
@Serializable
data class CompletedInteractionDto(
    val request: CompletedInteractionRequestDto? = null,
    val response: CompletedInteractionResponseDto? = null
)

@Serializable
data class CompletedInteractionRequestDto(
    val permission: RequestedPermissionDto? = null,
    val questionnaire: QuestionnaireDto? = null,
    val askQuestion: AskQuestionInteractionDto? = null
)

@Serializable
data class CompletedInteractionResponseDto(
    val trajectoryId: String = "",
    val stepIndex: Int = 0,
    val permission: PermissionResolutionDto? = null,
    val questionnaire: QuestionnaireResolutionDto? = null,
    val askQuestion: AskQuestionInteractionDto? = null
)

object PermissionScopes {
    const val UNSPECIFIED = "PERMISSION_SCOPE_UNSPECIFIED"
    const val ONCE = "PERMISSION_SCOPE_ONCE"
    const val CONVERSATION = "PERMISSION_SCOPE_CONVERSATION"
    const val PROJECT = "PERMISSION_SCOPE_PROJECT"
    const val WORKSPACE = "PERMISSION_SCOPE_WORKSPACE"
    const val GLOBAL = "PERMISSION_SCOPE_GLOBAL"
}
