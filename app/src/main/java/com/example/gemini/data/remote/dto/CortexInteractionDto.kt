package com.example.gemini.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Request payload for /exa.language_server_pb.LanguageServerService/HandleCascadeUserInteraction
 * Verified directly against recorded live traffic logs on port 8090/8091.
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
    val questionnaire: QuestionnaireResolutionDto? = null
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
    val permission: RequestedPermissionDto? = null
)

@Serializable
data class CompletedInteractionResponseDto(
    val trajectoryId: String = "",
    val stepIndex: Int = 0,
    val permission: PermissionResolutionDto? = null
)

object PermissionScopes {
    const val UNSPECIFIED = "PERMISSION_SCOPE_UNSPECIFIED"
    const val ONCE = "PERMISSION_SCOPE_ONCE"
    const val CONVERSATION = "PERMISSION_SCOPE_CONVERSATION"
    const val PROJECT = "PERMISSION_SCOPE_PROJECT"
    const val WORKSPACE = "PERMISSION_SCOPE_WORKSPACE"
    const val GLOBAL = "PERMISSION_SCOPE_GLOBAL"
}
