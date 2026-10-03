package com.example.gemini.domain.chat

import android.util.Log
import com.example.gemini.domain.model.ArtifactSnapshot
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.ChoiceQuestionnaire
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChatTurn
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.TokenUsage
import com.example.gemini.domain.model.ToolCall
import com.example.gemini.domain.model.ToolType
import com.example.gemini.domain.model.TurnBlock
import exa.language_server_pb.AgentStateUpdate
import exa.language_server_pb.CascadeRunStatus
import exa.language_server_pb.CortexStepErrorMessage
import exa.language_server_pb.CortexStepStatus
import exa.language_server_pb.CortexStepType
import exa.language_server_pb.CortexStepUserInput
import exa.language_server_pb.Duration
import exa.language_server_pb.Step
import exa.language_server_pb.Timestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic State Engine for AGY Hub Conversations.
 *
 * Consumes type-safe Square Wire Protobuf models (AgentStateUpdate, Step, etc.)
 * with a two-tier turn cache:
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
    private val activeStepsMap = LinkedHashMap<Int, Step>()

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
            val protoStatus = when (status.uppercase()) {
                "DONE", "SUCCESS" -> CortexStepStatus.CORTEX_STEP_STATUS_DONE
                "RUNNING" -> CortexStepStatus.CORTEX_STEP_STATUS_RUNNING
                "ERROR", "FAILED" -> CortexStepStatus.CORTEX_STEP_STATUS_ERROR
                "WAITING", "PENDING_APPROVAL", "AWAITING_CHOICE" -> CortexStepStatus.CORTEX_STEP_STATUS_WAITING
                "CANCELED", "REJECTED", "TERMINATED" -> CortexStepStatus.CORTEX_STEP_STATUS_CANCELED
                else -> CortexStepStatus.CORTEX_STEP_STATUS_UNSPECIFIED
            }
            val updated = existing.copy(
                status = protoStatus,
                run_command = if (output != null) existing.run_command?.copy(stdout = output) ?: existing.run_command else existing.run_command
            )
            activeStepsMap[stepIndex] = updated
            _turns.value = getTurns()
        }
    }

    /**
     * Ingests typed protobuf updates directly from Square Wire StreamAgentStateUpdates.
     */
    fun ingestAgentStateUpdate(update: AgentStateUpdate): List<ChatTurn> {
        val convId = update.conversation_id.takeIf { it.isNotBlank() }
        val trajId = update.trajectory_id.takeIf { it.isNotBlank() }
        val status = update.status

        if (!convId.isNullOrBlank()) conversationId = convId
        if (!trajId.isNullOrBlank()) trajectoryId = trajId

        val daemonRunning = status == CascadeRunStatus.CASCADE_RUN_STATUS_RUNNING
        val daemonIdle = status == CascadeRunStatus.CASCADE_RUN_STATUS_IDLE || update.fully_idle

        val stepsUpdate = update.main_trajectory_update?.steps_update

        val effectiveSteps = stepsUpdate?.steps ?: emptyList()
        val effectiveIndices = stepsUpdate?.indices ?: effectiveSteps.indices.toList()
        val totalLength = stepsUpdate?.total_length ?: effectiveSteps.size

        Log.d("CHAT_OPEN_DEBUG", "⚙️ [TrajectoryEngine.ingestAgentStateUpdate] convId=$conversationId, trajId=$trajectoryId, status=$status, stepsCount=${effectiveSteps.size}")

        if (effectiveSteps.isNotEmpty()) {
            val isInitialFullSync = (effectiveIndices.firstOrNull() == 0) || (completedTurns.isEmpty() && activeStepsMap.isEmpty())
            if (isInitialFullSync) {
                ingestInitialFullSync(effectiveIndices, effectiveSteps, daemonRunning)
            } else {
                ingestIncrementalDeltas(effectiveIndices, effectiveSteps, totalLength)
            }
        }

        val frameLastStepError = update.main_trajectory_update?.last_step_error

        if (frameLastStepError != null) {
            val errStepIdx = activeStepsMap.keys.maxOrNull() ?: activeTurnStartStep
            val existingStep = activeStepsMap[errStepIdx]
            if (existingStep != null && (existingStep.error_message != null || existingStep.error != null)) {
                val curErr = existingStep.error_message?.error ?: existingStep.error
                val baseErr = curErr ?: frameLastStepError
                val enrichedErr = baseErr.copy(
                    full_error = if (curErr?.full_error.isNullOrBlank()) frameLastStepError.full_error else curErr?.full_error ?: "",
                    short_error = if (curErr?.short_error.isNullOrBlank()) frameLastStepError.short_error else curErr?.short_error ?: "",
                    user_error_message = if (curErr?.user_error_message.isNullOrBlank()) frameLastStepError.user_error_message else curErr?.user_error_message ?: "",
                    error_code = curErr?.error_code ?: frameLastStepError.error_code,
                    error_id = if (curErr?.error_id.isNullOrBlank()) frameLastStepError.error_id else curErr?.error_id ?: ""
                )
                activeStepsMap[errStepIdx] = existingStep.copy(
                    error_message = CortexStepErrorMessage(error = enrichedErr, should_show_user = true),
                    error = enrichedErr
                )
            } else if (activeStepsMap.none { (_, s) -> s.error_message != null || s.error != null || s.type == CortexStepType.CORTEX_STEP_TYPE_ERROR_MESSAGE }) {
                val newErrorStep = Step(
                    type = CortexStepType.CORTEX_STEP_TYPE_ERROR_MESSAGE,
                    status = CortexStepStatus.CORTEX_STEP_STATUS_DONE,
                    error_message = CortexStepErrorMessage(error = frameLastStepError, should_show_user = true),
                    error = frameLastStepError
                )
                activeStepsMap[errStepIdx] = newErrorStep
            }
        }

        isRunning = daemonRunning

        if (daemonIdle && effectiveSteps.isNotEmpty()) {
            pendingUserTurn = null
        }

        val hasPendingInteraction = activeStepsMap.any { (stepIdx, step) ->
            val isAskChoice = step.metadata?.tool_call?.name == "ask_question" ||
                    step.generic?.args?.any { it.key == "ask_question" } == true ||
                    step.ask_question != null ||
                    step.requested_interaction?.ask_question != null
            val isWaiting = step.status == CortexStepStatus.CORTEX_STEP_STATUS_WAITING || step.requested_interaction != null ||
                    (isAskChoice && step.status != CortexStepStatus.CORTEX_STEP_STATUS_DONE && step.status != CortexStepStatus.CORTEX_STEP_STATUS_CANCELED && step.status != CortexStepStatus.CORTEX_STEP_STATUS_ERROR)
            isWaiting && !userRespondedStepIndices.contains(stepIdx)
        }
        isWaitingInteraction = hasPendingInteraction

        val artifactUpdate = update.artifact_snapshots_update
        if (artifactUpdate != null && artifactUpdate.artifact_snapshots.isNotEmpty()) {
            val mapped = artifactUpdate.artifact_snapshots.map { snap ->
                ArtifactSnapshot(
                    name = snap.artifact_name,
                    absoluteUri = snap.artifact_absolute_uri,
                    lastEdited = snap.last_edited?.let { "${it.seconds}" } ?: "",
                    summary = snap.artifact_metadata?.summary ?: "",
                    requestFeedback = snap.artifact_metadata?.request_feedback == true,
                    userFacing = snap.artifact_metadata?.user_facing == true
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
     * Directly parses an array of Steps (e.g. for offline export or trajectory inspections)
     * and returns the resulting list of ChatMessages.
     */
    fun ingestStepsDirect(steps: List<Step>, convId: String = ""): List<ChatMessage> {
        reset(convId, force = true)
        ingestInitialFullSync(
            indices = steps.indices.toList(),
            steps = steps,
            cascadeRunning = false
        )
        finalizeActiveTurn()
        return toChatMessages(convId)
    }

    /**
     * Parses Chunk 0 (the complete historical trajectory) into completed turns.
     */
    private fun ingestInitialFullSync(indices: List<Int>, steps: List<Step>, cascadeRunning: Boolean) {
        val savedPending = pendingUserTurn
        completedTurns.clear()
        activeStepsMap.clear()
        pendingUserTurn = null

        var currentTurnBlocks = mutableListOf<TurnBlock>()
        var currentTurnSteps = mutableListOf<Step>()
        var lastUserStepArrayIndex = -1
        var hasUserInputStep = false

        for (i in steps.indices) {
            val stepIndex = indices.getOrNull(i) ?: i
            val step = steps[i]

            if (step.type == CortexStepType.CORTEX_STEP_TYPE_USER_INPUT || step.user_input != null) {
                hasUserInputStep = true
                if (currentTurnBlocks.isNotEmpty()) {
                    val prevUserStepIdx = if (lastUserStepArrayIndex >= 0) (indices.getOrNull(lastUserStepArrayIndex) ?: lastUserStepArrayIndex) else -1
                    val turnId = "${conversationId}_${prevUserStepIdx + 1}"
                    val tokenUsage = computeTokenUsage(currentTurnSteps)
                    completedTurns.add(ChatTurn.Assistant(turnId = turnId, blocks = currentTurnBlocks.toList(), tokenUsage = tokenUsage))
                    currentTurnBlocks = mutableListOf()
                    currentTurnSteps = mutableListOf()
                }

                val userText = extractUserText(step.user_input)
                val attachments = extractUserAttachments(step.user_input, stepIndex)
                completedTurns.add(ChatTurn.User(stepIndex = stepIndex, text = userText, attachments = attachments))
                lastUserStepArrayIndex = i
                activeTurnStartStep = stepIndex + 1
            } else {
                currentTurnSteps.add(step)
                extractStepBlocks(step, stepIndex, isStreaming = false, blocks = currentTurnBlocks)
            }
        }

        if (!hasUserInputStep && savedPending != null) {
            pendingUserTurn = savedPending
        }

        val hasActiveWork = cascadeRunning || currentTurnBlocks.any {
            it is TurnBlock.Permission || (it is TurnBlock.Tool && it.toolCall.status == "PENDING_APPROVAL")
        }
        if (hasActiveWork && currentTurnBlocks.isNotEmpty()) {
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
    private fun ingestIncrementalDeltas(indices: List<Int>, steps: List<Step>, totalLength: Int) {
        if (totalLength in 1..activeStepsMap.size) {
            activeStepsMap.keys.retainAll { it < totalLength }
        }

        for (i in steps.indices) {
            val stepIndex = indices.getOrNull(i) ?: (activeTurnStartStep + i)
            val step = steps[i]

            if (step.type == CortexStepType.CORTEX_STEP_TYPE_USER_INPUT || step.user_input != null) {
                finalizeActiveTurn()
                pendingUserTurn = null

                val userText = extractUserText(step.user_input)
                val attachments = extractUserAttachments(step.user_input, stepIndex)
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
                activeStepsMap[stepIndex] = step
            }
        }
    }

    private fun parseTimestampToMillis(ts: Timestamp?): Long? {
        if (ts == null) return null
        return (ts.seconds * 1000L) + (ts.nanos / 1_000_000L)
    }

    private fun parseDurationToMillis(duration: Duration?): Long? {
        if (duration == null) return null
        return (duration.seconds * 1000L) + (duration.nanos / 1_000_000L)
    }

    /**
     * Calculates the aggregated TokenUsage for a set of steps in a turn.
     * Only considers completed PLANNER_RESPONSE steps with valid modelUsage.
     */
    private fun computeTokenUsage(steps: Iterable<Step>): TokenUsage? {
        val allStepsList = steps.toList()
        val plannerSteps = allStepsList.filter {
            it.type == CortexStepType.CORTEX_STEP_TYPE_PLANNER_RESPONSE &&
            it.status == CortexStepStatus.CORTEX_STEP_STATUS_DONE &&
            it.metadata?.model_usage != null
        }

        val startTimes = allStepsList.mapNotNull {
            parseTimestampToMillis(it.metadata?.created_at) ?: parseTimestampToMillis(it.metadata?.started_at)
        }
        val endTimes = allStepsList.mapNotNull {
            parseTimestampToMillis(it.metadata?.completed_at) ?: parseTimestampToMillis(it.metadata?.finished_generating_at)
        }
        val durationMs = if (startTimes.isNotEmpty() && endTimes.isNotEmpty()) {
            val start = startTimes.minOrNull() ?: 0L
            val end = endTimes.maxOrNull() ?: 0L
            if (end >= start) end - start else 0L
        } else 0L

        if (plannerSteps.isEmpty() && durationMs == 0L) return null

        val outputTokens = plannerSteps.sumOf { (it.metadata?.model_usage?.output_tokens ?: 0L).toInt() }
        val promptTokens = (plannerSteps.lastOrNull()?.metadata?.model_usage?.input_tokens ?: 0L).toInt()
        val cachedTokens = plannerSteps.sumOf { (it.metadata?.model_usage?.cache_read_tokens ?: 0L).toInt() }

        if (outputTokens == 0 && promptTokens == 0 && cachedTokens == 0 && durationMs == 0L) return null

        return TokenUsage(
            promptTokens = promptTokens,
            outputTokens = outputTokens,
            cachedTokens = cachedTokens,
            totalTokens = promptTokens + outputTokens,
            durationMs = durationMs
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
            if ((isRunning || pendingUserTurn != null) && !isWaitingInteraction) {
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
            val isStepRunning = step.status == CortexStepStatus.CORTEX_STEP_STATUS_RUNNING ||
                    step.status == CortexStepStatus.CORTEX_STEP_STATUS_PENDING ||
                    step.status == CortexStepStatus.CORTEX_STEP_STATUS_GENERATING ||
                    step.status == CortexStepStatus.CORTEX_STEP_STATUS_QUEUED

            val isAskChoice = step.metadata?.tool_call?.name == "ask_question" ||
                    step.generic?.args?.any { it.key == "ask_question" } == true ||
                    step.ask_question != null ||
                    step.requested_interaction?.ask_question != null
            val isWaiting = step.status == CortexStepStatus.CORTEX_STEP_STATUS_WAITING || step.requested_interaction != null ||
                    (isAskChoice && step.status != CortexStepStatus.CORTEX_STEP_STATUS_DONE && step.status != CortexStepStatus.CORTEX_STEP_STATUS_CANCELED && step.status != CortexStepStatus.CORTEX_STEP_STATUS_ERROR)

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
                                }
                                if (block.durationMs != null && block.durationMs > 0) {
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
                                val errJson = buildString {
                                    append("{\"title\":\"${block.title}\",\"userMessage\":\"${block.userMessage}\",\"shortError\":\"${block.shortError}\",\"fullError\":\"${block.fullError}\"")
                                    if (block.errorCode != null) append(",\"errorCode\":${block.errorCode}")
                                    if (block.errorId.isNotBlank()) append(",\"errorId\":\"${block.errorId}\"")
                                    append("}")
                                }
                                contentParts.add("<!-- error:${block.stepIndex} -->\n$errJson\n<!-- /error -->")
                            }
                            is TurnBlock.SystemNotice -> {
                                if (block.content.isNotBlank()) {
                                    val noticeToolId = "notice_${block.stepIndex}"
                                    if (toolCalls.none { it.id == noticeToolId || it.stepIndex == block.stepIndex }) {
                                        val noticeTool = ToolCall(
                                            id = noticeToolId,
                                            name = "system_notice",
                                            toolType = ToolType.SYSTEM_NOTIFICATION,
                                            command = block.title.ifBlank { "System Notification" },
                                            status = "SUCCESS",
                                            output = block.content,
                                            exitCode = 0,
                                            stepIndex = block.stepIndex
                                        )
                                        toolCalls.add(noticeTool)
                                        contentParts.add("<!-- tool_call:$noticeToolId -->")
                                    }
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
     * Extracts blocks strictly in-order from a single Step protobuf message.
     */
    private fun extractStepBlocks(
        step: Step,
        stepIndex: Int,
        isStreaming: Boolean,
        blocks: MutableList<TurnBlock>
    ) {
        if (step.type == CortexStepType.CORTEX_STEP_TYPE_USER_INPUT || step.user_input != null) {
            return
        }

        if (step.type == CortexStepType.CORTEX_STEP_TYPE_PLANNER_RESPONSE || step.planner_response != null) {
            val thinking = step.planner_response?.thinking
            val response = step.planner_response?.response
            val thinkingDurationMs = parseDurationToMillis(step.planner_response?.thinking_duration)

            if (!thinking.isNullOrBlank()) {
                blocks.add(TurnBlock.Thinking(stepIndex = stepIndex, thought = thinking, durationMs = thinkingDurationMs, isStreaming = isStreaming))
            }
            if (!response.isNullOrBlank()) {
                blocks.add(TurnBlock.Text(stepIndex = stepIndex, markdown = response, isStreaming = isStreaming))
            }
            return
        }

        if (step.type == CortexStepType.CORTEX_STEP_TYPE_SYSTEM_MESSAGE || step.system_message != null) {
            val sysMsg = step.system_message
            val msgText = sysMsg?.message?.takeIf { it.isNotBlank() } ?: ""
            val renderTitle = sysMsg?.render_info?.title?.takeIf { it.isNotBlank() && it != "System" }
                ?: sysMsg?.agent_message?.render_details?.message_title?.takeIf { it.isNotBlank() }
                ?: extractTitleFromSystemMessage(msgText)
            val isHidden = sysMsg?.render_info?.hidden == true || sysMsg?.agent_message?.hide_from_user == true

            val isBackgroundStopNotice = msgText.contains("subagents and background tasks have been stopped", ignoreCase = true) ||
                    msgText.contains("server restart", ignoreCase = true) ||
                    msgText.contains("stopped due to server restart", ignoreCase = true)

            if (!isHidden && !isBackgroundStopNotice && msgText.isNotBlank()) {
                blocks.add(
                    TurnBlock.SystemNotice(
                        stepIndex = stepIndex,
                        title = renderTitle,
                        content = msgText
                    )
                )
            }
            return
        }

        val stepError = step.error_message?.error ?: step.error
        val isErrorType = step.type == CortexStepType.CORTEX_STEP_TYPE_ERROR_MESSAGE ||
                step.status == CortexStepStatus.CORTEX_STEP_STATUS_ERROR ||
                step.error_message != null ||
                step.error != null

        if (isErrorType) {
            val userMsg = stepError?.user_error_message?.takeIf { it.isNotBlank() } ?: "Agent Execution Error"
            val shortErr = stepError?.short_error?.takeIf { it.isNotBlank() }
                ?: stepError?.model_error_message?.takeIf { it.isNotBlank() }
                ?: ""
            val fullErr = stepError?.full_error?.takeIf { it.isNotBlank() } ?: ""
            val code = stepError?.error_code
            val errId = stepError?.error_id ?: ""

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

        val toolCall = extractToolCallFromStep(step, stepIndex)
        if (toolCall != null) {
            blocks.add(TurnBlock.Tool(stepIndex = stepIndex, toolCall = toolCall))
        }
    }

    /**
     * Extracts a domain ToolCall from a typed Step protobuf model without regex.
     */
    private fun extractToolCallFromStep(step: Step, stepIndex: Int): ToolCall? {
        val meta = step.metadata
        val tcMeta = meta?.tool_call

        val argsMap: Map<String, String> = step.generic?.args ?: emptyMap()

        val rawName = tcMeta?.name?.takeIf { it.isNotBlank() }
            ?: when (step.type) {
                CortexStepType.CORTEX_STEP_TYPE_RUN_COMMAND -> "run_command"
                CortexStepType.CORTEX_STEP_TYPE_VIEW_FILE -> "view_file"
                CortexStepType.CORTEX_STEP_TYPE_CODE_ACTION -> "code_action"
                CortexStepType.CORTEX_STEP_TYPE_LIST_DIRECTORY -> "list_dir"
                CortexStepType.CORTEX_STEP_TYPE_GREP_SEARCH -> "grep_search"
                CortexStepType.CORTEX_STEP_TYPE_FIND -> "find"
                CortexStepType.CORTEX_STEP_TYPE_SEARCH_WEB -> "search_web"
                CortexStepType.CORTEX_STEP_TYPE_READ_URL_CONTENT, CortexStepType.CORTEX_STEP_TYPE_READ_RESOURCE -> "read_url"
                CortexStepType.CORTEX_STEP_TYPE_GENERATE_IMAGE -> "generate_image"
                CortexStepType.CORTEX_STEP_TYPE_MCP_TOOL -> "call_mcp_tool"
                CortexStepType.CORTEX_STEP_TYPE_ASK_QUESTION -> "ask_question"
                else -> when {
                    step.run_command != null -> "run_command"
                    step.view_file != null -> "view_file"
                    step.code_action != null -> "code_action"
                    step.write_to_file != null -> "write_to_file"
                    step.list_directory != null -> "list_dir"
                    step.grep_search != null -> "grep_search"
                    step.find != null -> "find"
                    step.search_web != null -> "search_web"
                    step.read_url_content != null -> "read_url"
                    step.generate_image != null -> "generate_image"
                    step.mcp_tool != null -> "call_mcp_tool"
                    step.ask_question != null || step.requested_interaction?.ask_question != null -> "ask_question"
                    else -> meta?.tool_summary?.takeIf { it.isNotBlank() } ?: "unknown_tool"
                }
            }

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
            "system_notice", "system_notification", "system_message" -> ToolType.SYSTEM_NOTIFICATION
            else -> if (rawName.startsWith("mcp_")) ToolType.MCP else ToolType.UNKNOWN
        }

        val toolId = stepIndex.toString()
        val fullOutputUri = step.generic?.result?.full_output_uri

        val runCmd = step.run_command
        val codeAct = step.code_action
        val viewF = step.view_file
        val listDir = step.list_directory
        val searchW = step.search_web
        val grepS = step.grep_search
        val findF = step.find
        val readUrl = step.read_url_content
        val genImg = step.generate_image
        val mcp = step.mcp_tool
        val askQ = step.ask_question

        var command = ""
        var output = ""
        var exitCode: Int? = null
        var questionnaire: ChoiceQuestionnaire? = null

        when (rawName) {
            "run_command", "bash", "terminal" -> {
                val cmd = runCmd?.command_line?.takeIf { it.isNotBlank() }
                    ?: runCmd?.proposed_command_line?.takeIf { it.isNotBlank() }
                    ?: argsMap["CommandLine"]
                    ?: meta?.tool_summary?.takeIf { it.isNotBlank() }
                    ?: "run_command"
                command = cmd
                output = runCmd?.combined_output?.full?.takeIf { it.isNotBlank() }
                    ?: runCmd?.stdout ?: ""
                exitCode = runCmd?.exit_code
            }
            "view_file" -> {
                val rawPath = (argsMap["AbsolutePath"]
                    ?: viewF?.absolute_path_uri
                    ?: "").removePrefix("file://")
                val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                val startLine = argsMap["StartLine"]?.toIntOrNull() ?: viewF?.start_line
                val endLine = argsMap["EndLine"]?.toIntOrNull() ?: viewF?.end_line
                val lineRange = if (startLine != null && endLine != null) " (lines $startLine-$endLine)"
                    else if (endLine != null) " (lines 1-$endLine)"
                    else ""
                command = if (fileName.isNotBlank()) "$fileName$lineRange" else if (meta?.tool_summary?.isNotBlank() == true) meta.tool_summary else "view_file"
                output = viewF?.content?.takeIf { it.isNotBlank() }
                    ?: fullOutputUri?.let { "[File content at $it]" }
                    ?: ""
            }
            "write_to_file" -> {
                val rawPath = (argsMap["TargetFile"]
                    ?: codeAct?.action_spec?.create_file?.path?.absolute_uri
                    ?: codeAct?.action_result?.edit?.absolute_uri
                    ?: "").removePrefix("file://")
                val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                val codeContent = argsMap["CodeContent"] ?: ""
                val diff = codeAct?.action_result?.edit?.diff?.unified_diff?.lines?.joinToString("\n") { it.text }
                command = if (fileName.isNotBlank()) fileName else if (meta?.tool_summary?.isNotBlank() == true) meta.tool_summary else "write_to_file"
                output = if (!diff.isNullOrBlank()) diff else codeContent
            }
            "replace_file_content" -> {
                val rawPath = (argsMap["TargetFile"]
                    ?: codeAct?.action_spec?.command?.file_?.absolute_uri
                    ?: codeAct?.action_result?.edit?.absolute_uri
                    ?: "").removePrefix("file://")
                val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                val startLine = argsMap["StartLine"]?.toIntOrNull() ?: codeAct?.action_spec?.command?.line_range?.start_line
                val endLine = argsMap["EndLine"]?.toIntOrNull() ?: codeAct?.action_spec?.command?.line_range?.end_line
                val lineRange = if (startLine != null && endLine != null) " (lines $startLine-$endLine)" else ""
                command = if (fileName.isNotBlank()) "$fileName$lineRange" else if (meta?.tool_summary?.isNotBlank() == true) meta.tool_summary else "replace_file_content"
                val diff = codeAct?.action_result?.edit?.diff?.unified_diff?.lines?.joinToString("\n") { it.text }
                output = if (!diff.isNullOrBlank()) diff else run {
                    val target = argsMap["TargetContent"] ?: ""
                    val replacement = argsMap["ReplacementContent"] ?: ""
                    if (target.isNotBlank() || replacement.isNotBlank()) {
                        "--- Target (${startLine ?: 1}-${endLine ?: "?"}):\n$target\n\n+++ Replacement:\n$replacement"
                    } else ""
                }
            }
            "multi_replace_file_content" -> {
                val rawPath = (argsMap["TargetFile"]
                    ?: codeAct?.action_spec?.command?.file_?.absolute_uri
                    ?: codeAct?.action_result?.edit?.absolute_uri
                    ?: "").removePrefix("file://")
                val fileName = rawPath.substringAfterLast('/').ifBlank { rawPath }
                command = if (fileName.isNotBlank()) "$fileName (multi-replace)" else if (meta?.tool_summary?.isNotBlank() == true) meta.tool_summary else "multi_replace_file_content"
                val diff = codeAct?.action_result?.edit?.diff?.unified_diff?.lines?.joinToString("\n") { it.text }
                output = if (!diff.isNullOrBlank()) diff else (argsMap["ReplacementChunks"] ?: "")
            }
            "list_dir" -> {
                val rawDir = (argsMap["DirectoryPath"]
                    ?: listDir?.directory_path_uri
                    ?: "").removePrefix("file://")
                val dirName = rawDir.substringAfterLast('/').ifBlank { rawDir }
                command = if (dirName.isNotBlank()) dirName else if (meta?.tool_summary?.isNotBlank() == true) meta.tool_summary else "list_dir"
                output = if (listDir != null && listDir.results.isNotEmpty()) {
                    listDir.results.joinToString("\n") { entry ->
                        val icon = if (entry.is_dir) "📁" else "📄"
                        val size = if (entry.size_bytes > 0L) " (${entry.size_bytes} B)" else ""
                        "$icon ${entry.name}$size"
                    }
                } else if (listDir != null && listDir.children.isNotEmpty()) {
                    listDir.children.joinToString("\n") { "📄 $it" }
                } else ""
            }
            "grep_search" -> {
                val query = argsMap["Query"] ?: grepS?.query ?: ""
                val searchPath = (argsMap["SearchPath"]
                    ?: grepS?.search_path_uri
                    ?: "").removePrefix("file://")
                val pathDisplay = searchPath.substringAfterLast('/').ifBlank { searchPath }
                command = if (query.isNotBlank() && pathDisplay.isNotBlank()) "\"$query\" in $pathDisplay"
                    else if (query.isNotBlank()) query
                    else if (meta?.tool_summary?.isNotBlank() == true) meta.tool_summary
                    else "grep_search"
                output = if (grepS != null && grepS.results.isNotEmpty()) {
                    grepS.results.joinToString("\n") { "${it.relative_path.ifBlank { it.absolute_path }}:${it.line_number}: ${it.content}" }
                } else {
                    grepS?.raw_output ?: grepS?.command_run ?: ""
                }
            }
            "find", "find_by_name" -> {
                val pattern = argsMap["Pattern"] ?: findF?.pattern ?: "*"
                val dir = (argsMap["SearchDirectory"] ?: findF?.search_directory ?: "").removePrefix("file://").substringAfterLast('/')
                command = if (dir.isNotBlank()) "$pattern in $dir" else "find $pattern"
                output = findF?.truncated_output?.takeIf { it.isNotBlank() } ?: findF?.raw_output ?: findF?.command_run ?: ""
            }
            "search_web" -> {
                val query = argsMap["query"] ?: searchW?.query ?: ""
                val metaSummary = meta?.tool_summary ?: ""
                command = if (query.isNotBlank()) query else if (metaSummary.isNotBlank()) metaSummary else "search_web"
                output = buildString {
                    if (searchW != null && searchW.summary.isNotBlank()) {
                        append(searchW.summary)
                    }
                    if (searchW != null && searchW.web_documents.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        searchW.web_documents.forEach { r ->
                            append("• ${r.title} (${r.url})\n  ${r.summary.ifBlank { r.text }}\n")
                        }
                    }
                }
            }
            "read_url", "read_url_content" -> {
                val url = argsMap["Url"] ?: readUrl?.url ?: ""
                val metaSummary = meta?.tool_summary ?: ""
                command = if (url.isNotBlank()) url else if (metaSummary.isNotBlank()) metaSummary else "read_url"
                output = readUrl?.web_document?.text?.takeIf { it.isNotBlank() } ?: readUrl?.web_document?.summary ?: ""
            }
            "generate_image" -> {
                val prompt = argsMap["Prompt"] ?: genImg?.prompt ?: ""
                val metaSummary = meta?.tool_summary ?: ""
                command = if (prompt.isNotBlank()) prompt else if (metaSummary.isNotBlank()) metaSummary else "generate_image"
                val inlineBytes = genImg?.generated_media?.inline_data
                val inlineBase64 = if (inlineBytes != null && inlineBytes.size > 0) inlineBytes.base64() else ""
                val imageBase64 = genImg?.generated_image?.base64_data?.takeIf { it.isNotBlank() } ?: ""

                output = when {
                    inlineBase64.isNotBlank() -> {
                        val mime = genImg?.generated_media?.mime_type?.takeIf { it.isNotBlank() } ?: "image/png"
                        "data:$mime;base64,$inlineBase64"
                    }
                    imageBase64.isNotBlank() -> {
                        val mime = genImg?.generated_image?.mime_type?.takeIf { it.isNotBlank() } ?: "image/png"
                        "data:$mime;base64,$imageBase64"
                    }
                    genImg?.generated_media?.uri?.isNotBlank() == true -> {
                        genImg.generated_media.uri
                    }
                    genImg?.generated_image?.uri?.isNotBlank() == true -> {
                        genImg.generated_image.uri
                    }
                    else -> ""
                }
            }
            "call_mcp_tool" -> {
                val sName = argsMap["ServerName"] ?: mcp?.server_name ?: ""
                val tName = argsMap["ToolName"] ?: mcp?.tool_call?.name ?: ""
                val metaSummary = meta?.tool_summary ?: ""
                command = if (sName.isNotBlank() && tName.isNotBlank()) "$sName / $tName" else if (tName.isNotBlank()) tName else if (metaSummary.isNotBlank()) metaSummary else "call_mcp_tool"
                output = mcp?.result_string?.takeIf { it.isNotBlank() }
                    ?: mcp?.result_uri?.let { "[Result at $it]" }
                    ?: ""
            }
            "ask_choices", "ask_question", "user_choice" -> {
                val askQuestions = askQ?.questions?.takeIf { it.isNotEmpty() }
                    ?: step.requested_interaction?.ask_question?.questions?.takeIf { it.isNotEmpty() }

                val titleText = meta?.tool_action?.takeIf { it.isNotBlank() }
                    ?: meta?.tool_summary?.takeIf { it.isNotBlank() }
                    ?: "Questionnaire"

                if (askQuestions != null && askQuestions.isNotEmpty()) {
                    questionnaire = ChoiceQuestionnaire.fromProto(
                        title = titleText,
                        questions = askQuestions,
                        description = meta?.tool_action?.takeIf { it != titleText }
                    )
                }
                command = titleText

                val completedResponses = step.completed_interactions
                    .mapNotNull { it.response?.ask_question?.responses }
                    .flatten()
                    .filter { it.question.isNotBlank() }

                if (completedResponses.isNotEmpty()) {
                    output = completedResponses.joinToString("\n\n") { resp ->
                        val selectedIds = resp.selected_option_ids
                        val matchedOpts = resp.options.filter { opt -> selectedIds.contains(opt.id) || selectedIds.contains(opt.text) }
                        val answerText = when {
                            resp.skipped -> "Skipped"
                            matchedOpts.isNotEmpty() -> matchedOpts.joinToString(", ") { it.text }
                            resp.write_in_response.isNotBlank() -> "Other: \"${resp.write_in_response}\""
                            selectedIds.isNotEmpty() -> selectedIds.joinToString(", ")
                            else -> "Submitted"
                        }
                        "• ${resp.question}: $answerText"
                    }
                } else if (askQuestions != null && askQuestions.isNotEmpty()) {
                    val answeredQuestions = askQuestions.filter { it.selected_option_ids.isNotEmpty() || it.write_in_response.isNotBlank() || it.skipped }
                    if (answeredQuestions.isNotEmpty()) {
                        output = answeredQuestions.joinToString("\n\n") { q ->
                            val matchedOpts = q.options.filter { opt -> q.selected_option_ids.contains(opt.id) || q.selected_option_ids.contains(opt.text) }
                            val answerText = when {
                                q.skipped -> "Skipped"
                                matchedOpts.isNotEmpty() -> matchedOpts.joinToString(", ") { it.text }
                                q.write_in_response.isNotBlank() -> "Other: \"${q.write_in_response}\""
                                q.selected_option_ids.isNotEmpty() -> q.selected_option_ids.joinToString(", ")
                                else -> "Submitted"
                            }
                            "• ${q.question}: $answerText"
                        }
                    } else {
                        output = askQuestions.joinToString("\n\n") { q ->
                            "${q.question}\n" + q.options.joinToString("\n") { opt -> "• ${opt.text}" }
                        }
                    }
                }
            }
            else -> {
                command = meta?.tool_action?.takeIf { it.isNotBlank() }
                    ?: meta?.tool_summary?.takeIf { it.isNotBlank() }
                    ?: rawName
                output = fullOutputUri?.let { "[Output at $it]" } ?: ""
            }
        }

        if (command.isBlank()) {
            command = meta?.tool_action?.ifBlank { meta.tool_summary.ifBlank { rawName } } ?: rawName
        }

        val status = when (step.status) {
            CortexStepStatus.CORTEX_STEP_STATUS_DONE -> {
                userRespondedStepIndices.remove(stepIndex)
                "SUCCESS"
            }
            CortexStepStatus.CORTEX_STEP_STATUS_ERROR -> {
                userRespondedStepIndices.remove(stepIndex)
                "FAILED"
            }
            CortexStepStatus.CORTEX_STEP_STATUS_CANCELED -> {
                userRespondedStepIndices.remove(stepIndex)
                when {
                    output.contains("rejected", ignoreCase = true) -> "REJECTED"
                    output.contains("terminated", ignoreCase = true) || output.contains("stopped", ignoreCase = true) -> "TERMINATED"
                    step.requested_interaction != null -> "REJECTED"
                    else -> "TERMINATED"
                }
            }
            CortexStepStatus.CORTEX_STEP_STATUS_WAITING -> {
                if (toolType == ToolType.ASK_CHOICE) {
                    if (userRespondedStepIndices.contains(stepIndex)) "RUNNING" else "AWAITING_CHOICE"
                } else {
                    if (userRespondedStepIndices.contains(stepIndex)) "RUNNING" else "PENDING_APPROVAL"
                }
            }
            CortexStepStatus.CORTEX_STEP_STATUS_RUNNING,
            CortexStepStatus.CORTEX_STEP_STATUS_PENDING,
            CortexStepStatus.CORTEX_STEP_STATUS_GENERATING,
            CortexStepStatus.CORTEX_STEP_STATUS_QUEUED -> {
                if (toolType == ToolType.ASK_CHOICE && !userRespondedStepIndices.contains(stepIndex)) {
                    "AWAITING_CHOICE"
                } else {
                    "RUNNING"
                }
            }
            else -> if (step.requested_interaction != null || toolType == ToolType.ASK_CHOICE) {
                if (userRespondedStepIndices.contains(stepIndex)) "RUNNING" else if (toolType == ToolType.ASK_CHOICE) "AWAITING_CHOICE" else "PENDING_APPROVAL"
            } else "RUNNING"
        }

        val startMs = parseTimestampToMillis(step.metadata?.started_at) ?: parseTimestampToMillis(step.metadata?.created_at)
        val endMs = parseTimestampToMillis(step.metadata?.completed_at) ?: parseTimestampToMillis(step.metadata?.finished_generating_at)
        val toolDurationMs = if (startMs != null && endMs != null && endMs >= startMs) {
            endMs - startMs
        } else null

        return ToolCall(
            id = toolId,
            name = rawName,
            toolType = toolType,
            command = command,
            output = output,
            status = status,
            exitCode = exitCode,
            durationMs = toolDurationMs,
            stepIndex = stepIndex,
            trajectoryId = trajectoryId,
            interactionType = "permission",
            questionnaire = questionnaire
        )
    }

    private fun extractUserText(userInput: CortexStepUserInput?): String {
        if (userInput == null) return ""
        val direct = userInput.user_response.takeIf { it.isNotBlank() }
            ?: userInput.query.takeIf { it.isNotBlank() }
            ?: ""
        if (direct.isNotBlank()) return direct
        val itemsText = userInput.items.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } }.joinToString("\n")
        return itemsText
    }

    private fun extractUserAttachments(userInput: CortexStepUserInput?, stepIndex: Int): List<ChatAttachment> {
        if (userInput == null) return emptyList()
        val list = mutableListOf<ChatAttachment>()

        userInput.media.forEachIndexed { idx, media ->
            val resolvedMime = media.mime_type
            val cleanUri = (media.uri).removePrefix("file://")
            val base64Data = if (media.inline_data.size > 0) media.inline_data.base64() else ""
            if (resolvedMime.isBlank() && cleanUri.isBlank() && base64Data.isBlank()) {
                return@forEachIndexed
            }
            val isAud = resolvedMime.startsWith("audio/") || cleanUri.endsWith(".m4a", true) || cleanUri.endsWith(".mp3", true) || cleanUri.endsWith(".wav", true) || cleanUri.endsWith(".ogg", true)
            val isImg = resolvedMime.startsWith("image/") || cleanUri.endsWith(".png", true) || cleanUri.endsWith(".jpg", true) || cleanUri.endsWith(".jpeg", true) || cleanUri.endsWith(".webp", true) || cleanUri.endsWith(".gif", true) || cleanUri.endsWith(".svg", true)
            val dur = (media.duration_seconds).toInt()
            val resolvedName = when {
                media.display_name.isNotBlank() && !media.display_name.startsWith("attachment_") -> media.display_name
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
                    base64 = if (base64Data.isNotBlank()) base64Data else null
                )
            )
        }

        userInput.images.forEachIndexed { idx, img ->
            val cleanUri = (img.uri).removePrefix("file://")
            val base64Data = img.base64_data
            if (cleanUri.isNotBlank() || base64Data.isNotBlank()) {
                val mime = img.mime_type.takeIf { it.isNotBlank() } ?: "image/jpeg"
                list.add(
                    ChatAttachment(
                        id = "att_${stepIndex}_img_$idx",
                        name = img.caption.takeIf { it.isNotBlank() } ?: "Image",
                        path = cleanUri,
                        isImage = true,
                        isAudio = false,
                        durationSeconds = 0,
                        mimeType = mime,
                        base64 = if (base64Data.isNotBlank()) base64Data else null
                    )
                )
            }
        }

        return list
    }

    private fun extractTitleFromSystemMessage(msgText: String): String {
        val clean = msgText.trim()
        if (clean.startsWith("Schedule timer:", ignoreCase = true) || clean.startsWith("Schedule timer", ignoreCase = true)) {
            return "Schedule Timer"
        }
        if (clean.startsWith("Timer has expired", ignoreCase = true)) {
            return "Timer Expired"
        }
        if (clean.startsWith("Task completed:", ignoreCase = true)) {
            return "Task Completed"
        }
        if (clean.contains("[Message]", ignoreCase = true)) {
            val prefix = clean.substringBefore("[Message]").trim().removeSuffix(":")
            if (prefix.isNotBlank() && prefix.length <= 40) {
                return prefix
            }
            return "System Notification"
        }
        val firstLine = clean.lines().firstOrNull()?.trim() ?: ""
        if (firstLine.contains(":") && firstLine.indexOf(":") in 3..40) {
            val candidate = firstLine.substringBefore(":").trim()
            if (!candidate.contains("\n") && candidate.length <= 40) {
                return candidate
            }
        }
        return if (firstLine.isNotBlank() && firstLine.length <= 40) firstLine else "System Notification"
    }
}
