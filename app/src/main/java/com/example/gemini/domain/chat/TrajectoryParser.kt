package com.example.gemini.domain.chat

import android.util.Log
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Universal domain parser for converting raw AGY/Cortex steps into UI ChatMessage,
 * ChatAttachment, and ToolCall models.
 */
object TrajectoryParser {
    private const val TAG = "TrajectoryParser"

    fun normalizeToolName(name: String): String = when (name.lowercase().trim()) {
        "web_search", "search_web", "search" -> "web_search"
        "read_url", "read_url_content", "web_reader" -> "read_url"
        "run_command", "terminal", "bash" -> "bash"
        "view_file", "viewfile" -> "view_file"
        "edit_file", "modifyfile", "write_to_file", "replace_file_content", "codeaction", "filechange" -> "edit_file"
        "list_dir", "listdirectory" -> "list_dir"
        "find", "find_by_name" -> "find"
        "grep_search", "code_search" -> "grep_search"
        "generate_image", "generateimage" -> "generate_image"
        else -> name.lowercase().trim()
    }

    fun findLastUserStepIndex(steps: JSONArray): Int {
        var lastIndex = -1
        for (i in (steps.length() - 1) downTo 0) {
            val step = steps.optJSONObject(i) ?: continue
            val stepType = step.optString("type", "")
            if (stepType == "CORTEX_STEP_TYPE_USER_INPUT" || step.has("userInput")) {
                val stepInfo = step.optJSONObject("metadata")?.optJSONObject("sourceTrajectoryStepInfo")
                return stepInfo?.optInt("stepIndex", step.optInt("stepIndex", i)) ?: step.optInt("stepIndex", i)
            }
        }
        return lastIndex
    }

    /**
     * Parses steps JSON payload into chronological ChatMessages.
     */
    fun parseStepsToChatMessages(stepsJson: String, conversationId: String): List<ChatMessage> {
        try {
            val root = JSONObject(stepsJson)
            val steps = root.optJSONArray("steps")
                ?: root.optJSONObject("update")?.optJSONObject("mainTrajectoryUpdate")?.optJSONObject("stepsUpdate")?.optJSONArray("steps")
                ?: root.optJSONObject("mainTrajectoryUpdate")?.optJSONObject("stepsUpdate")?.optJSONArray("steps")
                ?: root.optJSONObject("stepsUpdate")?.optJSONArray("steps")
                ?: return emptyList()

            val status = root.optJSONObject("update")?.optString("status", "") ?: root.optString("status", "")
            return parseStepsArrayToChatMessages(steps, conversationId, status)
        } catch (e: Exception) {
            Log.e(TAG, "parseStepsToChatMessages error: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Parses steps array into chat messages with chronological tool ordering and live status.
     */
    fun parseStepsArrayToChatMessages(
        steps: JSONArray,
        conversationId: String,
        cascadeStatus: String = ""
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        try {
            val turnTools = linkedMapOf<String, ToolCall>()
            val turnStepTexts = sortedMapOf<Int, String>()
            val turnStepThoughts = sortedMapOf<Int, String>()
            var turnAssistantId: String? = null

            fun flushAssistant() {
                if (turnAssistantId != null || turnStepThoughts.isNotEmpty() || turnStepTexts.isNotEmpty() || turnTools.isNotEmpty()) {
                    val content = turnStepTexts.values.joinToString("\n\n").trim()
                    val thoughtText = turnStepThoughts.values.joinToString("\n\n").trim().takeIf { it.isNotBlank() }
                    messages.add(
                        ChatMessage(
                            id = turnAssistantId ?: UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = content,
                            thoughtText = thoughtText,
                            toolCalls = turnTools.values.toList(),
                            isStreaming = false
                        )
                    )
                    turnStepThoughts.clear()
                    turnStepTexts.clear()
                    turnTools.clear()
                    turnAssistantId = null
                }
            }

            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val stepType = step.optString("type", "")
                val stepInfo = step.optJSONObject("metadata")?.optJSONObject("sourceTrajectoryStepInfo")
                val stepIndex = stepInfo?.optInt("stepIndex", step.optInt("stepIndex", i)) ?: step.optInt("stepIndex", i)

                if (stepType == "CORTEX_STEP_TYPE_USER_INPUT" || step.has("userInput")) {
                    flushAssistant()
                    val userInput = step.optJSONObject("userInput")
                    var userText = ""
                    val userAttachments = mutableListOf<ChatAttachment>()
                    if (userInput != null) {
                        val items = userInput.optJSONArray("items")
                        if (items != null && items.length() > 0) {
                            userText = items.getJSONObject(0).optString("text", "")
                        } else {
                            userText = userInput.optString("content", "")
                        }

                        val mediaArr = userInput.optJSONArray("media")
                        if (mediaArr != null) {
                            for (mIdx in 0 until mediaArr.length()) {
                                val mObj = mediaArr.optJSONObject(mIdx) ?: continue
                                val mime = mObj.optString("mimeType", mObj.optString("mime_type", ""))
                                val uri = mObj.optString("uri", "")
                                val inline = mObj.optString("inlineData", mObj.optString("inline_data", ""))
                                val cleanPath = if (uri.startsWith("file://")) uri.removePrefix("file://") else uri
                                if (mime.isBlank() && cleanPath.isBlank() && inline.isBlank()) {
                                    continue
                                }
                                val dur = mObj.optInt("durationSeconds", mObj.optInt("duration_seconds", 0))
                                val isAud = mime.startsWith("audio/") || cleanPath.endsWith(".m4a", true) || cleanPath.endsWith(".mp3", true) || cleanPath.endsWith(".wav", true) || cleanPath.endsWith(".ogg", true)
                                val isImg = mime.startsWith("image/") || cleanPath.endsWith(".png", true) || cleanPath.endsWith(".jpg", true) || cleanPath.endsWith(".jpeg", true) || cleanPath.endsWith(".webp", true) || cleanPath.endsWith(".gif", true) || cleanPath.endsWith(".svg", true)
                                val desc = mObj.optString("description", if (isAud) "Voice note" else if (isImg) "Image" else "Attachment")
                                userAttachments.add(
                                    ChatAttachment(
                                        id = "att_${conversationId}_${stepIndex}_$mIdx",
                                        name = desc,
                                        path = cleanPath,
                                        isImage = isImg,
                                        isAudio = isAud,
                                        durationSeconds = dur,
                                        mimeType = mime.ifBlank { if (isAud) "audio/mp4" else if (isImg) "image/jpeg" else "" },
                                        base64 = inline.ifBlank { null }
                                    )
                                )
                            }
                        }

                        val imageRegex = Regex("""\[Attached Image:\s*([^\]]+)\]\(([^)]+)\)""", RegexOption.IGNORE_CASE)
                        val fileRegex = Regex("""\[Attached File:\s*([^\]]+)\]\(([^)]+)\)""", RegexOption.IGNORE_CASE)

                        imageRegex.findAll(userText).forEachIndexed { idx, match ->
                            val attName = match.groupValues[1].trim()
                            val rawPath = match.groupValues[2].trim()
                            val cleanPath = if (rawPath.startsWith("file://")) rawPath.removePrefix("file://") else rawPath
                            userAttachments.add(
                                ChatAttachment(
                                    id = "att_${conversationId}_${stepIndex}_img_$idx",
                                    name = attName,
                                    path = cleanPath,
                                    isImage = true,
                                    isAudio = false
                                )
                            )
                        }

                        fileRegex.findAll(userText).forEachIndexed { idx, match ->
                            val attName = match.groupValues[1].trim()
                            val rawPath = match.groupValues[2].trim()
                            val cleanPath = if (rawPath.startsWith("file://")) rawPath.removePrefix("file://") else rawPath
                            userAttachments.add(
                                ChatAttachment(
                                    id = "att_${conversationId}_${stepIndex}_file_$idx",
                                    name = attName,
                                    path = cleanPath,
                                    isImage = false,
                                    isAudio = false
                                )
                            )
                        }
                    }
                    if (userText.isNotBlank() || userAttachments.isNotEmpty()) {
                        messages.add(
                            ChatMessage(
                                id = "user_${conversationId}_$stepIndex",
                                conversationId = conversationId,
                                role = MessageRole.USER,
                                content = userText,
                                attachments = userAttachments,
                                stepIndex = stepIndex
                            )
                        )
                    }
                } else {
                    if (turnAssistantId == null) {
                        turnAssistantId = "assistant_${conversationId}_$stepIndex"
                    }

                    if (step.has("plannerResponse")) {
                        val pr = step.getJSONObject("plannerResponse")
                        val th = pr.optString("thinking", "")
                        val resp = pr.optString("response", "")
                        if (th.isNotBlank()) {
                            turnStepThoughts[stepIndex] = th
                        }
                        if (resp.isNotBlank()) {
                            val existing = turnStepTexts[stepIndex]
                            turnStepTexts[stepIndex] = if (existing != null) "$existing\n\n$resp" else resp
                        }
                    }

                    val tool = extractToolCallFromStep(step, stepIndex, conversationId, cascadeStatus)
                    if (tool != null) {
                        val normName = normalizeToolName(tool.name)
                        val targetId = tool.id
                        val unifiedTool = tool.copy(id = targetId, name = normName)
                        turnTools[targetId] = unifiedTool

                        val marker = "<!-- tool_call:$targetId -->"
                        val alreadyHasMarker = turnStepTexts.values.any { it.contains(marker) }
                        if (!alreadyHasMarker) {
                            val existing = turnStepTexts[stepIndex]
                            turnStepTexts[stepIndex] = if (existing != null) "$existing\n\n$marker" else marker
                        }
                    } else {
                        val stepErrJson = extractStepErrorJson(step, stepIndex)
                        if (stepErrJson != null) {
                            val marker = "<!-- error:$stepIndex -->\n$stepErrJson\n<!-- /error -->"
                            val existing = turnStepTexts[stepIndex]
                            turnStepTexts[stepIndex] = if (existing != null) "$existing\n\n$marker" else marker
                        } else {
                            val stepErr = extractStepError(step)
                            if (stepErr != null) {
                                val existing = turnStepTexts[stepIndex]
                                turnStepTexts[stepIndex] = if (existing != null) "$existing\n\n⚠️ $stepErr" else "⚠️ $stepErr"
                            }
                        }
                    }
                }
            }
            flushAssistant()
        } catch (e: Exception) {
            Log.e(TAG, "parseStepsArrayToChatMessages error: ${e.message}")
        }
        return messages
    }

    /**
     * Extracts a ToolCall from a trajectory step.
     */
    fun extractToolCallFromStep(
        step: JSONObject,
        stepIndex: Int,
        conversationId: String,
        cascadeStatus: String = ""
    ): ToolCall? {
        val meta = step.optJSONObject("metadata")
        val stepInfo = meta?.optJSONObject("sourceTrajectoryStepInfo")
        val actualStepIndex = when {
            stepInfo?.has("stepIndex") == true -> stepInfo.getInt("stepIndex")
            step.has("stepIndex") -> step.getInt("stepIndex")
            else -> stepIndex
        }
        val trajId = stepInfo?.optString("trajectoryId", "")?.takeIf { it.isNotBlank() }
            ?: step.optString("trajectoryId", "").takeIf { it.isNotBlank() }

        val reqInteraction = step.optJSONObject("requestedInteraction")
        val perm = reqInteraction?.optJSONObject("permission")
            ?: reqInteraction?.optJSONObject("confirmation")
            ?: step.optJSONObject("permission")
            ?: meta?.optJSONObject("permission")

        val stepType = step.optString("type", "")
        val interactionType = when {
            reqInteraction?.has("mcp") == true || step.has("mcpTool") || step.has("callMcpTool") || stepType.contains("MCP") ||
                reqInteraction?.optString("type")?.contains("MCP", ignoreCase = true) == true -> "mcp"
            perm != null || reqInteraction?.has("permission") == true -> "permission"
            reqInteraction?.has("approvalInteraction") == true || (step.has("generic") && perm == null) -> "approvalInteraction"
            reqInteraction?.has("readUrlContent") == true || step.has("readUrlContent") -> "readUrlContent"
            reqInteraction?.has("browserAction") == true -> "browserAction"
            else -> "permission"
        }

        val rawCall = extractRawToolCallFromStep(step, actualStepIndex, conversationId, cascadeStatus) ?: return null
        return rawCall.copy(
            stepIndex = actualStepIndex,
            trajectoryId = trajId ?: rawCall.trajectoryId,
            interactionType = rawCall.interactionType ?: interactionType
        )
    }

    private fun extractRawToolCallFromStep(
        step: JSONObject,
        stepIndex: Int,
        conversationId: String,
        cascadeStatus: String = ""
    ): ToolCall? {
        val meta = step.optJSONObject("metadata")
        val toolSummary = meta?.optString("toolSummary", "")?.trim() ?: ""
        val toolAction = meta?.optString("toolAction", "")?.trim() ?: ""
        val stepStatus = step.optString("status", "")
        val stepType = step.optString("type", "")

        val reqInteraction = step.optJSONObject("requestedInteraction")
        val perm = reqInteraction?.optJSONObject("permission")
            ?: reqInteraction?.optJSONObject("confirmation")
            ?: step.optJSONObject("permission")
            ?: meta?.optJSONObject("permission")

        val rc = step.optJSONObject("runCommand")
        val generic = step.optJSONObject("generic")
        val genericArgs = generic?.optJSONObject("args")

        val metaTc = meta?.optJSONObject("toolCall")
        val plannerPr = step.optJSONObject("plannerResponse")
        val callsArr = step.optJSONArray("tool_calls")
            ?: step.optJSONArray("toolCalls")
            ?: plannerPr?.optJSONArray("toolCalls")
            ?: plannerPr?.optJSONArray("tool_calls")
        val firstTc = callsArr?.optJSONObject(0)

        fun makeToolId(prefix: String): String = stepIndex.toString()

        fun parseArgsJson(obj: JSONObject?): JSONObject? {
            if (obj == null) return null
            val direct = obj.optJSONObject("args") ?: obj.optJSONObject("argsJson") ?: obj.optJSONObject("arguments")
            if (direct != null) return direct
            val jsonStr = obj.optString("argumentsJson", "").takeIf { it.isNotBlank() }
                ?: obj.optString("args", "").takeIf { it.isNotBlank() }
            if (jsonStr != null) {
                try { return JSONObject(jsonStr) } catch (_: Exception) {}
            }
            return null
        }
        val tcArgs = parseArgsJson(firstTc) ?: parseArgsJson(metaTc) ?: genericArgs

        val isStepRunning = stepStatus == "CORTEX_STEP_STATUS_RUNNING" ||
                stepStatus == "CORTEX_STEP_STATUS_PENDING" ||
                stepStatus == "CORTEX_STEP_STATUS_GENERATING" ||
                stepStatus == "RUNNING"
        val isStepDone = stepStatus == "CORTEX_STEP_STATUS_DONE" ||
                stepStatus == "DONE" ||
                stepStatus == "SUCCESS"

        val isProposedRunCommand = rc != null && rc.has("proposedCommandLine") &&
                rc.optString("commandLine", "").isBlank() &&
                (rc.optJSONObject("combinedOutput")?.optString("full") ?: rc.optString("output", "")).isBlank() &&
                !rc.has("exitCode")

        val isProposedGenericCommand = genericArgs?.has("CommandLine") == true && !step.has("runCommand")

        val isWaitingPermission = !isStepRunning && !isStepDone && (
            perm != null ||
            (reqInteraction != null && reqInteraction.length() > 0) ||
            stepType == "CORTEX_STEP_TYPE_CONFIRM" ||
            stepStatus == "CORTEX_STEP_STATUS_WAITING" ||
            stepStatus == "WAITING" ||
            isProposedRunCommand ||
            isProposedGenericCommand
        )

        fun resolveStatus(hasOutput: Boolean, isPending: Boolean = false): String {
            return when {
                stepStatus == "CORTEX_STEP_STATUS_DONE" || stepStatus == "DONE" || stepStatus == "SUCCESS" -> "SUCCESS"
                stepStatus == "CORTEX_STEP_STATUS_ERROR" || stepStatus == "ERROR" || stepStatus == "FAILED" -> "FAILED"
                stepStatus == "CORTEX_STEP_STATUS_CANCELLED" || stepStatus == "CANCELLED" || stepStatus == "REJECTED" -> "REJECTED"
                stepStatus == "CORTEX_STEP_STATUS_WAITING" || stepStatus == "WAITING" || isPending -> "PENDING_APPROVAL"
                stepStatus == "CORTEX_STEP_STATUS_RUNNING" || stepStatus == "CORTEX_STEP_STATUS_PENDING" || stepStatus == "CORTEX_STEP_STATUS_GENERATING" || stepStatus == "RUNNING" -> "RUNNING"
                else -> if (isPending) "PENDING_APPROVAL" else if (hasOutput) "SUCCESS" else "RUNNING"
            }
        }

        // 0. Explicit Confirmation / Permission Request
        if (isWaitingPermission) {
            val isMcpWaiting = reqInteraction?.has("mcp") == true ||
                reqInteraction?.optString("type")?.contains("MCP", ignoreCase = true) == true ||
                step.has("mcpTool") || step.has("callMcpTool") || stepType.contains("MCP") ||
                genericArgs?.has("ServerName") == true || tcArgs?.has("ServerName") == true

            if (isMcpWaiting) {
                val mcp = step.optJSONObject("mcpTool")
                    ?: step.optJSONObject("callMcpTool")
                    ?: step.optJSONObject("mcp")
                    ?: reqInteraction?.optJSONObject("mcp")
                val sName = mcp?.optString("serverName", "")?.ifBlank {
                    tcArgs?.optString("ServerName", tcArgs.optString("serverName", genericArgs?.optString("ServerName", ""))) ?: ""
                } ?: ""
                val tName = mcp?.optJSONObject("toolCall")?.optString("name", "")?.ifBlank {
                    mcp?.optString("toolName", mcp?.optString("name", ""))
                }?.ifBlank {
                    tcArgs?.optString("ToolName", tcArgs.optString("toolName", genericArgs?.optString("ToolName", ""))) ?: ""
                } ?: ""
                val cmdDisplay = if (sName.isNotBlank() && tName.isNotBlank()) "$sName / $tName"
                    else if (tName.isNotBlank()) tName
                    else toolSummary.ifBlank { "MCP Tool" }
                val normName = if (sName.isNotBlank() && tName.isNotBlank()) "mcp_${sName}_$tName"
                    else if (tName.isNotBlank()) "mcp_$tName"
                    else "mcp_tool"
                return ToolCall(
                    id = makeToolId("tool_mcp_"),
                    name = normName,
                    command = cmdDisplay,
                    output = toolAction.ifBlank { "[Awaiting confirmation]" },
                    status = "PENDING_APPROVAL",
                    interactionType = "mcp"
                )
            }

            val rawToolName = perm?.optString("toolName", "")?.ifBlank {
                meta?.optJSONObject("toolCall")?.optString("name", "") ?: ""
            }?.ifBlank {
                step.optJSONObject("generic")?.optString("name", "") ?: ""
            } ?: ""

            val cmd = when {
                rc != null && rc.optString("commandLine", rc.optString("proposedCommandLine", rc.optString("CommandLine", rc.optString("command", "")))).isNotBlank() ->
                    rc.optString("commandLine", rc.optString("proposedCommandLine", rc.optString("CommandLine", rc.optString("command", ""))))
                perm != null && perm.has("command") && perm.optString("command", "").isNotBlank() ->
                    perm.optString("command")
                perm?.optJSONObject("resource")?.optString("target", "")?.isNotBlank() == true ->
                    perm.getJSONObject("resource").getString("target")
                tcArgs?.has("CommandLine") == true -> tcArgs.getString("CommandLine")
                tcArgs?.has("commandLine") == true -> tcArgs.getString("commandLine")
                tcArgs?.has("command") == true -> tcArgs.getString("command")
                tcArgs?.has("TargetFile") == true -> tcArgs.getString("TargetFile")
                tcArgs?.has("AbsolutePath") == true -> tcArgs.getString("AbsolutePath")
                tcArgs?.has("query") == true -> tcArgs.getString("query")
                generic?.optString("name", "")?.isNotBlank() == true -> generic.getString("name")
                else -> toolSummary.ifBlank { toolAction }
            }
            val normName = normalizeToolName(rawToolName.ifBlank { "bash" })
            return ToolCall(
                id = makeToolId("tool_"),
                name = normName,
                command = cmd.ifBlank { "Command execution" },
                output = toolAction.ifBlank { "[Awaiting confirmation]" },
                status = "PENDING_APPROVAL"
            )
        }

        val parsedArgs = parseArgsJson(genericArgs ?: tcArgs)
        val genericResultPayload = generic?.optJSONObject("result")?.optJSONObject("payload")

        // Generic Step Pattern
        if (stepType == "CORTEX_STEP_TYPE_GENERIC" || genericArgs != null || parsedArgs != null || (stepType.isBlank() && metaTc != null)) {
            val name = generic?.optString("name", "")?.takeIf { it.isNotBlank() }
                ?: metaTc?.optString("name", "")?.takeIf { it.isNotBlank() }
                ?: parsedArgs?.optString("ToolName", parsedArgs.optString("toolName", ""))?.takeIf { it.isNotBlank() }
                ?: ""

            if (name.isNotBlank() || (parsedArgs != null && parsedArgs.length() > 0)) {
                val mName = if (name.isBlank() && parsedArgs?.has("CommandLine") == true) "run_command" else name

                val cmd = when {
                    parsedArgs?.has("CommandLine") == true -> parsedArgs.optString("CommandLine", "")
                    parsedArgs?.has("commandLine") == true -> parsedArgs.optString("commandLine", "")
                    parsedArgs?.has("query") == true -> parsedArgs.optString("query", "")
                    parsedArgs?.has("AbsolutePath") == true -> parsedArgs.optString("AbsolutePath", "")
                    parsedArgs?.has("TargetFile") == true -> parsedArgs.optString("TargetFile", "")
                    parsedArgs?.has("DirectoryPath") == true -> parsedArgs.optString("DirectoryPath", "")
                    parsedArgs?.has("Prompt") == true -> parsedArgs.optString("Prompt", "")
                    parsedArgs?.has("ServerName") == true -> {
                        val s = parsedArgs.optString("ServerName")
                        val t = parsedArgs.optString("ToolName")
                        if (s.isNotBlank() && t.isNotBlank()) "$s / $t" else t
                    }
                    else -> toolSummary.ifBlank { toolAction }
                }

                val out = when {
                    genericResultPayload?.has("runCommand") == true -> genericResultPayload.getJSONObject("runCommand").optJSONObject("combinedOutput")?.optString("full", "") ?: ""
                    genericResultPayload?.has("searchWeb") == true -> genericResultPayload.getJSONObject("searchWeb").optString("summary", "")
                    genericResultPayload?.has("viewFile") == true -> genericResultPayload.getJSONObject("viewFile").optString("content", "")
                    genericResultPayload?.has("codeAction") == true -> genericResultPayload.getJSONObject("codeAction").optString("diff", "")
                    genericResultPayload?.has("mcpTool") == true -> genericResultPayload.getJSONObject("mcpTool").optString("result", "")
                    else -> generic?.optJSONObject("result")?.optString("payload", "") ?: ""
                }

                val exitCode = if (genericResultPayload?.has("runCommand") == true) genericResultPayload.getJSONObject("runCommand").optInt("exitCode", 0) else null

                val toolStatus = resolveStatus(out.isNotBlank() || exitCode != null, isWaitingPermission)
                return ToolCall(
                    id = makeToolId("tool_"),
                    name = normalizeToolName(mName.ifBlank { "unknown" }),
                    command = cmd,
                    output = out,
                    status = toolStatus,
                    exitCode = exitCode
                )
            }
        }

        // Run Command
        if (step.has("runCommand")) {
            val cmd = rc?.optString("commandLine",
                rc.optString("proposedCommandLine",
                    rc.optString("CommandLine",
                        rc.optString("command", ""))))?.takeIf { it.isNotBlank() }
                ?: tcArgs?.optString("CommandLine", tcArgs.optString("commandLine", tcArgs.optString("command", "")))
                ?: ""
            val out = rc?.optJSONObject("combinedOutput")?.optString("full") ?: rc?.optString("output", "") ?: ""
            val exitCode = if (rc != null && rc.has("exitCode")) rc.optInt("exitCode", 0) else null
            val isWaiting = isProposedRunCommand || isWaitingPermission || stepStatus == "CORTEX_STEP_STATUS_WAITING"
            val toolStatus = resolveStatus(out.isNotBlank() || exitCode != null, isWaiting)
            return ToolCall(
                id = makeToolId("tool_"),
                name = "bash",
                command = cmd.ifBlank { toolSummary },
                output = if (toolStatus == "PENDING_APPROVAL" && out.isBlank()) toolAction.ifBlank { "[Awaiting confirmation]" } else out,
                status = toolStatus,
                exitCode = exitCode
            )
        }

        // MCP Tool Call
        if (step.has("mcpTool") || step.has("callMcpTool") || step.has("mcp") || stepType.contains("MCP")) {
            val mcp = step.optJSONObject("mcpTool")
                ?: step.optJSONObject("callMcpTool")
                ?: step.optJSONObject("mcp")
                ?: JSONObject()

            val serverName = mcp.optString("serverName", mcp.optString("server", "")).trim()
            val tcObj = mcp.optJSONObject("toolCall")
                ?: mcp.optJSONObject("call")
                ?: mcp.optJSONObject("mcpToolCall")
            val rawToolName = tcObj?.optString("name", tcObj.optString("toolName", ""))
                ?.ifBlank { mcp.optString("name", mcp.optString("toolName", "")) }
                ?: ""
            val toolName = rawToolName.trim()

            val argsObj = tcObj?.optJSONObject("arguments")
                ?: tcObj?.optJSONObject("args")
                ?: mcp.optJSONObject("arguments")
                ?: mcp.optJSONObject("args")
            val argsJsonStr = tcObj?.optString("argumentsJson", "")?.takeIf { it.isNotBlank() }
                ?: tcObj?.optString("argsJson", "")?.takeIf { it.isNotBlank() }
                ?: mcp.optString("argumentsJson", "")?.takeIf { it.isNotBlank() }

            val parsedArgsObj = argsObj ?: if (!argsJsonStr.isNullOrBlank()) {
                try { JSONObject(argsJsonStr) } catch (_: Exception) { null }
            } else null

            val argsSummary = when {
                parsedArgsObj != null -> {
                    val keys = parsedArgsObj.keys().asSequence().toList()
                    if (keys.size == 1) {
                        val k = keys[0]
                        val v = parsedArgsObj.opt(k)?.toString() ?: ""
                        if (v.length > 80) "$k: ${v.take(80)}..." else "$k: $v"
                    } else {
                        parsedArgsObj.toString()
                    }
                }
                !argsJsonStr.isNullOrBlank() -> argsJsonStr.trim()
                else -> ""
            }

            val commandDisplay = when {
                serverName.isNotBlank() && toolName.isNotBlank() -> {
                    if (argsSummary.isNotBlank()) "$serverName / $toolName($argsSummary)"
                    else "$serverName / $toolName"
                }
                toolName.isNotBlank() -> {
                    if (argsSummary.isNotBlank()) "$toolName($argsSummary)"
                    else toolName
                }
                serverName.isNotBlank() -> "MCP: $serverName"
                else -> toolSummary.ifBlank { "MCP Tool" }
            }

            val resObj = mcp.opt("result") ?: mcp.opt("response") ?: mcp.opt("output")
            val resError = mcp.optString("error", "").takeIf { it.isNotBlank() }
            val outStr = when {
                resError != null -> "Error: $resError"
                resObj is JSONObject -> {
                    val contentArr = resObj.optJSONArray("content")
                    if (contentArr != null && contentArr.length() > 0) {
                        val sb = StringBuilder()
                        for (ci in 0 until contentArr.length()) {
                            val cObj = contentArr.optJSONObject(ci)
                            val text = cObj?.optString("text", "") ?: ""
                            if (text.isNotBlank()) {
                                if (sb.isNotEmpty()) sb.append("\n")
                                sb.append(text)
                            }
                        }
                        if (sb.isNotEmpty()) sb.toString() else resObj.toString(2)
                    } else {
                        resObj.optString("value", resObj.optString("text", resObj.toString(2)))
                    }
                }
                resObj is JSONArray -> resObj.toString(2)
                resObj != null && resObj.toString().isNotBlank() -> resObj.toString()
                else -> ""
            }

            val hasOutput = outStr.isNotBlank() || resError != null
            val isWaiting = isWaitingPermission
            val toolStatus = when {
                resError != null -> "FAILED"
                isWaiting -> "PENDING_APPROVAL"
                isStepRunning || (!hasOutput && !isStepDone) -> "RUNNING"
                else -> resolveStatus(hasOutput, isWaiting)
            }

            val normName = if (serverName.isNotBlank() && toolName.isNotBlank()) {
                "mcp_${serverName}_$toolName"
            } else if (toolName.isNotBlank()) {
                "mcp_$toolName"
            } else {
                "mcp_tool"
            }

            return ToolCall(
                id = makeToolId("tool_mcp_"),
                name = normName,
                command = commandDisplay,
                output = if (toolStatus == "PENDING_APPROVAL" && outStr.isBlank()) {
                    toolAction.ifBlank { "[Awaiting confirmation]" }
                } else if (toolStatus == "RUNNING" && outStr.isBlank()) {
                    toolAction.ifBlank { "Executing MCP tool..." }
                } else {
                    outStr
                },
                status = toolStatus,
                interactionType = "mcp"
            )
        }

        // View File
        if (step.has("viewFile") || stepType.contains("VIEW_FILE")) {
            val vf = step.optJSONObject("viewFile") ?: JSONObject()
            val rawPath = vf.optString("absolutePathUri", vf.optString("absolutePath", "")).removePrefix("file://")
            val startLine = vf.optInt("startLine", -1)
            val endLine = vf.optInt("endLine", -1)
            val lineRange = if (startLine > 0 && endLine > 0) " (lines $startLine-$endLine)"
                else if (endLine > 0) " (lines 1-$endLine)"
                else ""
            val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
            val cmd = if (fileName.isNotBlank()) "$fileName$lineRange" else toolSummary.ifBlank { "View File" }

            val mediaUri = vf.optJSONObject("mediaData")?.optString("uri", "") ?: ""
            val numLines = vf.optInt("numLines", 0)
            val numBytes = vf.optInt("numBytes", 0)
            val rawContent = vf.optString("content", "")

            val out = when {
                rawContent.isNotBlank() -> rawContent
                mediaUri.isNotBlank() -> "[Image: $mediaUri]"
                numLines > 0 || numBytes > 0 -> "$rawPath\n$numLines lines, $numBytes bytes"
                rawPath.isNotBlank() -> rawPath
                else -> toolAction.ifBlank { toolSummary }
            }

            return ToolCall(
                id = makeToolId("tool_view_"),
                name = "view_file",
                command = cmd,
                output = out,
                status = resolveStatus(out.isNotBlank())
            )
        }

        // List Directory
        if (step.has("listDirectory") || stepType.contains("LIST_DIRECTORY")) {
            val ld = step.optJSONObject("listDirectory") ?: JSONObject()
            val rawDir = ld.optString("directoryPathUri", ld.optString("directoryPath", "")).removePrefix("file://")
            val dirName = rawDir.substringAfterLast('/').ifBlank { rawDir }
            val cmd = if (dirName.isNotBlank()) dirName else toolSummary.ifBlank { "Directory" }

            val resultsArr = ld.optJSONArray("results")
            val out = if (resultsArr != null && resultsArr.length() > 0) {
                val items = mutableListOf<String>()
                for (idx in 0 until resultsArr.length()) {
                    val item = resultsArr.getJSONObject(idx)
                    val name = item.optString("name", "")
                    val isDir = item.optBoolean("isDir", false)
                    val size = item.optString("sizeBytes", "")
                    val prefix = if (isDir) "📁" else "📄"
                    val suffix = if (size.isNotBlank()) " ($size bytes)" else ""
                    items.add("$prefix $name$suffix")
                }
                items.joinToString("\n")
            } else {
                ld.optString("output", rawDir.ifBlank { toolAction.ifBlank { toolSummary } })
            }

            return ToolCall(
                id = makeToolId("tool_list_"),
                name = "list_dir",
                command = cmd,
                output = out,
                status = resolveStatus(out.isNotBlank())
            )
        }

        // Grep Search
        if (step.has("grepSearch") || stepType.contains("GREP")) {
            val gs = step.optJSONObject("grepSearch") ?: JSONObject()
            val query = gs.optString("query", "")
            val rawPath = gs.optString("searchPathUri", gs.optString("searchPath", "")).removePrefix("file://")
            val pathDisplay = rawPath.substringAfterLast('/').ifBlank { rawPath }
            val cmd = if (query.isNotBlank()) "\"$query\" in $pathDisplay" else toolSummary.ifBlank { "Search Code" }
            val total = gs.optInt("totalResults", -1)
            val commandRun = gs.optString("commandRun", "")
            val out = when {
                total >= 0 -> "$total matches found for \"$query\" in $rawPath\n$commandRun"
                commandRun.isNotBlank() -> commandRun
                else -> toolAction.ifBlank { toolSummary }
            }
            return ToolCall(
                id = makeToolId("tool_grep_"),
                name = "grep_search",
                command = cmd,
                output = out,
                status = resolveStatus(true)
            )
        }

        // Find Files
        if ((step.has("find") || stepType.contains("FIND")) && !stepType.contains("FINDINGS")) {
            val f = step.optJSONObject("find") ?: JSONObject()
            val pat = f.optString("pattern", "*")
            val rawDir = f.optString("searchDirectory", "").removePrefix("file://")
            val dir = rawDir.substringAfterLast('/')
            val cmd = if (dir.isNotBlank()) "$pat in $dir" else toolSummary.ifBlank { "Find $pat" }
            val out = f.optString("truncatedOutput", f.optString("output", rawDir.ifBlank { toolAction.ifBlank { toolSummary } }))
            return ToolCall(
                id = makeToolId("tool_find_"),
                name = "find",
                command = cmd,
                output = out,
                status = resolveStatus(out.isNotBlank())
            )
        }

        // Edit / Modify File
        if (step.has("modifyFile") || step.has("codeAction") || step.has("fileChange") ||
            stepType.contains("FILE_CHANGE") || stepType.contains("CODE_ACTION")) {
            val ca = step.optJSONObject("codeAction")
                ?: step.optJSONObject("modifyFile")
                ?: step.optJSONObject("fileChange")
                ?: JSONObject()
            val uri = ca.optString("uri", ca.optString("absolutePathUri", ca.optString("path", "")))
            val path = uri.removePrefix("file://")
            val fileName = path.substringAfterLast('/').ifBlank { path }
            val diff = ca.optString("diff", ca.optString("patch", ca.optString("content", "")))
            val cmd = if (fileName.isNotBlank()) fileName else toolSummary.ifBlank { "Edit File" }
            val out = diff.ifBlank { toolAction.ifBlank { toolSummary.ifBlank { "File modified" } } }
            return ToolCall(
                id = makeToolId("tool_edit_"),
                name = "edit_file",
                command = cmd,
                output = out,
                status = resolveStatus(true)
            )
        }

        // Search Web
        if (step.has("searchWeb") || stepType.contains("SEARCH_WEB")) {
            val sw = step.optJSONObject("searchWeb") ?: JSONObject()
            val query = sw.optString("query", "")
            val summary = sw.optString("summary", sw.optString("output", step.optString("content", "")))
            return ToolCall(
                id = makeToolId("tool_web_"),
                name = "web_search",
                command = query.ifBlank { toolSummary.ifBlank { "Web Search" } },
                output = summary.ifBlank { toolAction },
                status = resolveStatus(summary.isNotBlank())
            )
        }

        // Read URL Content
        if (step.has("readUrlContent") || stepType.contains("READ_URL")) {
            val ru = step.optJSONObject("readUrlContent") ?: JSONObject()
            val url = ru.optString("url", "")
            val content = ru.optString("markdown", ru.optString("content", ""))
            return ToolCall(
                id = makeToolId("tool_read_"),
                name = "read_url",
                command = url.ifBlank { toolSummary.ifBlank { "Read URL" } },
                output = content,
                status = resolveStatus(content.isNotBlank())
            )
        }

        // Generate Image
        if (step.has("generateImage") || stepType.contains("GENERATE_IMAGE")) {
            val gi = step.optJSONObject("generateImage") ?: JSONObject()
            val prompt = gi.optString("prompt", "")
            val gm = gi.optJSONObject("generatedMedia")
            val mimeType = gm?.optString("mimeType", "image/jpeg")?.ifBlank { "image/jpeg" } ?: "image/jpeg"
            val inlineData = gm?.optString("inlineData", gm.optString("inline_data", "")) ?: ""
            val rawUri = gm?.optString("uri", "")?.ifBlank { gi.optString("uri", "") } ?: ""
            val output = if (inlineData.isNotBlank()) "data:$mimeType;base64,$inlineData" else rawUri
            return ToolCall(
                id = makeToolId("tool_genimg_"),
                name = "generate_image",
                command = prompt.ifBlank { toolSummary.ifBlank { "Generate Image" } },
                output = output,
                status = resolveStatus(output.isNotBlank())
            )
        }

        return null
    }

    /**
     * Extracts structured error JSON payload from a trajectory step if present.
     */
    fun extractStepErrorJson(step: JSONObject, stepIndex: Int): String? {
        val errMsgObj = step.optJSONObject("errorMessage")
        val errObj = errMsgObj?.optJSONObject("error") ?: step.optJSONObject("error")
        val plannerErr = step.optJSONObject("plannerResponse")?.optJSONObject("error")
        val stepType = step.optString("type", "")
        val isErrorStep = errMsgObj != null || step.has("error") || plannerErr != null || stepType.contains("ERROR", ignoreCase = true)

        if (!isErrorStep && errObj == null && plannerErr == null) {
            return null
        }

        val target = errObj ?: plannerErr ?: step
        val userError = target.optString("userErrorMessage", "").takeIf { it.isNotBlank() }
            ?: if (stepType.contains("AUTH", ignoreCase = true)) "Authentication Required" else "Agent Execution Error"
        val shortError = target.optString("shortError", "").takeIf { it.isNotBlank() }
            ?: target.optString("message", "").takeIf { it.isNotBlank() }
            ?: target.optString("modelErrorMessage", "").takeIf { it.isNotBlank() }
            ?: ""
        val fullError = target.optString("fullError", "").takeIf { it.isNotBlank() } ?: ""
        val errorCode = if (target.has("errorCode")) target.optInt("errorCode") else if (target.has("code")) target.optInt("code") else null
        val errorId = target.optString("errorId", "")

        val title = when {
            shortError.contains("auth", ignoreCase = true) || userError.contains("auth", ignoreCase = true) -> "Authentication Required"
            shortError.contains("quota", ignoreCase = true) || shortError.contains("credit", ignoreCase = true) || errorCode == 429 -> "Quota / Usage Limit Exceeded"
            shortError.contains("model not found", ignoreCase = true) || shortError.contains("unknown model", ignoreCase = true) -> "Model Configuration Error"
            shortError.contains("network", ignoreCase = true) || shortError.contains("connect", ignoreCase = true) -> "Network / Server Connection Error"
            else -> "Agent Execution Error"
        }

        return JSONObject().apply {
            put("title", title)
            put("userMessage", userError)
            put("shortError", shortError)
            put("fullError", fullError)
            if (errorCode != null) put("errorCode", errorCode)
            if (errorId.isNotBlank()) put("errorId", errorId)
            if (fullError.isBlank() && errObj != null) put("rawJson", errObj.toString())
        }.toString()
    }

    /**
     * Extracts an error message from a trajectory step if present.
     */
    fun extractStepError(step: JSONObject): String? {
        val errMsgObj = step.optJSONObject("errorMessage")
        if (errMsgObj != null) {
            val err = errMsgObj.optJSONObject("error")
            val shortError = err?.optString("shortError", "")?.takeIf { it.isNotBlank() }
            val userError = err?.optString("userErrorMessage", "")?.takeIf { it.isNotBlank() }
            val directMsg = err?.optString("message", "")?.takeIf { it.isNotBlank() }
                ?: errMsgObj.optString("message", "").takeIf { it.isNotBlank() }

            if (!userError.isNullOrBlank() && !shortError.isNullOrBlank() && userError != shortError) {
                return "$userError: $shortError"
            }
            if (!shortError.isNullOrBlank()) return shortError
            if (!userError.isNullOrBlank()) return userError
            if (!directMsg.isNullOrBlank()) return directMsg
        }

        val errObj = step.optJSONObject("error")
        if (errObj != null) {
            val shortError = errObj.optString("shortError", "").takeIf { it.isNotBlank() }
            val userError = errObj.optString("userErrorMessage", "").takeIf { it.isNotBlank() }
            val message = errObj.optString("message", "").takeIf { it.isNotBlank() }
            if (!userError.isNullOrBlank() && !shortError.isNullOrBlank() && userError != shortError) {
                return "$userError: $shortError"
            }
            if (!shortError.isNullOrBlank()) return shortError
            if (!userError.isNullOrBlank()) return userError
            if (!message.isNullOrBlank()) return message
        }

        val directErr = step.optString("error", "").takeIf { it.isNotBlank() }
            ?: step.optString("executionError", "").takeIf { it.isNotBlank() }
        if (directErr != null) {
            return directErr
        }

        val plannerErr = step.optJSONObject("plannerResponse")?.optJSONObject("error")
        if (plannerErr != null) {
            val shortError = plannerErr.optString("shortError", "").takeIf { it.isNotBlank() }
            val msg = plannerErr.optString("message", "").takeIf { it.isNotBlank() }
            if (!shortError.isNullOrBlank()) return shortError
            if (!msg.isNullOrBlank()) return msg
        }

        val stepType = step.optString("type", "")
        if (stepType.contains("ERROR", ignoreCase = true)) {
            val desc = step.optString("description", "").takeIf { it.isNotBlank() }
            return desc ?: "Agent execution terminated due to error."
        }

        val status = step.optString("status", "")
        if (status.contains("ERROR", ignoreCase = true) || status.contains("FAIL", ignoreCase = true)) {
            val shortStatus = status.removePrefix("CORTEX_STEP_STATUS_").lowercase().replace('_', ' ')
            return "Model step error ($shortStatus)"
        }
        return null
    }
}
