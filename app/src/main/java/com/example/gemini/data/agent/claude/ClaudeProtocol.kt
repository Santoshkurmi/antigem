package com.example.gemini.data.agent.claude

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Typed models for the Claude Code stream-json protocol (CLAUDE_CLI_PROTOCOL.md) and the bridge's
 * `/api/claude/` endpoints. Unknown fields are ignored so newer CLI versions keep working.
 */
val ClaudeJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

// ---------------------------------------------------------------- bridge frames (WebSocket)

@Serializable
data class BridgeFrame(
    val type: String,
    val seq: Long? = null,
    val data: JsonObject? = null,
    val event: String? = null,
    val state: BridgeSessionState? = null,
    val exit_code: Int? = null,
    val error: String? = null
)

@Serializable
data class BridgeSessionState(
    val live: Boolean = false,
    val busy: Boolean = false,
    val pending_turns: Int = 0,
    val buffer_start_seq: Long = 0,
    val next_seq: Long = 0,
    val history_offset: Long = 0
)

@Serializable
data class BridgeConfigFrame(
    val type: String = "bridge_config",
    val cwd: String? = null,
    val model: String? = null,
    val permission_mode: String? = null,
    val effort: String? = null,
    val fork_from: String? = null,
    val resume_session_at: String? = null,
    /** "on" | "off" */
    val thinking: String? = null
)

// ---------------------------------------------------------------- outgoing (app → claude)

@Serializable
data class ClaudeUserMessage(
    val uuid: String,
    val message: ClaudeOutgoingMessage,
    val type: String = "user",
    val session_id: String = "",
    val parent_tool_use_id: String? = null
)

@Serializable
data class ClaudeOutgoingMessage(
    val content: List<ClaudeContentBlock>,
    val role: String = "user"
)

@Serializable
data class ClaudeControlRequest(
    val request_id: String,
    val request: ClaudeControlRequestBody,
    val type: String = "control_request"
)

@Serializable
data class ClaudeControlRequestBody(
    val subtype: String,
    val model: String? = null,
    val mode: String? = null,
    val max_thinking_tokens: Int? = null,
    val thinking_display: String? = null,
    val cancel_queued: Boolean? = null,
    val settings: JsonObject? = null,
    val user_message_id: String? = null,
    val dry_run: Boolean? = null
)

@Serializable
data class ClaudeControlResponse(
    val response: ClaudeControlResponseBody,
    val type: String = "control_response"
)

@Serializable
data class ClaudeControlResponseBody(
    val request_id: String,
    val response: ClaudePermissionResult? = null,
    val subtype: String = "success",
    val error: String? = null
)

/** Answer to `can_use_tool`. */
@Serializable
data class ClaudePermissionResult(
    val behavior: String,
    val updatedInput: JsonObject? = null,
    val updatedPermissions: JsonArray? = null,
    val message: String? = null,
    val interrupt: Boolean? = null,
    val toolUseID: String? = null
)

// ---------------------------------------------------------------- content blocks (both directions)

@Serializable
data class ClaudeContentBlock(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val tool_use_id: String? = null,
    val content: JsonElement? = null,
    val is_error: Boolean? = null,
    val source: ClaudeBlockSource? = null,
    val title: String? = null
)

@Serializable
data class ClaudeBlockSource(
    val type: String,
    val media_type: String? = null,
    val data: String? = null
)

// ---------------------------------------------------------------- incoming (claude → app)

@Serializable
data class ClaudeUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val cache_read_input_tokens: Int = 0,
    val cache_creation_input_tokens: Int = 0,
    val cache_creation: ClaudeCacheCreation? = null
)

@Serializable
data class ClaudeCacheCreation(
    val ephemeral_1h_input_tokens: Int = 0,
    val ephemeral_5m_input_tokens: Int = 0
)

@Serializable
data class ClaudeApiMessage(
    val id: String? = null,
    val model: String? = null,
    val role: String? = null,
    val content: List<ClaudeContentBlock> = emptyList(),
    val usage: ClaudeUsage? = null
)

@Serializable
data class ClaudeAssistantEvent(
    val message: ClaudeApiMessage,
    val parent_tool_use_id: String? = null,
    val uuid: String? = null,
    val parentUuid: String? = null,
    val isSidechain: Boolean = false,
    val timestamp: String? = null
)

@Serializable
data class ClaudeIncomingUserMessage(
    val content: JsonElement? = null
)

@Serializable
data class ClaudeUserEvent(
    val message: ClaudeIncomingUserMessage,
    val parent_tool_use_id: String? = null,
    val uuid: String? = null,
    val parentUuid: String? = null,
    val isReplay: Boolean = false,
    val isMeta: Boolean = false,
    val isSidechain: Boolean = false,
    val isCompactSummary: Boolean = false,
    val isSynthetic: Boolean = false,
    val timestamp: String? = null,
    val tool_use_result: JsonElement? = null,
    val toolUseResult: JsonElement? = null
)

@Serializable
data class ClaudeStreamEvent(
    val event: ClaudeStreamPayload,
    val parent_tool_use_id: String? = null
)

@Serializable
data class ClaudeStreamPayload(
    val type: String,
    val index: Int? = null,
    val message: ClaudeApiMessage? = null,
    val content_block: ClaudeContentBlock? = null,
    val delta: ClaudeStreamDelta? = null
)

@Serializable
data class ClaudeStreamDelta(
    val type: String? = null,
    val text: String? = null,
    val thinking: String? = null
)

@Serializable
data class ClaudeResultEvent(
    val subtype: String = "",
    val is_error: Boolean = false,
    val result: String? = null,
    val errors: List<String> = emptyList(),
    val terminal_reason: String? = null,
    val duration_ms: Long = 0,
    val total_cost_usd: Double = 0.0,
    val num_turns: Int = 0,
    val usage: ClaudeUsage? = null
)

@Serializable
data class ClaudeSystemEvent(
    val subtype: String = "",
    val session_id: String? = null,
    val model: String? = null,
    val permissionMode: String? = null,
    val status: String? = null,
    val title: String? = null,
    val content: String? = null,
    val message: String? = null,
    val tool_name: String? = null,
    val task_id: String? = null,
    val tool_use_id: String? = null,
    val description: String? = null,
    val summary: String? = null
)

@Serializable
data class ClaudeControlRequestEvent(
    val request_id: String,
    val request: ClaudeIncomingControlRequest
)

@Serializable
data class ClaudeIncomingControlRequest(
    val subtype: String,
    val tool_name: String? = null,
    val input: JsonObject? = null,
    val tool_use_id: String? = null,
    val permission_suggestions: JsonArray? = null,
    val description: String? = null,
    val title: String? = null,
    val decision_reason: String? = null,
    val blocked_path: String? = null
)

@Serializable
data class ClaudeControlCancelEvent(
    val request_id: String
)

/** A `control_response` coming back for a request the app sent. */
@Serializable
data class ClaudeControlResponseEvent(
    val response: ClaudeControlResponseInner
)

@Serializable
data class ClaudeControlResponseInner(
    val subtype: String = "",
    val request_id: String = "",
    val response: JsonObject? = null,
    val error: String? = null
)

// ---------------------------------------------------------------- bridge REST

@Serializable
data class ClaudeSessionSummary(
    val id: String,
    val title: String = "",
    val last_prompt: String = "",
    val cwd: String = "",
    val created_at: Long = 0,
    val updated_at: Long = 0,
    val live: Boolean = false,
    val busy: Boolean = false
)

@Serializable
data class ClaudeSessionListResponse(
    val sessions: List<ClaudeSessionSummary> = emptyList()
)

@Serializable
data class ClaudeHistoryResponse(
    val success: Boolean = false,
    val entries: List<JsonObject> = emptyList(),
    val state: BridgeSessionState = BridgeSessionState(),
    val error: String? = null
)

@Serializable
data class ClaudeModelInfo(
    val value: String,
    val resolvedModel: String? = null,
    val displayName: String = "",
    val description: String = "",
    val supportsEffort: Boolean = false,
    val supportedEffortLevels: List<String> = emptyList(),
    val supportsAdaptiveThinking: Boolean = false,
    val supportsFastMode: Boolean = false,
    val supportsAutoMode: Boolean = false,
    val disabled: Boolean = false
)

@Serializable
data class ClaudeCommandInfo(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val builtin: Boolean = false
)

@Serializable
data class ClaudeAccountInfo(
    val email: String? = null,
    val organization: String? = null,
    val subscriptionType: String? = null
)

@Serializable
data class ClaudeInitializeInfo(
    val models: List<ClaudeModelInfo> = emptyList(),
    val unavailable_models: List<ClaudeModelInfo> = emptyList(),
    val commands: List<ClaudeCommandInfo> = emptyList(),
    val output_style: String? = null,
    val available_output_styles: List<String> = emptyList(),
    val account: ClaudeAccountInfo? = null,
    val current_permission_mode: String? = null,
    val fast_mode_state: String? = null,
    val fast_mode_disabled_reason: String? = null
)

@Serializable
data class ClaudeInfoResponse(
    val success: Boolean = false,
    val info: ClaudeInitializeInfo? = null,
    /** Permission mode new chats start in (the user's settings.json default, otherwise Auto). */
    val default_mode: String? = null,
    val error: String? = null
)

@Serializable
data class ClaudeAuthStatus(
    val loggedIn: Boolean = false,
    val authMethod: String? = null,
    val email: String? = null,
    val subscriptionType: String? = null
)

@Serializable
data class ClaudeCliStatus(
    val success: Boolean = false,
    val installed: Boolean = false,
    val version: String? = null,
    val bin_path: String? = null,
    val auth: ClaudeAuthStatus? = null,
    /** `claude auth status` failed without printing a status. */
    val auth_error: String? = null,
    val error: String? = null
)

@Serializable
data class BridgeSimpleResponse(
    val success: Boolean = false,
    val error: String? = null
)

@Serializable
data class ClaudeTitleRequest(val title: String)

// ---------------------------------------------------------------- usage / limits

@Serializable
data class ClaudeLimitWindow(
    val utilization: Double? = null,
    val resets_at: String? = null
)

@Serializable
data class ClaudeLimitEntry(
    val kind: String = "",
    val group: String = "",
    val percent: Double = 0.0,
    val severity: String = "normal",
    val resets_at: String? = null,
    val is_active: Boolean = false
)

@Serializable
data class ClaudeRateLimits(
    val five_hour: ClaudeLimitWindow? = null,
    val seven_day: ClaudeLimitWindow? = null,
    val seven_day_opus: ClaudeLimitWindow? = null,
    val seven_day_sonnet: ClaudeLimitWindow? = null,
    val limits: List<ClaudeLimitEntry> = emptyList()
)

@Serializable
data class ClaudeSessionCost(
    val total_cost_usd: Double = 0.0,
    val total_duration_ms: Long = 0,
    val total_lines_added: Int = 0,
    val total_lines_removed: Int = 0
)

@Serializable
data class ClaudeUsageInfo(
    val session: ClaudeSessionCost? = null,
    val subscription_type: String? = null,
    val rate_limits_available: Boolean = false,
    val rate_limits: ClaudeRateLimits? = null
)

@Serializable
data class ClaudeUsageResponse(
    val success: Boolean = false,
    val usage: ClaudeUsageInfo? = null,
    val error: String? = null
)

/** Live `rate_limit_event.rate_limit_info.unifiedWindows` entry (utilization 0..1, resetsAt unix seconds). */
@Serializable
data class ClaudeUnifiedWindow(
    val utilization: Double = 0.0,
    val resetsAt: Long? = null
)

// ---------------------------------------------------------------- login

@Serializable
data class ClaudeLoginState(
    val login_id: String = "",
    val method: String = "",
    val state: String = "",
    val error: String? = null,
    val manual_url: String = "",
    val automatic_url: String = ""
)

@Serializable
data class ClaudeLoginResponse(
    val success: Boolean = false,
    val login: ClaudeLoginState? = null,
    val error: String? = null
)

@Serializable
data class ClaudeLoginStartRequest(val method: String)

@Serializable
data class ClaudeLoginCodeRequest(val code: String)

// ---------------------------------------------------------------- configuration

@Serializable
data class ClaudeSettingsResponse(
    val success: Boolean = false,
    val path: String = "",
    val settings: JsonObject = JsonObject(emptyMap()),
    val error: String? = null
)

@Serializable
data class ClaudeSettingsPut(val settings: JsonObject)

@Serializable
data class ClaudeMemoryResponse(
    val success: Boolean = false,
    val path: String = "",
    val exists: Boolean = false,
    val content: String = "",
    val error: String? = null
)

@Serializable
data class ClaudeMemoryPut(val content: String)

@Serializable
data class ClaudeMcpTool(val name: String = "")

@Serializable
data class ClaudeMcpServer(
    val name: String,
    val status: String = "",
    val scope: String? = null,
    val source: String? = null,
    val error: String? = null,
    val tools: List<ClaudeMcpTool> = emptyList(),
    val config: JsonObject? = null
)

@Serializable
data class ClaudeMcpResponse(
    val success: Boolean = false,
    val servers: List<ClaudeMcpServer> = emptyList(),
    val error: String? = null
)

@Serializable
data class ClaudeMcpAddRequest(
    val name: String,
    val scope: String = "user",
    val transport: String = "stdio",
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val cwd: String = ""
)

@Serializable
data class ClaudeCommandOutput(
    val success: Boolean = false,
    val output: String = "",
    val error: String? = null
)

@Serializable
data class ClaudePluginEntry(
    val pluginId: String = "",
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String? = null,
    val marketplace: String? = null,
    val enabled: Boolean? = null,
    val scope: String? = null,
    val category: String? = null
) {
    val key: String get() = pluginId.ifBlank { id.ifBlank { name } }
}

@Serializable
data class ClaudePluginLists(
    val installed: List<ClaudePluginEntry> = emptyList(),
    val available: List<ClaudePluginEntry> = emptyList()
)

@Serializable
data class ClaudeMarketplace(
    val name: String,
    val source: String? = null,
    val repo: String? = null,
    val url: String? = null
)

@Serializable
data class ClaudePluginsResponse(
    val success: Boolean = false,
    val plugins: ClaudePluginLists = ClaudePluginLists(),
    val marketplaces: List<ClaudeMarketplace> = emptyList(),
    val error: String? = null
)

@Serializable
data class ClaudePluginAction(
    val plugin: String = "",
    val scope: String? = null,
    val source: String? = null,
    val name: String? = null
)

@Serializable
data class ClaudeCliJob(
    val kind: String = "",
    val running: Boolean = false,
    val exit_code: Int? = null,
    val log: String = ""
)

@Serializable
data class ClaudeCliJobResponse(
    val success: Boolean = false,
    val job: ClaudeCliJob? = null,
    val error: String? = null
)

// ---------------------------------------------------------------- live control responses

@Serializable
data class ClaudeContextCategory(
    val name: String,
    val tokens: Long = 0,
    val kind: String? = null
)

@Serializable
data class ClaudeContextUsage(
    val categories: List<ClaudeContextCategory> = emptyList(),
    val totalTokens: Long = 0,
    val maxTokens: Long = 0,
    val percentage: Double = 0.0
)

@Serializable
data class ClaudeRewindResult(
    val canRewind: Boolean = false,
    val error: String? = null,
    val filesChanged: List<String> = emptyList(),
    val insertions: Int = 0,
    val deletions: Int = 0
)

@Serializable
data class ClaudeExportResult(
    val text: String = "",
    val default_filename: String? = null
)

// ---------------------------------------------------------------- app-handled slash commands

/** A slash command the app answers itself instead of sending it to Claude (see ClaudeChatBackend.appCommand). */
sealed interface ClaudeAppCommand {
    data object Usage : ClaudeAppCommand
    data object Context : ClaudeAppCommand
    data object ModelPicker : ClaudeAppCommand
    data object Mcp : ClaudeAppCommand
    data object Settings : ClaudeAppCommand
    data object NewChat : ClaudeAppCommand
    /** Applied through the app's own controls; [message] confirms it. */
    data class Done(val message: String) : ClaudeAppCommand
}

// ---------------------------------------------------------------- attachments & sandbox

@Serializable
data class ClaudeAttachmentSave(
    val session_id: String,
    val name: String,
    val mime_type: String,
    /** File content, base64. */
    val data: String
)

@Serializable
data class ClaudeSavedAttachment(
    val success: Boolean = false,
    val path: String? = null,
    val name: String? = null,
    val size: Long = 0,
    val mime_type: String? = null,
    val error: String? = null
)

/** Whether Claude Code's command sandbox (bubblewrap) can run on this device, and whether it is turned on. */
@Serializable
data class ClaudeSandboxInfo(
    val success: Boolean = false,
    val available: Boolean = false,
    val enabled: Boolean = false,
    val reason: String? = null
)
