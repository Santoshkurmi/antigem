package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences

import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuotaGroup
import com.example.gemini.domain.model.QuotaSummaryResponse
import com.example.gemini.domain.model.QuotaWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

import exa.language_server_pb.GetAvailableModelsRequest
import exa.language_server_pb.Model
import exa.language_server_pb.RetrieveUserQuotaSummaryRequest

/**
 * Dedicated RPC service for AI models and user quota telemetry.
 */
class AgyModelService {
    companion object {
        private const val TAG = "AgyModelService"
        val instance by lazy { AgyModelService() }

        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val keyToModelEnum = ConcurrentHashMap<String, String>()
        private val nameToModelEnum = ConcurrentHashMap<String, String>()
        private val allValidEnums = CopyOnWriteArraySet<String>()
        @Volatile
        private var defaultModelEnum: String = ""

        fun updateModelRegistry(models: List<AiModel>) {
            if (models.isEmpty()) return
            models.forEach { m ->
                allValidEnums.add(m.id)
                if (m.key.isNotBlank()) {
                    keyToModelEnum[m.key.lowercase()] = m.id
                }
                keyToModelEnum[m.displayName.lowercase()] = m.id
                if (m.baseName.isNotBlank()) {
                    nameToModelEnum[m.baseName.lowercase()] = m.id
                }
            }
            if (defaultModelEnum.isBlank() || !allValidEnums.contains(defaultModelEnum)) {
                defaultModelEnum = models.firstOrNull {
                    it.displayName.contains("flash", ignoreCase = true) || it.key.contains("flash", ignoreCase = true)
                }?.id ?: models.first().id
            }
        }

        fun resolveModelEnum(rawInput: String?): String {
            if (rawInput.isNullOrBlank()) return defaultModelEnum.ifBlank { "MODEL_PLACEHOLDER_M319" }
            if (allValidEnums.contains(rawInput)) return rawInput

            val lower = rawInput.lowercase().trim()
            keyToModelEnum[lower]?.let { return it }
            nameToModelEnum[lower]?.let { return it }

            for ((k, v) in keyToModelEnum) {
                if (lower.contains(k) || k.contains(lower)) return v
            }

            if (rawInput.startsWith("MODEL_")) return rawInput
            return defaultModelEnum.ifBlank { rawInput }
        }

        fun formatQuotaResetCountdown(isoString: String?): String {
            if (isoString.isNullOrBlank()) return ""
            return try {
                val cleanIso = if (isoString.length > 19) isoString.substring(0, 19) else isoString
                val targetTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(cleanIso)?.time ?: return ""

                val now = System.currentTimeMillis()
                val diffMs = targetTime - now
                if (diffMs <= 0) return "Resetting soon"

                val hours = (diffMs / (1000 * 60 * 60)).toInt()
                val mins = ((diffMs % (1000 * 60 * 60)) / (1000 * 60)).toInt()
                val parts = mutableListOf<String>()
                if (hours > 0) parts.add("${hours}h")
                if (mins > 0 || hours == 0) parts.add("${mins}m")
                "Resets in " + parts.joinToString(" ")
            } catch (e: Exception) {
                ""
            }
        }
    }

    /**
     * Fetches all available models from daemon via typed AgyLanguageService and maps them to AiModel.
     */
    suspend fun getAvailableModels(
        forceRefresh: Boolean = false,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val req = GetAvailableModelsRequest(force_refresh = forceRefresh)
            val res = AgyLanguageService.GetAvailableModels().executeSafely(req)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("Failed to fetch models"))
            }
            val response = res.getOrThrow().response
                ?: return@withContext Result.failure(Exception("Empty models response from server"))

            val modelsMap = response.models
            val sorts = response.agent_model_sorts.ifEmpty { response.battle_mode_model_sorts }
            val sortedIds = mutableListOf<String>()

            for (sort in sorts) {
                for (grp in sort.groups) {
                    for (mid in grp.model_ids) {
                        if (mid.isNotBlank() && !sortedIds.contains(mid)) {
                            sortedIds.add(mid)
                        }
                    }
                }
            }

            val keysToProcess = if (sortedIds.isNotEmpty()) {
                sortedIds
            } else {
                response.models.mapNotNull { (key, details) ->
                    if (details != null && !details.is_internal && !details.disabled && details.display_name.isNotBlank()) {
                        key
                    } else null
                }
            }

            val resultList = mutableListOf<AiModel>()
            for (key in keysToProcess) {
                val details = modelsMap[key] ?: continue
                if (details.disabled) continue
                if (details.is_internal) continue

                val displayName = details.display_name.ifBlank { key }
                val modelEnum = details.model
                val modelEnumName = if (modelEnum != Model.MODEL_UNSPECIFIED) {
                    modelEnum.name
                } else {
                    key
                }
                val supportsThinking = details.supports_thinking

                var baseName = displayName
                var tier: String? = null
                val tierMatch = Regex("^(.*?)\\s*\\((High|Medium|Low|Med|Thinking)\\)$", RegexOption.IGNORE_CASE).find(baseName)
                if (tierMatch != null) {
                    baseName = tierMatch.groupValues[1].trim()
                    tier = tierMatch.groupValues[2].replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }

                val family = when {
                    key.startsWith("openrouter/", ignoreCase = true) || key.contains("openrouter", ignoreCase = true) || details.description.contains("openrouter", ignoreCase = true) -> ModelFamily.OPENROUTER
                    displayName.contains("claude", ignoreCase = true) || key.contains("claude", ignoreCase = true) -> ModelFamily.CLAUDE
                    else -> ModelFamily.GEMINI
                }

                resultList.add(
                    AiModel(
                        id = modelEnumName,
                        displayName = displayName,
                        family = family,
                        supportsThinking = supportsThinking,
                        description = details.description,
                        key = key,
                        baseName = baseName,
                        tier = tier
                    )
                )
            }

            updateModelRegistry(resultList)
            Result.success(resultList)
        } catch (e: Exception) {
            Log.e(TAG, "getAvailableModels failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fetches user quota summary (5-hour and weekly buckets) from RetrieveUserQuotaSummary via typed AgyLanguageService
     */
    suspend fun retrieveUserQuotaSummary(
        forceRefresh: Boolean = false,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<QuotaSummaryResponse> = withContext(Dispatchers.IO) {
        try {
            val req = RetrieveUserQuotaSummaryRequest(force_refresh = forceRefresh)
            val res = AgyLanguageService.RetrieveUserQuotaSummary().executeSafely(req)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("Failed to retrieve quota summary"))
            }
            val rawResponse = res.getOrThrow()
            Log.d(TAG, "retrieveUserQuotaSummary response: $rawResponse")
            val quotaResp = rawResponse.response
            val groupsList = mutableListOf<ModelQuotaGroup>()

            if (quotaResp != null) {
                for (grp in quotaResp.groups) {
                    val dispName = grp.display_name
                    val desc = grp.description
                    val buckets = grp.buckets

                    var fiveHourInfo: QuotaWindowInfo? = null
                    var weeklyInfo: QuotaWindowInfo? = null

                    for (b in buckets) {
                        val window = b.window
                        val remFraction = b.remaining_fraction
                        val remPct = String.format(Locale.US, "%.1f%%", remFraction * 100f)
                        val usedPct = String.format(Locale.US, "%.1f%%", (1.0f - remFraction) * 100f)
                        val resetTime = b.reset_time?.let { ts ->
                            val epochMs = ts.seconds * 1000L + (ts.nanos / 1_000_000L)
                            ISO_FORMAT.format(Date(epochMs))
                        }
                        val bDesc = b.description
                        val countdown = formatQuotaResetCountdown(resetTime)

                        val windowInfo = QuotaWindowInfo(
                            window = window,
                            displayName = b.display_name.ifBlank { window },
                            remainingFraction = remFraction,
                            remainingPct = remPct,
                            usedPct = usedPct,
                            resetTime = resetTime,
                            countdown = countdown,
                            description = bDesc
                        )

                        if (window.contains("5h", ignoreCase = true)) {
                            fiveHourInfo = windowInfo
                        } else if (window.contains("week", ignoreCase = true) || window.contains("7d", ignoreCase = true)) {
                            weeklyInfo = windowInfo
                        } else {
                            if (fiveHourInfo == null) {
                                fiveHourInfo = windowInfo
                            } else if (weeklyInfo == null) {
                                weeklyInfo = windowInfo
                            }
                        }
                    }

                val gId = when {
                    dispName.contains("gemini", ignoreCase = true) -> "gemini"
                    dispName.contains("claude", ignoreCase = true) || dispName.contains("gpt", ignoreCase = true) -> "claude_gpt"
                    else -> dispName.lowercase().replace(" ", "_")
                }

                    groupsList.add(
                        ModelQuotaGroup(
                            groupId = gId,
                            groupName = dispName,
                            description = desc,
                            fiveHour = fiveHourInfo,
                            weekly = weeklyInfo
                        )
                    )
                }
            }

            Result.success(
                QuotaSummaryResponse(
                    groups = groupsList,
                    lastUpdated = ISO_FORMAT.format(Date())
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "retrieveUserQuotaSummary failed: ${e.message}")
            Result.failure(e)
        }
    }
}

