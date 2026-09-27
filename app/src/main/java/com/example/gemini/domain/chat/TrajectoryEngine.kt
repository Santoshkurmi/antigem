package com.example.gemini.domain.chat

import android.util.Log
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.dto.*
import com.example.gemini.domain.model.ArtifactSnapshot
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChatTurn
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.domain.model.TurnBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministic State Engine for AGY Hub Conversations.
 *
 * Replaces all regex scraping, HTML comment markers (<!-- tool_call:... -->),
 * and uncoordinated maps with a two-tier turn cache:
 * 1. Completed historical turns (immutable, zero re-parsing on live updates).
 * 2. Active turn steps (sparse stepIndex map, O(1) cumulative replacement).
 */
class TrajectoryEngine {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    private val _artifacts = MutableStateFlow<List<ArtifactSnapshot>>(emptyList())
    val artifacts: StateFlow<List<ArtifactSnapshot>> = _artifacts.asStateFlow()

    // Tier 1: Immutable cache of finished turns (Turns 0 to N-1)
    private val completedTurns = mutableListOf<ChatTurn>()

    // Tier 2: Sparse step map for the active turn currently receiving live chunks
    private val activeStepsMap = LinkedHashMap<Int, CortexStepDto>()

    val userRespondedStepIndices = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    var trajectoryId: String = ""
        private set
    var conversationId: String = ""
        private set
    var isRunning: Boolean = false
        private set
    var isWaitingInteraction: Boolean = false
        private set

    private var activeTurnStartStep: Int = 0
    private var pendingUserTurn: ChatTurn.User? = null

    /**
     * Resets state when switching conversations.
     */
    fun reset(newConversationId: String = "", force: Boolean = false) {
        if (!force && pendingUserTurn != null && (conversationId.isBlank() || conversationId == newConversationId)) {
            if (newConversationId.isNotBlank()) {
                conversationId = newConversationId
            }
            trajectoryId = ""
            userRespondedStepIndices.clear()
            activeStepsMap.clear()
            return
        }
        conversationId = newConversationId
        trajectoryId = ""
        isRunning = false
        isWaitingInteraction = false
        activeTurnStartStep = 0
        userRespondedStepIndices.clear()
        completedTurns.clear()
        activeStepsMap.clear()
        pendingUserTurn = null
        _turns.value = emptyList()
        _artifacts.value = emptyList()
    }

    /**
     * Optimistically registers user prompt so UI feed never flickers or drops the user message
     * during the few milliseconds between sending and wire frame receipt.
     */
    fun submitUserPrompt(text: String, attachments: List<ChatAttachment> = emptyList(), convId: String = "") {
        if (convId.isNotBlank()) {
            conversationId = convId
        }
        finalizeActiveTurn()
        pendingUserTurn = ChatTurn.User(
            stepIndex = activeTurnStartStep,
            text = text,
            attachments = attachments
        )
        isRunning = true
        _turns.value = getTurns()
    }

    fun cancelRunning() {
        isRunning = false
        pendingUserTurn?.let { pending ->
            val alreadyPresent = completedTurns.any { it is ChatTurn.User && it.stepIndex == pending.stepIndex }
            if (!alreadyPresent) {
                completedTurns.add(pending)
            }
        }
        pendingUserTurn = null
        _turns.value = getTurns()
    }

    fun markStepResponded(stepIndex: Int) {
        userRespondedStepIndices.add(stepIndex)
    }

    fun unmarkStepResponded(stepIndex: Int) {
        userRespondedStepIndices.remove(stepIndex)
    }

    fun optimisticUpdateStepStatus(stepIndex: Int, status: String, output: String? = null) {
        val existing = activeStepsMap[stepIndex]
        if (existing != null) {
            val updated = existing.copy(
                status = status,
                generic = if (output != null) {
                    val curGen = existing.generic
                    curGen?.copy(
                        result = curGen.result?.copy(
                            payload = kotlinx.serialization.json.buildJsonObject {
                                put("output", kotlinx.serialization.json.JsonPrimitive(output))
                            }
                        )
                    ) ?: curGen
                } else existing.generic,
                runCommand = if (output != null) {
                    existing.runCommand?.copy(output = output) ?: existing.runCommand
                } else existing.runCommand
            )
            activeStepsMap[stepIndex] = updated
            _turns.value = getTurns()
        }
    }

    fun ingestFrame(frame: AgyStreamFrameDto): List<ChatTurn> {
        val update = frame.update
        val convId = update?.conversationId?.takeIf { it.isNotBlank() }
            ?: frame.conversationId.takeIf { it.isNotBlank() }
        val trajId = update?.trajectoryId?.takeIf { it.isNotBlank() }
            ?: frame.trajectoryId.takeIf { it.isNotBlank() }
        val status = update?.status?.takeIf { it.isNotBlank() }
            ?: frame.status

        if (!convId.isNullOrBlank()) conversationId = convId
        if (!trajId.isNullOrBlank()) trajectoryId = trajId

        val daemonRunning = status == CascadeRunStatuses.RUNNING
        val daemonIdle = status == CascadeRunStatuses.IDLE || update?.fullyIdle == true || frame.fullyIdle

        val stepsUpdate = update?.mainTrajectoryUpdate?.stepsUpdate
            ?: update?.stepsUpdate
            ?: frame.mainTrajectoryUpdate?.stepsUpdate
            ?: frame.stepsUpdate

        val effectiveSteps = stepsUpdate?.steps ?: frame.steps
        val effectiveIndices = stepsUpdate?.indices ?: effectiveSteps?.indices?.toList() ?: emptyList()
        val totalLength = stepsUpdate?.totalLength ?: effectiveSteps?.size ?: 0

        Log.d("CHAT_OPEN_DEBUG", "⚙️ [TrajectoryEngine.ingestFrame] convId=$conversationId, trajId=$trajectoryId, status=$status, stepsCount=${effectiveSteps?.size ?: 0}, indicesPreview=${effectiveIndices.take(10)}")

        if (!effectiveSteps.isNullOrEmpty()) {
            val isInitialFullSync = (effectiveIndices.firstOrNull() == 0) || (completedTurns.isEmpty() && activeStepsMap.isEmpty())
            Log.d("CHAT_OPEN_DEBUG", "⚙️ [TrajectoryEngine.ingestFrame] isInitialFullSync=$isInitialFullSync (indices.first=${effectiveIndices.firstOrNull()}, completedTurns=${completedTurns.size}, activeSteps=${activeStepsMap.size})")
            if (isInitialFullSync) {
                ingestInitialFullSync(effectiveIndices, effectiveSteps, daemonRunning)
            } else {
                ingestIncrementalDeltas(effectiveIndices, effectiveSteps, totalLength)
            }
        }

        val frameLastStepError = update?.mainTrajectoryUpdate?.lastStepError
            ?: update?.lastStepError
            ?: frame.mainTrajectoryUpdate?.lastStepError
            ?: frame.lastStepError

        if (frameLastStepError != null) {
            val errStepIdx = activeStepsMap.keys.maxOrNull() ?: activeTurnStartStep
            val existingStep = activeStepsMap[errStepIdx]
            if (existingStep != null && (existingStep.errorMessage != null || existingStep.error != null)) {
                // Enrich existing error step with fullError if missing
                val curErr = existingStep.errorMessage?.error ?: existingStep.error
                val baseErr = curErr ?: frameLastStepError
                val enrichedErr = baseErr.copy(
                    fullError = if (curErr?.fullError.isNullOrBlank()) frameLastStepError.fullError else curErr.fullError,
                    shortError = if (curErr?.shortError.isNullOrBlank()) frameLastStepError.shortError else curErr.shortError,
                    userErrorMessage = if (curErr?.userErrorMessage.isNullOrBlank()) frameLastStepError.userErrorMessage else curErr.userErrorMessage,
                    errorCode = curErr?.errorCode ?: frameLastStepError.errorCode,
                    errorId = if (curErr?.errorId.isNullOrBlank()) frameLastStepError.errorId else curErr.errorId
                )
                activeStepsMap[errStepIdx] = existingStep.copy(
                    errorMessage = CortexErrorMessageDto(error = enrichedErr, shouldShowUser = true),
                    error = enrichedErr
                )
            } else if (activeStepsMap.none { (_, s) -> s.errorMessage != null || s.error != null || s.type.contains("ERROR", ignoreCase = true) }) {
                // Add error step if not already present in active turn
                val newErrorStep = CortexStepDto(
                    type = "CORTEX_STEP_TYPE_ERROR_MESSAGE",
                    status = CortexStepStatuses.DONE,
                    errorMessage = CortexErrorMessageDto(error = frameLastStepError, shouldShowUser = true),
                    error = frameLastStepError
                )
                activeStepsMap[errStepIdx] = newErrorStep
            }
        }

        // If a user prompt was optimistically submitted and is in flight to daemon,
        // keep isRunning = true until daemon actually acknowledges or finishes.
        isRunning = daemonRunning || (pendingUserTurn != null)
        val isIdle = daemonIdle && (pendingUserTurn == null)

        val hasPendingInteraction = activeStepsMap.any { (stepIdx, step) ->
            val isAskChoice = step.metadata?.toolCall?.name == "ask_question" || step.generic?.name == "ask_question" || step.type == CortexStepTypes.ASK_QUESTION
            val isWaiting = step.status == CortexStepStatuses.WAITING || step.requestedInteraction != null ||
                    (isAskChoice && step.status != CortexStepStatuses.DONE && step.status != CortexStepStatuses.CANCELED && step.status != CortexStepStatuses.ERROR)
            isWaiting && !userRespondedStepIndices.contains(stepIdx)
        }
        isWaitingInteraction = hasPendingInteraction

        // If the cascade entered IDLE status and no tool is waiting for permission,
        // promote pendingUserTurn only if daemon never emitted a user input step and completedTurns doesn't have it
        if (isIdle && !isWaitingInteraction && !isRunning) {
            pendingUserTurn?.let { pending ->
                val alreadyPresent = completedTurns.any { it is ChatTurn.User && it.stepIndex == pending.stepIndex }
                if (!alreadyPresent) {
                    completedTurns.add(pending)
                }
                pendingUserTurn = null
            }
        }

        val artifactUpdate = update?.mainTrajectoryUpdate?.artifactSnapshotsUpdate
            ?: update?.artifactSnapshotsUpdate
            ?: frame.mainTrajectoryUpdate?.artifactSnapshotsUpdate
            ?: frame.artifactSnapshotsUpdate

        if (artifactUpdate != null && artifactUpdate.artifactSnapshots.isNotEmpty()) {
            val mapped = artifactUpdate.artifactSnapshots.map { dto ->
                ArtifactSnapshot(
                    name = dto.artifactName,
                    absoluteUri = dto.artifactAbsoluteUri,
                    lastEdited = dto.lastEdited,
                    summary = dto.artifactMetadata?.effectiveSummary ?: "",
                    requestFeedback = dto.artifactMetadata?.effectiveRequestFeedback == true,
                    userFacing = dto.artifactMetadata?.effectiveUserFacing == true
                )
            }
            if (mapped.isNotEmpty()) {
                val current = _artifacts.value.toMutableList()
                mapped.forEach { incoming ->
                    val idx = current.indexOfFirst {
                        (it.absoluteUri.isNotBlank() && it.absoluteUri == incoming.absoluteUri) ||
                        (it.name.isNotBlank() && it.name == incoming.name)
                    }
                    if (idx >= 0) {
                        current[idx] = incoming
                    } else {
                        current.add(incoming)
                    }
                }
                _artifacts.value = current
            }
        }

        val result = getTurns()
        _turns.value = result
        return result
    }

    /**
     * Parses Chunk 0 (the complete historical trajectory) into completed turns.
     */
    private fun ingestInitialFullSync(indices: List<Int>, steps: List<CortexStepDto>, cascadeRunning: Boolean) {
        val savedPending = pendingUserTurn
        completedTurns.clear()
        activeStepsMap.clear()
        pendingUserTurn = null

        var currentTurnBlocks = mutableListOf<TurnBlock>()
        var currentTurnSteps = mutableListOf<CortexStepDto>()
        var lastUserStepArrayIndex = -1
        var hasUserInputStep = false

        for (i in steps.indices) {
            val stepIndex = indices.getOrNull(i) ?: i
            val step = steps[i]

            if (step.type == CortexStepTypes.USER_INPUT || step.userInput != null) {
                hasUserInputStep = true
                // If an assistant turn was building, flush it to completedTurns
                if (currentTurnBlocks.isNotEmpty()) {
                    val prevUserStepIdx = if (lastUserStepArrayIndex >= 0) (indices.getOrNull(lastUserStepArrayIndex) ?: lastUserStepArrayIndex) else -1
                    val turnId = "${conversationId}_${prevUserStepIdx + 1}"
                    val tokenUsage = computeTokenUsage(currentTurnSteps)
                    completedTurns.add(ChatTurn.Assistant(turnId = turnId, blocks = currentTurnBlocks.toList(), tokenUsage = tokenUsage))
                    currentTurnBlocks = mutableListOf()
                    currentTurnSteps = mutableListOf()
                }

                // Add User turn
                val userText = extractUserText(step.userInput)
                val attachments = extractUserAttachments(step.userInput, stepIndex)
                completedTurns.add(ChatTurn.User(stepIndex = stepIndex, text = userText, attachments = attachments))
                lastUserStepArrayIndex = i
                activeTurnStartStep = stepIndex + 1
            } else {
                // Assistant step
                currentTurnSteps.add(step)
                extractStepBlocks(step, stepIndex, isStreaming = false, blocks = currentTurnBlocks)
            }
        }

        if (!hasUserInputStep && savedPending != null) {
            pendingUserTurn = savedPending
        }

        // If the conversation is currently running or waiting, the trailing assistant steps belong in activeStepsMap
        val hasActiveWork = cascadeRunning || currentTurnBlocks.any {
            it is TurnBlock.Permission || (it is TurnBlock.Tool && it.toolCall.status == "PENDING_APPROVAL")
        }
        if (hasActiveWork && currentTurnBlocks.isNotEmpty()) {
            // Keep trailing blocks in activeStepsMap for live updates
            val startIdx = if (lastUserStepArrayIndex >= 0) lastUserStepArrayIndex + 1 else 0
            for (i in startIdx until steps.size) {
                val stepIndex = indices.getOrNull(i) ?: i
                activeStepsMap[stepIndex] = steps[i]
            }
        } else if (currentTurnBlocks.isNotEmpty()) {
            val lastUserStepIdx = if (lastUserStepArrayIndex >= 0) (indices.getOrNull(lastUserStepArrayIndex) ?: lastUserStepArrayIndex) else -1
            val turnId = "${conversationId}_${lastUserStepIdx + 1}"
            val tokenUsage = computeTokenUsage(currentTurnSteps)
            completedTurns.add(ChatTurn.Assistant(turnId = turnId, blocks = currentTurnBlocks.toList(), isStreaming = false, tokenUsage = tokenUsage))
        }
    }

    /**
     * Ingests live incremental deltas (Chunks 1..N).
     * Only touches activeStepsMap. Completed past turns are NEVER re-parsed.
     */
    private fun ingestIncrementalDeltas(indices: List<Int>, steps: List<CortexStepDto>, totalLength: Int) {
        // Rollback / Undo check: if totalLength decreased, drop rolled-back steps
        if (totalLength in 1..activeStepsMap.size) {
            activeStepsMap.keys.retainAll { it < totalLength }
        }

        for (i in steps.indices) {
            val stepIndex = indices.getOrNull(i) ?: (activeTurnStartStep + i)
            val step = steps[i]

            if (step.type == CortexStepTypes.USER_INPUT || step.userInput != null) {
                // A new prompt was sent by the user!
                finalizeActiveTurn()
                pendingUserTurn = null

                val userText = extractUserText(step.userInput)
                val attachments = extractUserAttachments(step.userInput, stepIndex)
                val userTurn = ChatTurn.User(stepIndex = stepIndex, text = userText, attachments = attachments)
                val existingIdx = completedTurns.indexOfFirst { it is ChatTurn.User && it.stepIndex == stepIndex }
                if (existingIdx >= 0) {
                    completedTurns[existingIdx] = userTurn
                } else {
                    completedTurns.add(userTurn)
                }

                activeTurnStartStep = stepIndex + 1
                activeStepsMap.clear()
            } else {
                // Live assistant step (Tool, Thinking, Response, System notice)
                // Overwrite with latest cumulative state for this stepIndex:
                activeStepsMap[stepIndex] = step
            }
        }
    }

    /**
     * Calculates the aggregated TokenUsage for a set of steps in a turn.
     * Only considers completed PLANNER_RESPONSE steps with valid modelUsage.
     */
    private fun computeTokenUsage(steps: Iterable<CortexStepDto>): TokenUsage? {
        val plannerSteps = steps.filter {
            (it.type == CortexStepTypes.PLANNER_RESPONSE || it.type == "CORTEX_STEP_TYPE_PLANNER_RESPONSE") &&
            (it.status == CortexStepStatuses.DONE || it.status == "CORTEX_STEP_STATUS_DONE") &&
            it.metadata?.modelUsage != null
        }
        if (plannerSteps.isEmpty()) return null

        val outputTokens = plannerSteps.sumOf { it.metadata?.modelUsage?.outputTokens?.toIntOrNull() ?: 0 }
        val promptTokens = plannerSteps.lastOrNull()?.metadata?.modelUsage?.inputTokens?.toIntOrNull() ?: 0
        val cachedTokens = plannerSteps.sumOf { it.metadata?.modelUsage?.cacheReadTokens?.toIntOrNull() ?: 0 }

        if (outputTokens == 0 && promptTokens == 0 && cachedTokens == 0) return null

        return TokenUsage(
            promptTokens = promptTokens,
            outputTokens = outputTokens,
            cachedTokens = cachedTokens,
            totalTokens = promptTokens + outputTokens
        )
    }

    /**
     * Moves the active turn into the completed turns cache when the turn finishes.
     */
    private fun finalizeActiveTurn() {
        if (activeStepsMap.isEmpty()) return

        val blocks = mutableListOf<TurnBlock>()
        for ((stepIndex, step) in activeStepsMap.toSortedMap()) {
            extractStepBlocks(step, stepIndex, isStreaming = false, blocks = blocks)
        }

        if (blocks.isNotEmpty()) {
            val turnId = "${conversationId}_$activeTurnStartStep"
            val tokenUsage = computeTokenUsage(activeStepsMap.values)
            val existingIndex = completedTurns.indexOfLast { it is ChatTurn.Assistant && it.turnId == turnId }
            if (existingIndex >= 0) {
                val existingTurn = completedTurns[existingIndex] as ChatTurn.Assistant
                val mergedBlocks = (existingTurn.blocks.filterNot { eb -> blocks.any { it.stepIndex == eb.stepIndex } } + blocks)
                    .sortedBy { it.stepIndex }
                completedTurns[existingIndex] = existingTurn.copy(blocks = mergedBlocks, isStreaming = false, tokenUsage = tokenUsage ?: existingTurn.tokenUsage)
            } else {
                completedTurns.add(ChatTurn.Assistant(turnId = turnId, blocks = blocks.toList(), isStreaming = false, tokenUsage = tokenUsage))
            }
        }

        activeStepsMap.clear()
        isWaitingInteraction = false
    }

    /**
     * Returns the full conversational feed: Completed Turns (cached) + Active Turn (lightweight).
     */
    fun getTurns(): List<ChatTurn> {
        val baseTurns = if (pendingUserTurn != null && completedTurns.none { it is ChatTurn.User && it.stepIndex == pendingUserTurn!!.stepIndex }) {
            completedTurns + pendingUserTurn!!
        } else {
            completedTurns.toList()
        }

        val nextAssistantStartStep = if (pendingUserTurn != null) activeTurnStartStep + 1 else activeTurnStartStep
        val turnId = "${conversationId}_$nextAssistantStartStep"
        val existingAssistantIdx = baseTurns.indexOfLast { it is ChatTurn.Assistant && it.turnId == turnId }

        if (activeStepsMap.isEmpty()) {
            if (isRunning && !isWaitingInteraction) {
                if (existingAssistantIdx >= 0) {
                    val existingTurn = baseTurns[existingAssistantIdx] as ChatTurn.Assistant
                    val updatedTurn = existingTurn.copy(isStreaming = true)
                    return baseTurns.toMutableList().apply { set(existingAssistantIdx, updatedTurn) }
                }
                val activeTurn = ChatTurn.Assistant(
                    turnId = turnId,
                    blocks = emptyList(),
                    isStreaming = true
                )
                return baseTurns + activeTurn
            }
            return baseTurns
        }

        val activeBlocks = mutableListOf<TurnBlock>()
        var waitingFound = false

        for ((stepIndex, step) in activeStepsMap.toSortedMap()) {
            val isStepRunning = step.status == CortexStepStatuses.RUNNING ||
                    step.status == CortexStepStatuses.PENDING ||
                    step.status == CortexStepStatuses.GENERATING ||
                    step.status == CortexStepStatuses.QUEUED

            val isAskChoice = step.metadata?.toolCall?.name == "ask_question" || step.generic?.name == "ask_question" || step.type == CortexStepTypes.ASK_QUESTION
            val isWaiting = step.status == CortexStepStatuses.WAITING || step.requestedInteraction != null ||
                    (isAskChoice && step.status != CortexStepStatuses.DONE && step.status != CortexStepStatuses.CANCELED && step.status != CortexStepStatuses.ERROR)

            if (isWaiting && !userRespondedStepIndices.contains(stepIndex)) {
                waitingFound = true
            }

            extractStepBlocks(step, stepIndex, isStreaming = isStepRunning && isRunning, blocks = activeBlocks)
        }

        isWaitingInteraction = waitingFound

        val finalBlocks = if (existingAssistantIdx >= 0) {
            val existingTurn = baseTurns[existingAssistantIdx] as ChatTurn.Assistant
            (existingTurn.blocks.filterNot { eb -> activeBlocks.any { it.stepIndex == eb.stepIndex } } + activeBlocks)
                .sortedBy { it.stepIndex }
        } else {
            activeBlocks.toList()
        }

        val activeTokenUsage = computeTokenUsage(activeStepsMap.values)
        return if (finalBlocks.isNotEmpty() || (isRunning && !isWaitingInteraction)) {
            val activeTurn = ChatTurn.Assistant(
                turnId = turnId,
                blocks = finalBlocks,
                isStreaming = isRunning && !isWaitingInteraction,
                tokenUsage = activeTokenUsage
            )
            if (existingAssistantIdx >= 0) {
                baseTurns.toMutableList().apply {
                    set(existingAssistantIdx, activeTurn)
                }
            } else {
                baseTurns + activeTurn
            }
        } else {
            baseTurns
        }
    }

    /**
     * Converts current turns to [ChatMessage]s for UI consumption.
     * Preserves exact step sequence, inline tool markers, thoughts, and attachments.
     */
    fun toChatMessages(convId: String = conversationId): List<ChatMessage> {
        val currentTurns = getTurns()
        val messages = mutableListOf<ChatMessage>()
        val seenMsgIds = mutableSetOf<String>()

        for (turn in currentTurns) {
            when (turn) {
                is ChatTurn.User -> {
                    val uId = "user_${convId}_${turn.stepIndex}"
                    if (seenMsgIds.contains(uId)) {
                        continue
                    }
                    seenMsgIds.add(uId)
                    messages.add(
                        ChatMessage(
                            id = uId,
                            conversationId = convId,
                            role = MessageRole.USER,
                            content = turn.text,
                            attachments = turn.attachments,
                            stepIndex = turn.stepIndex,
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                is ChatTurn.Assistant -> {
                    val thoughts = mutableListOf<String>()
                    var maxDuration: Long? = null
                    val toolCalls = mutableListOf<ToolCall>()
                    val contentParts = mutableListOf<String>()

                    for (block in turn.blocks) {
                        when (block) {
                            is TurnBlock.Thinking -> {
                                val thoughtText = block.thought.trim()
                                val streamTag = if (block.isStreaming) ":streaming" else ""
                                val durTag = if (block.durationMs != null && block.durationMs > 0) ":${block.durationMs}" else ""
                                if (thoughtText.isNotBlank()) {
                                    contentParts.add("<!-- thought$streamTag$durTag -->\n$thoughtText\n<!-- /thought -->")
                                    thoughts.add(thoughtText)
                                } else if (block.isStreaming) {
                                    contentParts.add("<!-- thought:streaming -->\nThinking...\n<!-- /thought -->")
                                }
                                if (block.durationMs != null) {
                                    maxDuration = maxOf(maxDuration ?: 0L, block.durationMs)
                                }
                            }
                            is TurnBlock.Text -> {
                                if (block.markdown.isNotBlank()) {
                                    contentParts.add(block.markdown.trim())
                                }
                            }
                            is TurnBlock.Tool -> {
                                if (toolCalls.none { it.stepIndex == block.stepIndex }) {
                                    toolCalls.add(block.toolCall)
                                    contentParts.add("<!-- tool_call:${block.toolCall.id} -->")
                                }
                            }
                            is TurnBlock.Permission -> {
                                if (toolCalls.none { it.stepIndex == block.stepIndex }) {
                                    val permCmd = block.interaction.permission?.resource?.target?.takeIf { it.isNotBlank() }
                                        ?: block.interaction.permission?.actionDescription?.takeIf { it.isNotBlank() }
                                        ?: "Permission Required"
                                    val permTool = ToolCall(
                                        id = block.stepIndex.toString(),
                                        name = block.interaction.permission?.resource?.action ?: "permission",
                                        toolType = ToolType.BASH,
                                        command = permCmd,
                                        status = if (userRespondedStepIndices.contains(block.stepIndex)) "RUNNING" else "PENDING_APPROVAL",
                                        stepIndex = block.stepIndex,
                                        trajectoryId = block.trajectoryId
                                    )
                                    toolCalls.add(permTool)
                                    contentParts.add("<!-- tool_call:${permTool.id} -->")
                                }
                            }
                            is TurnBlock.ErrorNotice -> {
                                val errJson = kotlinx.serialization.json.buildJsonObject {
                                    put("title", kotlinx.serialization.json.JsonPrimitive(block.title))
                                    put("userMessage", kotlinx.serialization.json.JsonPrimitive(block.userMessage))
                                    put("shortError", kotlinx.serialization.json.JsonPrimitive(block.shortError))
                                    put("fullError", kotlinx.serialization.json.JsonPrimitive(block.fullError))
                                    if (block.errorCode != null) put("errorCode", kotlinx.serialization.json.JsonPrimitive(block.errorCode))
                                    if (block.errorId.isNotBlank()) put("errorId", kotlinx.serialization.json.JsonPrimitive(block.errorId))
                                    if (block.rawJson.isNotBlank()) put("rawJson", kotlinx.serialization.json.JsonPrimitive(block.rawJson))
                                }.toString()
                                contentParts.add("<!-- error:${block.stepIndex} -->\n$errJson\n<!-- /error -->")
                            }
                            is TurnBlock.SystemNotice -> {
                                if (block.content.isNotBlank()) {
                                    contentParts.add("ℹ️ ${block.title}: ${block.content}")
                                }
                            }
                        }
                    }

                    val combinedContent = contentParts.joinToString("\n\n").trim()
                    val combinedThought = thoughts.joinToString("\n\n").trim().takeIf { it.isNotBlank() }

                    var aId = turn.turnId.ifBlank { "asst_${convId}_$activeTurnStartStep" }
                    var counter = 1
                    while (seenMsgIds.contains(aId)) {
                        aId = "${turn.turnId}_$counter"
                        counter++
                    }
                    seenMsgIds.add(aId)

                    messages.add(
                        ChatMessage(
                            id = aId,
                            conversationId = convId,
                            role = MessageRole.ASSISTANT,
                            content = combinedContent,
                            thoughtText = combinedThought,
                            thoughtDurationMs = maxDuration,
                            toolCalls = toolCalls,
                            isStreaming = turn.isStreaming,
                            tokenUsage = turn.tokenUsage
                        )
                    )
                }
            }
        }
        Log.d("CHAT_OPEN_DEBUG", "📋 [TrajectoryEngine.toChatMessages] convId=$convId produced ${messages.size} messages from ${currentTurns.size} turns (roles: ${messages.map { it.role }})")
        return messages
    }

    /**
     * Extracts blocks strictly in-order from a single CortexStepDto.
     */
    private fun extractStepBlocks(
        step: CortexStepDto,
        stepIndex: Int,
        isStreaming: Boolean,
        blocks: MutableList<TurnBlock>
    ) {
        // 1. User Input (handled as ChatTurn.User, skip here)
        if (step.type == CortexStepTypes.USER_INPUT || step.userInput != null) {
            return
        }

        // 2. Planner Response (Thinking + Response)
        if (step.type == CortexStepTypes.PLANNER_RESPONSE || step.plannerResponse != null) {
            val thinking = step.plannerResponse?.thinking
            val response = step.plannerResponse?.response
            if (!thinking.isNullOrBlank()) {
                blocks.add(TurnBlock.Thinking(stepIndex = stepIndex, thought = thinking, isStreaming = isStreaming))
            }
            if (!response.isNullOrBlank()) {
                blocks.add(TurnBlock.Text(stepIndex = stepIndex, markdown = response, isStreaming = isStreaming))
            } else if (thinking.isNullOrBlank() && (step.status == CortexStepStatuses.GENERATING || isStreaming)) {
                blocks.add(TurnBlock.Thinking(stepIndex = stepIndex, thought = "", isStreaming = true))
            }
            return
        }

        // 3. System Messages
        if (step.type == CortexStepTypes.SYSTEM_MESSAGE && step.systemMessage != null) {
            blocks.add(
                TurnBlock.SystemNotice(
                    stepIndex = stepIndex,
                    title = step.systemMessage.title,
                    content = step.systemMessage.content
                )
            )
            return
        }

        // 4. Error messages and notices
        val stepError = step.errorMessage?.error ?: step.error
        val isErrorType = step.type == CortexStepTypes.ERROR_MESSAGE ||
                step.type == "CORTEX_STEP_TYPE_ERROR_MESSAGE" ||
                step.type.contains("ERROR", ignoreCase = true) ||
                step.status == CortexStepStatuses.ERROR ||
                step.errorMessage != null ||
                step.error != null

        if (isErrorType) {
            val userMsg = stepError?.userErrorMessage?.takeIf { it.isNotBlank() }
                ?: if (step.type.contains("AUTH", ignoreCase = true)) "Authentication Required" else "Agent Execution Error"
            val shortErr = stepError?.shortError?.takeIf { it.isNotBlank() }
                ?: stepError?.message?.takeIf { it.isNotBlank() }
                ?: stepError?.modelErrorMessage?.takeIf { it.isNotBlank() }
                ?: ""
            val fullErr = stepError?.fullError?.takeIf { it.isNotBlank() } ?: ""
            val code = stepError?.errorCode ?: stepError?.code
            val errId = stepError?.errorId ?: ""

            val title = when {
                shortErr.contains("auth", ignoreCase = true) || userMsg.contains("auth", ignoreCase = true) -> "Authentication Required"
                shortErr.contains("quota", ignoreCase = true) || shortErr.contains("credit", ignoreCase = true) || code == 429 -> "Quota / Usage Limit Exceeded"
                shortErr.contains("model not found", ignoreCase = true) || shortErr.contains("unknown model", ignoreCase = true) -> "Model Configuration Error"
                shortErr.contains("network", ignoreCase = true) || shortErr.contains("connect", ignoreCase = true) -> "Network / Server Connection Error"
                else -> "Agent Execution Error"
            }

            blocks.add(
                TurnBlock.ErrorNotice(
                    stepIndex = stepIndex,
                    title = title,
                    userMessage = userMsg,
                    shortError = shortErr,
                    fullError = fullErr,
                    errorCode = code,
                    errorId = errId,
                    rawJson = if (fullErr.isBlank() && stepError != null) stepError.toString() else ""
                )
            )
            return
        }

        // 5. Tool / Step execution (including those waiting for permission approval)
        val toolCall = extractToolCallFromStep(step, stepIndex)
        if (toolCall != null) {
            blocks.add(TurnBlock.Tool(stepIndex = stepIndex, toolCall = toolCall))
        } else if (step.status == CortexStepStatuses.WAITING || step.requestedInteraction != null) {
            // Standalone permission request without an associated tool call
            step.requestedInteraction?.let { req ->
                blocks.add(TurnBlock.Permission(stepIndex = stepIndex, trajectoryId = trajectoryId, interaction = req))
            }
        }
    }

    /**
     * Extracts a domain ToolCall from a typed CortexStepDto without regex,
     * surfacing rich details, file content/diffs, terminal stdout, and full payloads.
     */
    private fun extractToolCallFromStep(step: CortexStepDto, stepIndex: Int): ToolCall? {
        val meta = step.metadata
        val tcMeta = meta?.toolCall

        val rawName = tcMeta?.name?.takeIf { it.isNotBlank() }
            ?: step.generic?.name?.takeIf { it.isNotBlank() }
            ?: step.type.removePrefix("CORTEX_STEP_TYPE_").lowercase().takeIf { it.isNotBlank() && it != "generic" }
            ?: meta?.toolSummary?.takeIf { it.isNotBlank() }
            ?: "unknown_tool"

        val toolType = when (rawName) {
            "run_command", "bash", "terminal" -> ToolType.BASH
            "view_file" -> ToolType.VIEW_FILE
            "write_to_file", "replace_file_content", "multi_replace_file_content", "edit_file", "code_action", "codeaction" -> ToolType.EDIT_FILE
            "list_dir", "list_directory" -> ToolType.LIST_DIR
            "grep_search" -> ToolType.GREP_SEARCH
            "find", "find_by_name" -> ToolType.FIND
            "search_web" -> ToolType.SEARCH_WEB
            "read_url", "read_url_content", "read_resource" -> ToolType.READ_URL
            "generate_image" -> ToolType.GENERATE_IMAGE
            "call_mcp_tool", "mcp_tool" -> ToolType.MCP
            "ask_choices", "ask_question", "user_choice" -> ToolType.ASK_CHOICE
            else -> if (rawName.startsWith("mcp_")) ToolType.MCP else ToolType.UNKNOWN
        }

        val toolId = stepIndex.toString()
        val args = step.generic?.args
        val rawPayload = step.generic?.result?.payload
        val fullOutputUri = step.generic?.result?.fullOutputUri

        val genericPayload: GenericPayloadDto? = try {
            rawPayload?.let { AgyHubClient.agyJson.decodeFromJsonElement<GenericPayloadDto>(it) }
        } catch (_: Exception) { null }

        val runCmd = genericPayload?.runCommand ?: step.runCommand
        val codeAct = genericPayload?.codeAction ?: step.codeAction
        val viewF = genericPayload?.viewFile ?: step.viewFile
        val listDir = genericPayload?.listDirectory ?: step.listDirectory
        val searchW = genericPayload?.searchWeb ?: step.searchWeb
        val grepS = genericPayload?.grepSearch ?: step.grepSearch
        val findF = genericPayload?.find ?: step.find
        val readUrl = genericPayload?.readUrlContent ?: step.readUrlContent
        val genImg = genericPayload?.generateImage ?: step.generateImage
        val mcp = genericPayload?.mcpTool ?: step.mcpTool
        val askQ = genericPayload?.askQuestion ?: step.askQuestion

        var command = ""
        var output = ""
        var exitCode: Int? = null

        when (rawName) {
            "run_command", "bash", "terminal" -> {
                command = runCmd?.commandLine?.takeIf { it.isNotBlank() }
                    ?: runCmd?.proposedCommandLine?.takeIf { it.isNotBlank() }
                    ?: args?.get("CommandLine")?.jsonPrimitive?.contentOrNull
                    ?: meta?.toolSummary?.takeIf { it.isNotBlank() }
                    ?: "run_command"
                output = runCmd?.combinedOutput?.full?.takeIf { it.isNotBlank() }
                    ?: runCmd?.output
                    ?: fullOutputUri?.let { "[Output stored at $it]" }
                    ?: ""
                exitCode = runCmd?.exitCode
            }
            "view_file" -> {
                val rawPath = (args?.get("AbsolutePath")?.jsonPrimitive?.contentOrNull
                    ?: viewF?.absolutePathUri?.takeIf { it.isNotBlank() }
                    ?: viewF?.absolutePath?.takeIf { it.isNotBlank() }
                    ?: viewF?.fileUri ?: "").removePrefix("file://")
                val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                val startLine = args?.get("StartLine")?.jsonPrimitive?.intOrNull ?: viewF?.startLine
                val endLine = args?.get("EndLine")?.jsonPrimitive?.intOrNull ?: viewF?.endLine
                val lineRange = if (startLine != null && endLine != null) " (lines $startLine-$endLine)"
                    else if (endLine != null) " (lines 1-$endLine)"
                    else ""
                command = if (fileName.isNotBlank()) "$fileName$lineRange" else meta?.toolSummary?.ifBlank { "view_file" } ?: "view_file"
                output = viewF?.content?.takeIf { it.isNotBlank() }
                    ?: fullOutputUri?.let { "[File content at $it]" }
                    ?: ""
            }
            "write_to_file" -> {
                val rawPath = (args?.get("TargetFile")?.jsonPrimitive?.contentOrNull
                    ?: codeAct?.uri?.takeIf { it.isNotBlank() }
                    ?: codeAct?.absolutePathUri ?: "").removePrefix("file://")
                val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                val codeContent = args?.get("CodeContent")?.jsonPrimitive?.contentOrNull ?: ""
                val diff = codeAct?.diff?.takeIf { it.isNotBlank() } ?: codeAct?.patch?.takeIf { it.isNotBlank() }
                command = if (fileName.isNotBlank()) fileName else meta?.toolSummary?.ifBlank { "write_to_file" } ?: "write_to_file"
                output = diff ?: codeContent
            }
            "replace_file_content" -> {
                val rawPath = (args?.get("TargetFile")?.jsonPrimitive?.contentOrNull
                    ?: codeAct?.uri?.takeIf { it.isNotBlank() }
                    ?: codeAct?.absolutePathUri ?: "").removePrefix("file://")
                val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                val startLine = args?.get("StartLine")?.jsonPrimitive?.intOrNull
                val endLine = args?.get("EndLine")?.jsonPrimitive?.intOrNull
                val lineRange = if (startLine != null && endLine != null) " (lines $startLine-$endLine)" else ""
                command = if (fileName.isNotBlank()) "$fileName$lineRange" else meta?.toolSummary?.ifBlank { "replace_file_content" } ?: "replace_file_content"
                val diff = codeAct?.diff?.takeIf { it.isNotBlank() } ?: codeAct?.patch?.takeIf { it.isNotBlank() }
                output = diff ?: run {
                    val target = args?.get("TargetContent")?.jsonPrimitive?.contentOrNull ?: ""
                    val replacement = args?.get("ReplacementContent")?.jsonPrimitive?.contentOrNull ?: ""
                    if (target.isNotBlank() || replacement.isNotBlank()) {
                        "--- Target (${startLine ?: 1}-${endLine ?: "?"}):\n$target\n\n+++ Replacement:\n$replacement"
                    } else ""
                }
            }
            "multi_replace_file_content" -> {
                val rawPath = (args?.get("TargetFile")?.jsonPrimitive?.contentOrNull
                    ?: codeAct?.uri?.takeIf { it.isNotBlank() }
                    ?: codeAct?.absolutePathUri ?: "").removePrefix("file://")
                val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                command = if (fileName.isNotBlank()) "$fileName (multi-replace)" else meta?.toolSummary?.ifBlank { "multi_replace_file_content" } ?: "multi_replace_file_content"
                val diff = codeAct?.diff?.takeIf { it.isNotBlank() } ?: codeAct?.patch?.takeIf { it.isNotBlank() }
                output = diff ?: (args?.get("ReplacementChunks")?.toString() ?: "")
            }
            "list_dir" -> {
                val rawDir = (args?.get("DirectoryPath")?.jsonPrimitive?.contentOrNull
                    ?: listDir?.directoryPathUri?.takeIf { it.isNotBlank() }
                    ?: listDir?.directoryPath ?: "").removePrefix("file://")
                val dirName = rawDir.substringAfterLast('/').ifBlank { rawDir }
                command = if (dirName.isNotBlank()) dirName else meta?.toolSummary?.ifBlank { "list_dir" } ?: "list_dir"
                output = if (listDir != null && listDir.results.isNotEmpty()) {
                    listDir.results.joinToString("\n") { entry ->
                        val icon = if (entry.isDir) "📁" else "📄"
                        val size = if (entry.sizeBytes.isNotBlank() && entry.sizeBytes != "0") " (${entry.sizeBytes} B)" else ""
                        "$icon ${entry.name}$size"
                    }
                } else {
                    listDir?.output ?: ""
                }
            }
            "grep_search" -> {
                val query = args?.get("Query")?.jsonPrimitive?.contentOrNull ?: grepS?.query ?: ""
                val searchPath = (args?.get("SearchPath")?.jsonPrimitive?.contentOrNull
                    ?: grepS?.searchPathUri?.takeIf { it.isNotBlank() }
                    ?: grepS?.searchPath ?: "").removePrefix("file://")
                val pathDisplay = searchPath.substringAfterLast('/').ifBlank { searchPath }
                command = if (query.isNotBlank() && pathDisplay.isNotBlank()) "\"$query\" in $pathDisplay"
                    else query.ifBlank { meta?.toolSummary?.ifBlank { "grep_search" } ?: "grep_search" }
                output = if (grepS != null && grepS.results.isNotEmpty()) {
                    grepS.results.joinToString("\n") { "${it.fileName}:${it.lineNumber}: ${it.lineContent}" }
                } else if (grepS != null && grepS.matchedLines.isNotEmpty()) {
                    grepS.matchedLines.joinToString("\n")
                } else {
                    grepS?.commandRun ?: ""
                }
            }
            "find", "find_by_name" -> {
                val pattern = args?.get("Pattern")?.jsonPrimitive?.contentOrNull ?: findF?.pattern ?: "*"
                val dir = (args?.get("SearchDirectory")?.jsonPrimitive?.contentOrNull ?: findF?.searchDirectory ?: "").removePrefix("file://").substringAfterLast('/')
                command = if (dir.isNotBlank()) "$pattern in $dir" else "find $pattern"
                output = if (findF != null && findF.matchedUris.isNotEmpty()) {
                    findF.matchedUris.joinToString("\n")
                } else {
                    findF?.truncatedOutput?.takeIf { it.isNotBlank() } ?: findF?.output ?: ""
                }
            }
            "search_web" -> {
                val query = args?.get("query")?.jsonPrimitive?.contentOrNull ?: searchW?.query ?: ""
                command = query.ifBlank { meta?.toolSummary?.ifBlank { "search_web" } ?: "search_web" }
                output = buildString {
                    if (!searchW?.summary.isNullOrBlank()) {
                        append(searchW.summary)
                    }
                    if (searchW != null && searchW.results.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        searchW.results.forEach { r ->
                            append("• ${r.title} (${r.url})\n  ${r.snippet}\n")
                        }
                    }
                }.ifBlank { searchW?.output ?: "" }
            }
            "read_url", "read_url_content" -> {
                val url = args?.get("Url")?.jsonPrimitive?.contentOrNull ?: readUrl?.url ?: ""
                command = url.ifBlank { meta?.toolSummary?.ifBlank { "read_url" } ?: "read_url" }
                output = readUrl?.markdown?.takeIf { it.isNotBlank() } ?: readUrl?.content ?: ""
            }
            "generate_image" -> {
                val prompt = args?.get("Prompt")?.jsonPrimitive?.contentOrNull ?: genImg?.prompt ?: ""
                command = prompt.ifBlank { meta?.toolSummary?.ifBlank { "generate_image" } ?: "generate_image" }
                output = if (!genImg?.generatedMedia?.inlineData.isNullOrBlank()) {
                    "data:${genImg.generatedMedia.mimeType.ifBlank { "image/jpeg" }};base64,${genImg.generatedMedia.inlineData}"
                } else {
                    genImg?.imageUri?.takeIf { it.isNotBlank() }
                        ?: genImg?.uri?.takeIf { it.isNotBlank() }
                        ?: genImg?.generatedMedia?.uri
                        ?: ""
                }
            }
            "call_mcp_tool" -> {
                val sName = args?.get("ServerName")?.jsonPrimitive?.contentOrNull ?: mcp?.serverName ?: ""
                val tName = args?.get("ToolName")?.jsonPrimitive?.contentOrNull ?: mcp?.toolName ?: ""
                command = if (sName.isNotBlank() && tName.isNotBlank()) "$sName / $tName" else tName.ifBlank { meta?.toolSummary?.ifBlank { "call_mcp_tool" } ?: "call_mcp_tool" }
                output = mcp?.resultString?.takeIf { it.isNotBlank() }
                    ?: mcp?.result?.toString()
                    ?: mcp?.response?.toString()
                    ?: mcp?.output?.toString()
                    ?: mcp?.error?.takeIf { it.isNotBlank() }?.let { "Error: $it" }
                    ?: ""
            }
            "ask_choices", "ask_question", "user_choice" -> {
                val argsJsonStr = args?.toString()
                val askQJson = if (askQ != null) {
                    try { AgyHubClient.agyJson.encodeToString(AskQuestionResultDto.serializer(), askQ) } catch (_: Exception) { null }
                } else null

                command = when {
                    !argsJsonStr.isNullOrBlank() && argsJsonStr != "{}" -> argsJsonStr
                    !askQJson.isNullOrBlank() -> askQJson
                    else -> meta?.toolSummary?.ifBlank { meta.toolAction.ifBlank { "ask_question" } } ?: "ask_question"
                }

                // 1. Extract answers from completedInteractions if user has responded
                val completedResponses = step.completedInteractions
                    .mapNotNull { it.response?.askQuestion?.responses }
                    .flatten()
                    .filter { it.question.isNotBlank() }

                if (completedResponses.isNotEmpty()) {
                    output = completedResponses.joinToString("\n\n") { resp ->
                        val selectedIds = resp.selectedOptionIds.orEmpty()
                        val matchedOpts = resp.options.filter { opt -> selectedIds.contains(opt.id) || selectedIds.contains(opt.text) || selectedIds.contains(opt.label) }
                        val answerText = when {
                            resp.skipped == true -> "Skipped"
                            matchedOpts.isNotEmpty() -> matchedOpts.joinToString(", ") { it.text.ifBlank { it.label } }
                            !resp.writeInResponse.isNullOrBlank() -> "Other: \"${resp.writeInResponse}\""
                            selectedIds.isNotEmpty() -> selectedIds.joinToString(", ")
                            else -> "Submitted"
                        }
                        "• ${resp.question}: $answerText"
                    }
                } else {
                    // 2. Check if questions in payload contain selectedOptionIds
                    val answeredQuestions = askQ?.questions?.filter { it.selectedOptionIds.isNotEmpty() || !it.writeInResponse.isNullOrBlank() || it.skipped } ?: emptyList()
                    if (answeredQuestions.isNotEmpty()) {
                        output = answeredQuestions.joinToString("\n\n") { q ->
                            val matchedOpts = q.options.filter { opt -> q.selectedOptionIds.contains(opt.id) || q.selectedOptionIds.contains(opt.text) || q.selectedOptionIds.contains(opt.label) }
                            val answerText = when {
                                q.skipped -> "Skipped"
                                matchedOpts.isNotEmpty() -> matchedOpts.joinToString(", ") { it.text.ifBlank { it.label } }
                                !q.writeInResponse.isNullOrBlank() -> "Other: \"${q.writeInResponse}\""
                                q.selectedOptionIds.isNotEmpty() -> q.selectedOptionIds.joinToString(", ")
                                else -> "Submitted"
                            }
                            "• ${q.question}: $answerText"
                        }
                    } else {
                        // 3. Fallback when still waiting for user input
                        val questions = askQ?.questions ?: emptyList()
                        output = if (questions.isNotEmpty()) {
                            questions.joinToString("\n\n") { q ->
                                "${q.question}\n" + q.options.joinToString("\n") { opt -> "• ${opt.text.ifBlank { opt.label }}" }
                            }
                        } else ""
                    }
                }
            }
            else -> {
                // Unknown or custom tool:
                command = meta?.toolAction?.takeIf { it.isNotBlank() }
                    ?: meta?.toolSummary?.takeIf { it.isNotBlank() }
                    ?: rawName
                output = rawPayload?.toString() ?: fullOutputUri?.let { "[Output at $it]" } ?: ""
            }
        }

        if (command.isBlank()) {
            command = meta?.toolAction?.ifBlank { meta.toolSummary.ifBlank { rawName } } ?: rawName
        }
        if (output.isBlank() && rawPayload != null) {
            output = rawPayload.toString()
        }

        val status = when (step.status) {
            CortexStepStatuses.DONE -> {
                userRespondedStepIndices.remove(stepIndex)
                "SUCCESS"
            }
            CortexStepStatuses.ERROR -> {
                userRespondedStepIndices.remove(stepIndex)
                "FAILED"
            }
            CortexStepStatuses.CANCELED -> {
                userRespondedStepIndices.remove(stepIndex)
                when {
                    output.contains("rejected", ignoreCase = true) -> "REJECTED"
                    output.contains("terminated", ignoreCase = true) || output.contains("stopped", ignoreCase = true) -> "TERMINATED"
                    step.requestedInteraction != null -> "REJECTED"
                    else -> "TERMINATED"
                }
            }
            CortexStepStatuses.WAITING -> {
                if (toolType == ToolType.ASK_CHOICE) {
                    if (userRespondedStepIndices.contains(stepIndex)) "RUNNING" else "AWAITING_CHOICE"
                } else {
                    if (userRespondedStepIndices.contains(stepIndex)) "RUNNING" else "PENDING_APPROVAL"
                }
            }
            CortexStepStatuses.RUNNING,
            CortexStepStatuses.PENDING,
            CortexStepStatuses.GENERATING -> {
                if (toolType == ToolType.ASK_CHOICE && !userRespondedStepIndices.contains(stepIndex)) {
                    "AWAITING_CHOICE"
                } else {
                    "RUNNING"
                }
            }
            else -> if (step.requestedInteraction != null || toolType == ToolType.ASK_CHOICE) {
                if (userRespondedStepIndices.contains(stepIndex)) "RUNNING" else if (toolType == ToolType.ASK_CHOICE) "AWAITING_CHOICE" else "PENDING_APPROVAL"
            } else "RUNNING"
        }

        return ToolCall(
            id = toolId,
            name = rawName,
            toolType = toolType,
            command = command,
            output = output,
            status = status,
            exitCode = exitCode,
            stepIndex = stepIndex,
            trajectoryId = trajectoryId,
            interactionType = step.requestedInteraction?.permission?.resource?.action ?: "permission"
        )
    }

    private fun extractUserText(userInput: CortexUserInputDto?): String {
        if (userInput == null) return ""
        val direct = userInput.userResponse.ifBlank { userInput.content }
        if (direct.isNotBlank()) return direct
        val itemsText = userInput.items.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }.joinToString("\n")
        return itemsText
    }

    private fun extractUserAttachments(userInput: CortexUserInputDto?, stepIndex: Int): List<ChatAttachment> {
        if (userInput == null) return emptyList()
        val list = mutableListOf<ChatAttachment>()
        val allMedia = mutableListOf<MediaAttachmentDto>()
        allMedia.addAll(userInput.media)
        userInput.items.forEach { item ->
            item.media?.let { allMedia.add(it) }
        }

        allMedia.forEachIndexed { idx, media ->
            val resolvedMime = media.mimeType.ifBlank { media.mime_type }
            val cleanUri = if (media.uri.startsWith("file://")) media.uri.removePrefix("file://") else media.uri
            val base64Data = media.inlineData.ifBlank { media.data }
            if (resolvedMime.isBlank() && cleanUri.isBlank() && base64Data.isBlank()) {
                return@forEachIndexed
            }
            val isAud = resolvedMime.startsWith("audio/") || cleanUri.endsWith(".m4a", true) || cleanUri.endsWith(".mp3", true) || cleanUri.endsWith(".wav", true) || cleanUri.endsWith(".ogg", true)
            val isImg = resolvedMime.startsWith("image/") || cleanUri.endsWith(".png", true) || cleanUri.endsWith(".jpg", true) || cleanUri.endsWith(".jpeg", true) || cleanUri.endsWith(".webp", true) || cleanUri.endsWith(".gif", true) || cleanUri.endsWith(".svg", true)
            val dur = if (media.durationSeconds > 0) media.durationSeconds else media.duration_seconds
            val resolvedName = when {
                media.name.isNotBlank() && !media.name.startsWith("attachment_") -> media.name
                media.description.isNotBlank() -> media.description
                isAud -> if (dur > 0) {
                    val mins = dur / 60
                    val secs = dur % 60
                    "Voice Note (${String.format("%d:%02d", mins, secs)})"
                } else "Voice Note"
                isImg -> "Image"
                else -> "attachment_$idx"
            }
            list.add(
                ChatAttachment(
                    id = "att_${stepIndex}_$idx",
                    name = resolvedName,
                    path = cleanUri,
                    isImage = isImg,
                    isAudio = isAud,
                    durationSeconds = dur,
                    mimeType = resolvedMime.ifBlank { if (isAud) "audio/mp4" else if (isImg) "image/jpeg" else "" },
                    base64 = if (base64Data.isNotBlank() && !base64Data.startsWith("http") && !base64Data.startsWith("file://")) base64Data else null
                )
            )
        }
        return list
    }
}
