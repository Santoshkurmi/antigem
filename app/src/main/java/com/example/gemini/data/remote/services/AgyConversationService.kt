package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient.SummariesUpdate
import com.example.gemini.data.remote.core.AgyGrpcClient
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

import exa.language_server_pb.CascadeRunStatus
import exa.language_server_pb.JetboxSubscribeToSummariesRequest

/**
 * Dedicated RPC service for conversation lifecycle: summaries subscription, step counting,
 * conversation forking, deletion, trajectory loading, and message reverting.
 */
class AgyConversationService(
    private val grpcClient: AgyGrpcClient = AgyGrpcClient.instance
) {
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
        AgyLanguageService.JetboxSubscribeToSummaries().asFlowSafely(JetboxSubscribeToSummariesRequest()).collect { response ->
            try {
                val frameList = mutableListOf<Conversation>()
                val removedIds = response.deletes.toMutableSet()

                for (entry in response.updates) {
                    val cid = entry.key
                    val summaryObj = entry.value_ ?: continue
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
    suspend fun getRawStepCount(cascadeId: String, hubUrl: String = AuthPreferences.currentHubUrl): Int = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("cascade_id", cascadeId)
                put("trajectory_verbosity", 2)
            }.toString()

            var count = 0
            grpcClient.callStream("GetCascadeTrajectorySteps", payload, hubUrl).collect { frameJson ->
                val json = JSONObject(frameJson)
                val stepsArr = json.optJSONArray("steps")
                if (stepsArr != null) {
                    count = stepsArr.length()
                }
            }
            count
        } catch (e: Exception) {
            Log.e(TAG, "getRawStepCount failed: ${e.message}")
            0
        }
    }

    /**
     * Forks conversation up to the specified stepIndex (or latest step if null)
     */
    suspend fun forkConversation(
        sourceCascadeId: String,
        forkAtStepIndex: Int? = null,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val rawCount = getRawStepCount(sourceCascadeId, hubUrl)
            val targetStep = when {
                forkAtStepIndex != null -> forkAtStepIndex.coerceIn(0, (rawCount - 1).coerceAtLeast(0))
                rawCount > 0 -> rawCount - 1
                else -> 0
            }

            val payload = JSONObject().apply {
                put("sourceCascadeId", sourceCascadeId)
                put("forkAtStepIndex", targetStep)
            }.toString()

            val res = grpcClient.callUnary("ForkConversation", payload, hubUrl)
            res.map { body ->
                val json = JSONObject(body)
                json.optString("newCascadeId", "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "forkConversation failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Deletes a conversation trajectory from daemon storage
     */
    suspend fun deleteCascadeTrajectory(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("cascadeId", cascadeId)
        }.toString()

        grpcClient.callUnary("DeleteCascadeTrajectory", payload, hubUrl).map { true }
    }

    /**
     * Loads raw trajectory steps for a conversation via Connect-RPC Unary
     */
    suspend fun getCascadeTrajectorySteps(
        cascadeId: String,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("cascadeId", cascadeId)
                put("trajectoryVerbosity", 2)
            }.toString()
            grpcClient.callUnary("GetCascadeTrajectorySteps", payload, hubUrl)
        } catch (e: Exception) {
            Log.e(TAG, "getCascadeTrajectorySteps failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Reverts the entire last user message turn on the AGY hub daemon trajectory.
     */
    suspend fun revertLastUserMessage(
        cascadeId: String,
        modelEnum: String = "MODEL_PLACEHOLDER_M319",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val stepsRes = getCascadeTrajectorySteps(cascadeId, hubUrl)
            if (!stepsRes.isSuccess) {
                return@withContext Result.failure(stepsRes.exceptionOrNull() ?: Exception("Failed to get trajectory steps"))
            }
            val stepsJson = JSONObject(stepsRes.getOrThrow())
            val stepsArr = stepsJson.optJSONArray("steps") ?: JSONArray()
            var lastUserIdx = -1
            for (i in (stepsArr.length() - 1) downTo 0) {
                val st = stepsArr.optJSONObject(i) ?: continue
                if (st.has("userInput") || st.optString("type") == "CORTEX_STEP_TYPE_USER_INPUT") {
                    lastUserIdx = i
                    break
                }
            }

            if (lastUserIdx <= 0) {
                deleteCascadeTrajectory(cascadeId, hubUrl)
                return@withContext Result.success(-1)
            }

            val targetStep = (lastUserIdx - 1).coerceAtLeast(0)
            val revertPayload = JSONObject().apply {
                put("cascadeId", cascadeId)
                put("stepIndex", targetStep)
                put("overrideConfig", JSONObject().apply {
                    put("plannerConfig", JSONObject().apply {
                        put("toolConfig", JSONObject().apply {
                            put("runCommand", JSONObject().apply {
                                put("autoCommandConfig", JSONObject().apply {
                                    put("autoExecutionPolicy", "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER")
                                })
                            })
                            put("notifyUser", JSONObject())
                        })
                        put("requestedModel", JSONObject().apply {
                            put("model", modelEnum)
                        })
                        put("knowledgeConfig", JSONObject())
                        put("useAiCredits", false)
                        put("supportsLatexRendering", true)
                    })
                    put("conversationHistoryConfig", JSONObject())
                })
            }.toString()

            val revertRes = grpcClient.callUnary("RevertToCascadeStep", revertPayload, hubUrl)
            if (!revertRes.isSuccess) {
                return@withContext Result.failure(revertRes.exceptionOrNull() ?: Exception("RevertToCascadeStep failed"))
            }
            Result.success(targetStep)
        } catch (e: Exception) {
            Log.e(TAG, "revertLastUserMessage failed: ${e.message}")
            Result.failure(e)
        }
    }
}

