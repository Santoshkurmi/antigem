package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient.SummariesUpdate

import com.example.gemini.domain.model.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

import exa.language_server_pb.CascadeConfig
import exa.language_server_pb.CascadePlannerConfig
import exa.language_server_pb.CascadeRunStatus
import exa.language_server_pb.ClientTrajectoryVerbosity
import exa.language_server_pb.CortexStepType
import exa.language_server_pb.DeleteCascadeTrajectoryRequest
import exa.language_server_pb.ForkConversationRequest
import exa.language_server_pb.GetCascadeTrajectoryStepsRequest
import exa.language_server_pb.JetboxSubscribeToSummariesRequest
import exa.language_server_pb.Model
import exa.language_server_pb.ModelOrAlias
import exa.language_server_pb.RevertToCascadeStepRequest
import exa.language_server_pb.Step

/**
 * Dedicated RPC service for conversation lifecycle: summaries subscription, step counting,
 * conversation forking, deletion, trajectory loading, and message reverting.
 */
class AgyConversationService {
    companion object {
        private const val TAG = "AgyConversationService"
        val instance by lazy { AgyConversationService() }

        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    /**
     * Subscribes to live conversation summary updates pushed by the AGY daemon via typed Wire LanguageServerService.
     */
    fun subscribeToSummaries(hubUrl: String = AuthPreferences.currentHubUrl): Flow<SummariesUpdate> = flow {
        if (!com.example.gemini.data.remote.AgyBridgeService.instance.awaitHubReady(timeoutMs = 10_000L)) {
            throw Exception("Antigravity Hub is not running")
        }
        AgyLanguageService.JetboxSubscribeToSummaries().asFlowSafely(JetboxSubscribeToSummariesRequest())
            .collect { response ->
                try {
                    val frameList = mutableListOf<Conversation>()
                    val removedIds = response.deletes.toMutableSet()

                    for ((cid, summaryObj) in response.updates) {
                        val annotations = summaryObj.annotations
                        val annTitle = annotations?.title?.takeIf { it.isNotBlank() }
                        val rawSummary = summaryObj.summary.takeIf { it.isNotBlank() }
                        val stepCount = summaryObj.step_count
                        val status = summaryObj.status
                        val isDeleted = status == CascadeRunStatus.CASCADE_RUN_STATUS_UNSPECIFIED && summaryObj.killed

                        val hasContent = (annTitle != null || rawSummary != null || stepCount > 0) && !isDeleted
                        if (!hasContent) {
                            removedIds.add(cid)
                            continue
                        }

                        val summary = annTitle ?: rawSummary ?: "Conversation"
                        val lastModEpoch = summaryObj.last_modified_time?.let { ts ->
                            ts.seconds * 1000L + (ts.nanos / 1_000_000L)
                        } ?: System.currentTimeMillis()

                        val createdEpoch = summaryObj.created_time?.let { ts ->
                            ts.seconds * 1000L + (ts.nanos / 1_000_000L)
                        } ?: lastModEpoch

                        val notFullyIdle = summaryObj.not_fully_idle
                        val hasActivity = summaryObj.has_activity
                        val isRunning = status == CascadeRunStatus.CASCADE_RUN_STATUS_RUNNING

                        val metaObj = summaryObj.trajectory_metadata
                        val parentCid = metaObj?.parent_conversation_id?.takeIf { it.isNotBlank() }
                            ?: summaryObj.fork_parent_conversation_id.takeIf { it.isNotBlank() }
                        val subagentSpec = metaObj?.subagent_spec
                        val subagentRole = subagentSpec?.role?.takeIf { it.isNotBlank() }
                        val subagentTypeName = subagentSpec?.type_name?.takeIf { it.isNotBlank() }
                        val nestingDepth = metaObj?.nesting_depth ?: 0

                        val wsUri = metaObj?.workspace_uris?.firstOrNull { it.isNotBlank() }
                            ?: summaryObj.workspaces.firstOrNull()?.workspace_folder_absolute_uri?.takeIf { it.isNotBlank() }
                            ?: ""

                        frameList.add(
                            Conversation(
                                id = cid,
                                title = summary,
                                modelId = "",
                                sessionId = cid,
                                summary = summary,
                                createdAt = createdEpoch,
                                updatedAt = lastModEpoch,
                                isRunning = isRunning,
                                notFullyIdle = notFullyIdle,
                                hasActivity = hasActivity,
                                runStatus = status.name,
                                stepCount = stepCount,
                                workspaceUri = wsUri,
                                parentConversationId = parentCid,
                                subagentRole = subagentRole,
                                subagentTypeName = subagentTypeName,
                                nestingDepth = nestingDepth
                            )
                        )
                    }
                    emit(SummariesUpdate(frameList, removedIds))
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing conversation updates: ${e.message}", e)
                }
            }
    }.flowOn(Dispatchers.IO)

    /**
     * Gets raw step count for a conversation
     */
    suspend fun getRawStepCount(cascadeId: String, hubUrl: String = AuthPreferences.currentHubUrl): Int =
        withContext(Dispatchers.IO) {
            val req = GetCascadeTrajectoryStepsRequest(
                cascade_id = cascadeId,
                trajectory_verbosity = ClientTrajectoryVerbosity.CLIENTTRAJECTORYVERBOSITY_CLIENT_TRAJECTORY_VERBOSITY_UNSPECIFIED
            )
            AgyLanguageService.GetCascadeTrajectorySteps().executeSafely(req).map { res ->
                res.steps.size
            }.getOrDefault(0)
        }

    /**
     * Forks conversation up to the specified stepIndex (or latest step if null)
     */
    suspend fun forkConversation(
        sourceCascadeId: String,
        forkAtStepIndex: Int? = null,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        val rawCount = getRawStepCount(sourceCascadeId, hubUrl)
        val targetStep = when {
            forkAtStepIndex != null -> forkAtStepIndex.coerceIn(0, (rawCount - 1).coerceAtLeast(0))
            rawCount > 0 -> rawCount - 1
            else -> 0
        }

        val req = ForkConversationRequest(
            source_cascade_id = sourceCascadeId,
            fork_at_step_index = targetStep
        )
        AgyLanguageService.ForkConversation().executeSafely(req).map { res ->
            res.new_cascade_id
        }
    }

    /**
     * Deletes a conversation trajectory from daemon storage
     */
    suspend fun deleteCascadeTrajectory(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val req = DeleteCascadeTrajectoryRequest(cascade_id = cascadeId)
        AgyLanguageService.DeleteCascadeTrajectory().executeSafely(req).map { true }
    }

    /**
     * Loads raw trajectory steps for a conversation via Wire typed RPC
     */
    suspend fun getCascadeTrajectorySteps(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<Step>> = withContext(Dispatchers.IO) {
        val req = GetCascadeTrajectoryStepsRequest(
            cascade_id = cascadeId,
            trajectory_verbosity = ClientTrajectoryVerbosity.CLIENTTRAJECTORYVERBOSITY_CLIENT_TRAJECTORY_VERBOSITY_UNSPECIFIED
        )
        AgyLanguageService.GetCascadeTrajectorySteps().executeSafely(req).map { res ->
            res.steps
        }
    }

    private fun toModelProto(name: String?): Model? {
        if (name.isNullOrBlank()) return null
        return try {
            Model.valueOf(name)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reverts a user message turn on the AGY hub daemon trajectory.
     * If [targetStepIndex] is provided, reverts to the step before that user message.
     * If [targetStepIndex] is null, reverts to the step before the last user message.
     */
    suspend fun revertUserMessage(
        cascadeId: String,
        modelEnum: String = "",
        targetStepIndex: Int? = null,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Int> = withContext(Dispatchers.IO) {
        val resolvedModelStr = com.example.gemini.data.remote.AgyHubClient.resolveModelEnum(modelEnum)
        val modelProto = toModelProto(resolvedModelStr)

        val targetStep = if (targetStepIndex != null) {
            if (targetStepIndex <= 0) {
                deleteCascadeTrajectory(cascadeId, hubUrl)
                return@withContext Result.success(-1)
            }
            (targetStepIndex - 1).coerceAtLeast(0)
        } else {
            val stepsRes = getCascadeTrajectorySteps(cascadeId, hubUrl)
            if (!stepsRes.isSuccess) {
                return@withContext Result.failure(
                    stepsRes.exceptionOrNull() ?: Exception("Failed to get trajectory steps")
                )
            }
            val steps = stepsRes.getOrThrow()
            var lastUserIdx = -1
            for (i in (steps.size - 1) downTo 0) {
                val step = steps[i]
                if (step.type == CortexStepType.CORTEX_STEP_TYPE_USER_INPUT || step.user_input != null) {
                    lastUserIdx = i
                    break
                }
            }

            if (lastUserIdx <= 0) {
                deleteCascadeTrajectory(cascadeId, hubUrl)
                return@withContext Result.success(-1)
            }
            (lastUserIdx - 1).coerceAtLeast(0)
        }

        val req = RevertToCascadeStepRequest(
            cascade_id = cascadeId,
            step_index = targetStep,
            conversation_only = true,
            override_config = CascadeConfig(
                planner_config = CascadePlannerConfig(
                    requested_model = if (modelProto != null) ModelOrAlias(model = modelProto) else null
                )
            )
        )
        AgyLanguageService.RevertToCascadeStep().executeSafely(req).map { targetStep }
    }

    suspend fun revertLastUserMessage(
        cascadeId: String,
        modelEnum: String = "",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Int> = revertUserMessage(cascadeId, modelEnum, null, hubUrl)
}

