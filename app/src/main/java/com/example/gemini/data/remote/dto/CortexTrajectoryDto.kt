package com.example.gemini.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Top-level gRPC-Web state update frame streamed from:
 * /exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates
 */
@Serializable
data class AgyStreamFrameDto(
    val update: AgyMainUpdateDto? = null,
    val mainTrajectoryUpdate: AgyMainTrajectoryUpdateDto? = null,
    val stepsUpdate: AgyStepsUpdateDto? = null,
    val steps: List<CortexStepDto>? = null,
    val artifactSnapshotsUpdate: ArtifactSnapshotsUpdateDto? = null,
    val lastStepError: CortexErrorDto? = null,
    val lastStepType: String = "",
    val conversationId: String = "",
    val trajectoryId: String = "",
    val status: String = "",
    val executableStatus: String = "",
    val executorLoopStatus: String = "",
    val fullyIdle: Boolean = false
)

@Serializable
data class AgyMainUpdateDto(
    val conversationId: String = "",
    val trajectoryId: String = "",
    val status: String = "",                    // CASCADE_RUN_STATUS_RUNNING, CASCADE_RUN_STATUS_IDLE
    val executableStatus: String = "",
    val executorLoopStatus: String = "",
    val mainTrajectoryUpdate: AgyMainTrajectoryUpdateDto? = null,
    val stepsUpdate: AgyStepsUpdateDto? = null,
    val artifactSnapshotsUpdate: ArtifactSnapshotsUpdateDto? = null,
    val lastStepError: CortexErrorDto? = null,
    val lastStepType: String = "",
    val fullyIdle: Boolean = false
)

@Serializable
data class AgyMainTrajectoryUpdateDto(
    val stepsUpdate: AgyStepsUpdateDto? = null,
    val artifactSnapshotsUpdate: ArtifactSnapshotsUpdateDto? = null,
    val trajectoryId: String = "",
    val lastStepError: CortexErrorDto? = null,
    val lastStepType: String = "",
    val generatorMetadatasUpdate: JsonObject? = null,
    val executorMetadatasUpdate: JsonObject? = null
)

@Serializable
data class ArtifactSnapshotsUpdateDto(
    val indices: List<Int> = emptyList(),
    val artifactSnapshots: List<ArtifactSnapshotDto> = emptyList()
)

@Serializable
data class ArtifactSnapshotDto(
    val artifactName: String = "",
    val artifactAbsoluteUri: String = "",
    val lastEdited: String = "",
    val artifactMetadata: ArtifactMetadataDto? = null
)

@Serializable
data class ArtifactMetadataDto(
    val summary: String? = null,
    val updatedAt: String? = null,
    val requestFeedback: Boolean = false,
    val userFacing: Boolean = false,
    @SerialName("Summary") val summaryPascal: String? = null,
    @SerialName("UpdatedAt") val updatedAtPascal: String? = null,
    @SerialName("RequestFeedback") val requestFeedbackPascal: Boolean? = null,
    @SerialName("UserFacing") val userFacingPascal: Boolean? = null
) {
    val effectiveSummary: String get() = summary ?: summaryPascal ?: ""
    val effectiveUpdatedAt: String get() = updatedAt ?: updatedAtPascal ?: ""
    val effectiveRequestFeedback: Boolean get() = requestFeedback || (requestFeedbackPascal == true)
    val effectiveUserFacing: Boolean get() = userFacing || (userFacingPascal == true)
}

@Serializable
data class AgyStepsUpdateDto(
    val indices: List<Int> = emptyList(),
    val steps: List<CortexStepDto> = emptyList(),
    val totalLength: Int = 0,
    val pageBounds: StepPageBoundsDto? = null
)

@Serializable
data class StepPageBoundsDto(
    val startIndex: Int? = null,
    val endIndex: Int? = null
)

/**
 * Universal step representation in AGY 2.0 / Jetbox / Cortex.
 * Used by both StreamAgentStateUpdates and GetCascadeTrajectorySteps.
 */
@Serializable
data class CortexStepDto(
    val type: String = "",                      // CORTEX_STEP_TYPE_*
    val status: String = "",                    // CORTEX_STEP_STATUS_*
    val metadata: CortexStepMetadataDto? = null,
    val userInput: CortexUserInputDto? = null,
    val plannerResponse: CortexPlannerResponseDto? = null,
    val generic: AgyGenericStepDto? = null,
    val requestedInteraction: CortexRequestedInteractionDto? = null,
    val systemMessage: CortexSystemMessageDto? = null,
    val taskDetails: CortexTaskDetailsDto? = null,
    val completedInteractions: List<CompletedInteractionDto> = emptyList(),
    val permissions: JsonElement? = null,
    val errorMessage: CortexErrorMessageDto? = null,
    val error: CortexErrorDto? = null,

    // Direct step fields (when step.type != CORTEX_STEP_TYPE_GENERIC)
    val runCommand: RunCommandResultDto? = null,
    val codeAction: CodeActionResultDto? = null,
    val viewFile: ViewFileResultDto? = null,
    val listDirectory: ListDirectoryResultDto? = null,
    val searchWeb: SearchWebResultDto? = null,
    val grepSearch: GrepSearchResultDto? = null,
    val find: FindResultDto? = null,
    val readUrlContent: ReadUrlContentResultDto? = null,
    val generateImage: GenerateImageResultDto? = null,
    val mcpTool: McpToolResultDto? = null,
    val invokeSubagent: InvokeSubagentResultDto? = null,
    val askQuestion: AskQuestionResultDto? = null
)

@Serializable
data class CortexStepMetadataDto(
    val createdAt: String = "",
    val viewableAt: String = "",
    val finishedGeneratingAt: String = "",
    val startedAt: String = "",
    val completedAt: String = "",
    val source: String = "",                    // CORTEX_STEP_SOURCE_MODEL, USER_EXPLICIT, SYSTEM
    val executionId: String = "",
    val generatorModel: String = "",
    val toolAction: String = "",
    val toolSummary: String = "",
    val toolCall: AgyToolCallMetadataDto? = null,
    val sourceTrajectoryStepInfo: SourceTrajectoryStepInfoDto? = null,
    val modelUsage: CortexModelUsageDto? = null,
    val preToolHookResults: List<PreToolHookResultDto> = emptyList()
)

@Serializable
data class AgyToolCallMetadataDto(
    val id: String = "",                        // e.g. "call_758344"
    val name: String = "",                      // e.g. "run_command", "view_file"
    val argumentsJson: String = "",
    val thinkingSignature: String = ""
)

@Serializable
data class SourceTrajectoryStepInfoDto(
    val trajectoryId: String = "",
    val cascadeId: String = "",
    val stepIndex: Int = 0,
    val metadataIndex: Int = 0,
    val turnIndex: Int = 0
)

@Serializable
data class CortexModelUsageDto(
    val model: String = "",
    val inputTokens: String = "0",
    val outputTokens: String = "0",
    val thinkingOutputTokens: String = "0",
    val responseOutputTokens: String = "0",
    val apiProvider: String = "",
    val messageId: String = "",
    val responseId: String = ""
)

@Serializable
data class PreToolHookResultDto(
    val decision: String = "allow"
)

@Serializable
data class CortexUserInputDto(
    val items: List<CascadeMessageItemDto> = emptyList(),
    val userResponse: String = "",
    val content: String = "",
    val media: List<MediaAttachmentDto> = emptyList()
)

@Serializable
data class CascadeMessageItemDto(
    val text: String = "",
    val media: MediaAttachmentDto? = null
)

@Serializable
data class MediaAttachmentDto(
    val mimeType: String = "",
    val mime_type: String = "",
    val data: String = "",                      // Base64 or URI
    val inlineData: String = "",
    val uri: String = "",
    val name: String = "",
    val description: String = "",
    val durationSeconds: Int = 0,
    val duration_seconds: Int = 0
)

@Serializable
data class CortexPlannerResponseDto(
    val response: String = "",                  // Full cumulative response text up to this chunk
    val thinking: String = "",                  // Full cumulative thinking text up to this chunk
    val modifiedResponse: String = "",
    val messageId: String = "",
    val stopReason: String = ""
)

@Serializable
data class AgyGenericStepDto(
    val name: String = "",
    val args: JsonObject = JsonObject(emptyMap()),
    val result: AgyGenericResultDto? = null
)

@Serializable
data class AgyGenericResultDto(
    val fullOutputUri: String? = null,          // Local file uri e.g. file:///.../output.txt
    val payload: JsonObject? = null
)

@Serializable
data class CortexSystemMessageDto(
    val content: String = "",
    val title: String = ""
)

@Serializable
data class CortexTaskDetailsDto(
    val description: String = "",
    val status: String = ""
)

@Serializable
data class CortexErrorDto(
    val shortError: String = "",
    val fullError: String = "",
    val message: String = "",
    val userErrorMessage: String = "",
    val modelErrorMessage: String = "",
    val code: Int? = null,
    val errorCode: Int? = null,
    val errorId: String = "",
    val details: String = "",
    val isBenign: Boolean = false,
    val structuredErrorParts: List<JsonObject> = emptyList()
)

@Serializable
data class CortexErrorMessageDto(
    val error: CortexErrorDto? = null,
    val shouldShowUser: Boolean = true
)

// ==========================================
// Tool-Specific Result & Payload DTOs
// ==========================================

@Serializable
data class GenericPayloadDto(
    val runCommand: RunCommandResultDto? = null,
    val codeAction: CodeActionResultDto? = null,
    val viewFile: ViewFileResultDto? = null,
    val listDirectory: ListDirectoryResultDto? = null,
    val searchWeb: SearchWebResultDto? = null,
    val grepSearch: GrepSearchResultDto? = null,
    val find: FindResultDto? = null,
    val readUrlContent: ReadUrlContentResultDto? = null,
    val generateImage: GenerateImageResultDto? = null,
    val mcpTool: McpToolResultDto? = null,
    val invokeSubagent: InvokeSubagentResultDto? = null,
    val askQuestion: AskQuestionResultDto? = null
)

@Serializable
data class RunCommandResultDto(
    val commandLine: String = "",
    val exitCode: Int? = null,
    val combinedOutput: CombinedOutputDto? = null,
    val output: String = "",
    val proposedCommandLine: String = ""
)

@Serializable
data class CombinedOutputDto(
    val full: String = ""
)

@Serializable
data class ListDirectoryResultDto(
    val directoryPathUri: String = "",
    val directoryPath: String = "",
    val results: List<DirectoryFileEntryDto> = emptyList(),
    val output: String = ""
)

@Serializable
data class DirectoryFileEntryDto(
    val name: String = "",
    val sizeBytes: String = "0",
    val isDir: Boolean = false
)

@Serializable
data class SearchWebResultDto(
    val query: String = "",
    val summary: String = "",
    val results: List<WebSearchResultItemDto> = emptyList(),
    val output: String = ""
)

@Serializable
data class WebSearchResultItemDto(
    val title: String = "",
    val url: String = "",
    val snippet: String = ""
)

@Serializable
data class ViewFileResultDto(
    val fileUri: String = "",
    val absolutePathUri: String = "",
    val absolutePath: String = "",
    val content: String = "",
    val totalLines: Int = 0,
    val numLines: Int = 0,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val mediaData: MediaAttachmentDto? = null
)

@Serializable
data class CodeActionResultDto(
    val uri: String = "",
    val absolutePathUri: String = "",
    val diff: String = "",
    val patch: String = "",
    val status: String = "",
    val diffStats: DiffStatsDto? = null
)

@Serializable
data class DiffStatsDto(
    val additions: Int = 0,
    val deletions: Int = 0
)

@Serializable
data class McpToolResultDto(
    val serverName: String = "",
    val toolName: String = "",
    val toolCall: AgyToolCallMetadataDto? = null,
    val resultString: String = "",
    val result: JsonElement? = null,
    val response: JsonElement? = null,
    val output: JsonElement? = null,
    val error: String = ""
)

@Serializable
data class FindResultDto(
    val pattern: String = "",
    val searchDirectory: String = "",
    val matchedUris: List<String> = emptyList(),
    val truncatedOutput: String = "",
    val output: String = ""
)

@Serializable
data class GrepSearchResultDto(
    val query: String = "",
    val searchPath: String = "",
    val searchPathUri: String = "",
    val matchedLines: List<String> = emptyList(),
    val totalResults: Int = 0,
    val commandRun: String = "",
    val results: List<GrepMatchDto> = emptyList()
)

@Serializable
data class GrepMatchDto(
    val fileName: String = "",
    val lineNumber: Int = 0,
    val lineContent: String = ""
)

@Serializable
data class ReadUrlContentResultDto(
    val url: String = "",
    val content: String = "",
    val markdown: String = ""
)

@Serializable
data class InvokeSubagentResultDto(
    val subagentConversationId: String = "",
    val prompt: String = "",
    val finalResponse: String = ""
)

@Serializable
data class GenerateImageResultDto(
    val prompt: String = "",
    val imageUri: String = "",
    val uri: String = "",
    val generatedMedia: GeneratedMediaDto? = null
)

@Serializable
data class GeneratedMediaDto(
    val uri: String = "",
    val inlineData: String = "",
    val mimeType: String = ""
)

@Serializable
data class AskQuestionResultDto(
    val questions: List<AskQuestionItemDto> = emptyList(),
    val userAnswers: List<String> = emptyList()
)

@Serializable
data class AskQuestionItemDto(
    val question: String = "",
    val options: List<AskQuestionOptionDto> = emptyList(),
    val is_multi_select: Boolean = false,
    val isMultiSelect: Boolean = false,
    val IsMultiSelect: Boolean = false,
    val selectedOptionIds: List<String> = emptyList(),
    val writeInResponse: String? = null,
    val skipped: Boolean = false
)

// ==========================================
// Tool-Specific Argument DTOs (Model Inputs)
// ==========================================

@Serializable
data class RunCommandArgsDto(
    val CommandLine: String = "",
    val Cwd: String = "",
    val IsDaemon: Boolean = false,
    val WaitMsBeforeAsync: Long = 0L
)

@Serializable
data class ViewFileArgsDto(
    val AbsolutePath: String = "",
    val StartLine: Int? = null,
    val EndLine: Int? = null,
    val ContentOffset: Long? = null,
    val IsSkillFile: Boolean = false
)

@Serializable
data class WriteToFileArgsDto(
    val TargetFile: String = "",
    val CodeContent: String = "",
    val Overwrite: Boolean = false,
    val Description: String = "",
    val Instruction: String = "",
    val ArtifactMetadata: ArtifactMetadataDto? = null
)

@Serializable
data class ReplaceFileContentArgsDto(
    val TargetFile: String = "",
    val TargetContent: String = "",
    val ReplacementContent: String = "",
    val StartLine: Int? = null,
    val EndLine: Int? = null,
    val AllowMultiple: Boolean = false,
    val Description: String = "",
    val Instruction: String = ""
)

@Serializable
data class MultiReplaceFileContentArgsDto(
    val TargetFile: String = "",
    val ReplacementChunks: List<ReplacementChunkDto> = emptyList(),
    val Description: String = "",
    val Instruction: String = ""
)

@Serializable
data class ReplacementChunkDto(
    val StartLine: Int? = null,
    val EndLine: Int? = null,
    val TargetContent: String = "",
    val ReplacementContent: String = "",
    val AllowMultiple: Boolean = false
)


@Serializable
data class ListDirArgsDto(
    val DirectoryPath: String = ""
)

@Serializable
data class GrepSearchArgsDto(
    val Query: String = "",
    val SearchPath: String = "",
    val CaseInsensitive: Boolean = false,
    val IsRegex: Boolean = false,
    val MatchPerLine: Boolean = true,
    val Includes: List<String> = emptyList()
)

@Serializable
data class FindByNameArgsDto(
    val Pattern: String = "",
    val SearchDirectory: String = ""
)

@Serializable
data class SearchWebArgsDto(
    val query: String = "",
    val domain: String = ""
)

@Serializable
data class ReadUrlContentArgsDto(
    val Url: String = ""
)

@Serializable
data class GenerateImageArgsDto(
    val Prompt: String = "",
    val ImageName: String = "",
    val AspectRatio: String = "1:1",
    val ImagePaths: List<String> = emptyList()
)

@Serializable
data class CallMcpToolArgsDto(
    val ServerName: String = "",
    val ToolName: String = "",
    val Arguments: JsonElement? = null
)

@Serializable
data class ScheduleArgsDto(
    val DurationSeconds: String = "",
    val CronExpression: String = "",
    val Prompt: String = "",
    val TimerCondition: String = "never",
    val MaxIterations: String = "",
    val IsDaemon: Boolean = false
)

@Serializable
data class ManageTaskArgsDto(
    val Action: String = "",
    val TaskId: String = "",
    val Input: String = ""
)

@Serializable
data class AskQuestionArgsDto(
    val questions: List<AskQuestionItemDto> = emptyList()
)

// ==========================================
// 100% Authoritative Proto Enums from AGY Binary
// ==========================================

object CortexStepTypes {
    const val UNSPECIFIED = "CORTEX_STEP_TYPE_UNSPECIFIED"
    const val USER_INPUT = "CORTEX_STEP_TYPE_USER_INPUT"
    const val PLANNER_RESPONSE = "CORTEX_STEP_TYPE_PLANNER_RESPONSE"
    const val GENERIC = "CORTEX_STEP_TYPE_GENERIC"
    const val SYSTEM_MESSAGE = "CORTEX_STEP_TYPE_SYSTEM_MESSAGE"
    const val ASK_QUESTION = "CORTEX_STEP_TYPE_ASK_QUESTION"
    const val BRAIN_UPDATE = "CORTEX_STEP_TYPE_BRAIN_UPDATE"
    const val BROWSER_INPUT = "CORTEX_STEP_TYPE_BROWSER_INPUT"
    const val BROWSER_SCROLL = "CORTEX_STEP_TYPE_BROWSER_SCROLL"
    const val BUILD_CLEANER = "CORTEX_STEP_TYPE_BUILD_CLEANER"
    const val CHECKPOINT = "CORTEX_STEP_TYPE_CHECKPOINT"
    const val CLIPBOARD = "CORTEX_STEP_TYPE_CLIPBOARD"
    const val CODE_ACTION = "CORTEX_STEP_TYPE_CODE_ACTION"
    const val CODE_SEARCH = "CORTEX_STEP_TYPE_CODE_SEARCH"
    const val COMMAND_STATUS = "CORTEX_STEP_TYPE_COMMAND_STATUS"
    const val COMPILE = "CORTEX_STEP_TYPE_COMPILE"
    const val COMPILE_APPLET = "CORTEX_STEP_TYPE_COMPILE_APPLET"
    const val CRITIQUE = "CORTEX_STEP_TYPE_CRITIQUE"
    const val DUMMY = "CORTEX_STEP_TYPE_DUMMY"
    const val EDIT_NOTEBOOK = "CORTEX_STEP_TYPE_EDIT_NOTEBOOK"
    const val ERROR_MESSAGE = "CORTEX_STEP_TYPE_ERROR_MESSAGE"
    const val FILE_CHANGE = "CORTEX_STEP_TYPE_FILE_CHANGE"
    const val FIND = "CORTEX_STEP_TYPE_FIND"
    const val FINDINGS = "CORTEX_STEP_TYPE_FINDINGS"
    const val FINISH = "CORTEX_STEP_TYPE_FINISH"
    const val GENERATE_IMAGE = "CORTEX_STEP_TYPE_GENERATE_IMAGE"
    const val GIT_COMMIT = "CORTEX_STEP_TYPE_GIT_COMMIT"
    const val GREP_SEARCH = "CORTEX_STEP_TYPE_GREP_SEARCH"
    const val KI_INSERTION = "CORTEX_STEP_TYPE_KI_INSERTION"
    const val LINT_APPLET = "CORTEX_STEP_TYPE_LINT_APPLET"
    const val LINT_DIFF = "CORTEX_STEP_TYPE_LINT_DIFF"
    const val LIST_DIRECTORY = "CORTEX_STEP_TYPE_LIST_DIRECTORY"
    const val LIST_RESOURCES = "CORTEX_STEP_TYPE_LIST_RESOURCES"
    const val MCP_TOOL = "CORTEX_STEP_TYPE_MCP_TOOL"
    const val MEMORY = "CORTEX_STEP_TYPE_MEMORY"
    const val MOMA = "CORTEX_STEP_TYPE_MOMA"
    const val MOVE = "CORTEX_STEP_TYPE_MOVE"
    const val MQUERY = "CORTEX_STEP_TYPE_MQUERY"
    const val NOTIFY_USER = "CORTEX_STEP_TYPE_NOTIFY_USER"
    const val PLAN_INPUT = "CORTEX_STEP_TYPE_PLAN_INPUT"
    const val POST_PR_REVIEW = "CORTEX_STEP_TYPE_POST_PR_REVIEW"
    const val PROPOSE_CODE = "CORTEX_STEP_TYPE_PROPOSE_CODE"
    const val READ_NOTEBOOK = "CORTEX_STEP_TYPE_READ_NOTEBOOK"
    const val READ_RESOURCE = "CORTEX_STEP_TYPE_READ_RESOURCE"
    const val READ_TERMINAL = "CORTEX_STEP_TYPE_READ_TERMINAL"
    const val RPC_ACTION = "CORTEX_STEP_TYPE_RPC_ACTION"
    const val RUN_COMMAND = "CORTEX_STEP_TYPE_RUN_COMMAND"
    const val SEARCH_WEB = "CORTEX_STEP_TYPE_SEARCH_WEB"
    const val SHELL_EXEC = "CORTEX_STEP_TYPE_SHELL_EXEC"
    const val TASK_BOUNDARY = "CORTEX_STEP_TYPE_TASK_BOUNDARY"
    const val TOOL_SEARCH = "CORTEX_STEP_TYPE_TOOL_SEARCH"
    const val VIEW_CODE_ITEM = "CORTEX_STEP_TYPE_VIEW_CODE_ITEM"
    const val VIEW_FILE = "CORTEX_STEP_TYPE_VIEW_FILE"
    const val WAIT = "CORTEX_STEP_TYPE_WAIT"
    const val WORKSPACE_API = "CORTEX_STEP_TYPE_WORKSPACE_API"
    const val WRITE_BLOB = "CORTEX_STEP_TYPE_WRITE_BLOB"
}

object CortexStepStatuses {
    const val UNSPECIFIED = "CORTEX_STEP_STATUS_UNSPECIFIED"
    const val PENDING = "CORTEX_STEP_STATUS_PENDING"
    const val QUEUED = "CORTEX_STEP_STATUS_QUEUED"
    const val GENERATING = "CORTEX_STEP_STATUS_GENERATING"
    const val RUNNING = "CORTEX_STEP_STATUS_RUNNING"
    const val WAITING = "CORTEX_STEP_STATUS_WAITING"
    const val DONE = "CORTEX_STEP_STATUS_DONE"
    const val ERROR = "CORTEX_STEP_STATUS_ERROR"
    const val CANCELED = "CORTEX_STEP_STATUS_CANCELED"
    const val CLEARED = "CORTEX_STEP_STATUS_CLEARED"
    const val HALTED = "CORTEX_STEP_STATUS_HALTED"
    const val INTERRUPTED = "CORTEX_STEP_STATUS_INTERRUPTED"
    const val INVALID = "CORTEX_STEP_STATUS_INVALID"
}

object CascadeRunStatuses {
    const val UNSPECIFIED = "CASCADE_RUN_STATUS_UNSPECIFIED"
    const val IDLE = "CASCADE_RUN_STATUS_IDLE"
    const val RUNNING = "CASCADE_RUN_STATUS_RUNNING"
    const val BUSY = "CASCADE_RUN_STATUS_BUSY"
    const val CANCELING = "CASCADE_RUN_STATUS_CANCELING"
}
