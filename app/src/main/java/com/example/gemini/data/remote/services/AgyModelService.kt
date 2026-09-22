package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.core.AgyGrpcClient
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

/**
 * Dedicated RPC service for AI models and user quota telemetry.
 */
class AgyModelService(
    private val grpcClient: AgyGrpcClient = AgyGrpcClient.instance
) {
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
     * Fetches all available models from daemon via Unary Connect-RPC and maps them to AiModel.
     */
    suspend fun getAvailableModels(
        forceRefresh: Boolean = false,
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<List<AiModel>> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("forceRefresh", forceRefresh)
            }.toString()

            val res = grpcClient.callUnary("GetAvailableModels", payload, hubUrl)
            if (!res.isSuccess) {
                return@withContext Result.failure(res.exceptionOrNull() ?: Exception("Failed to fetch models"))
            }
            val modelsJson = res.getOrThrow()
            if (modelsJson.isBlank()) {
                return@withContext Result.failure(Exception("Empty models response from server"))
            }

            val root = JSONObject(modelsJson)
            val resp = root.optJSONObject("response") ?: root
            val rawModels = resp.optJSONObject("models") ?: JSONObject()

            val sorts = resp.optJSONArray("agentModelSorts")
                ?: resp.optJSONArray("modelSorts")
                ?: resp.optJSONArray("sorts")
                ?: JSONArray()
            val sortedIds = mutableListOf<String>()
            for (i in 0 until sorts.length()) {
                val sortObj = sorts.getJSONObject(i)
                val groups = sortObj.optJSONArray("groups") ?: JSONArray()
                for (j in 0 until groups.length()) {
                    val grp = groups.getJSONObject(j)
                    val modelIds = grp.optJSONArray("modelIds")
                        ?: grp.optJSONArray("models")
                        ?: JSONArray()
                    for (k in 0 until modelIds.length()) {
                        val mid = modelIds.getString(k)
                        if (mid.isNotBlank() && !sortedIds.contains(mid)) sortedIds.add(mid)
                    }
                }
            }

            val keysToProcess = if (sortedIds.isNotEmpty()) {
                sortedIds
            } else {
                val allKeys = mutableListOf<String>()
                val iter = rawModels.keys()
                while (iter.hasNext()) {
                    val k = iter.next()
                    val details = rawModels.optJSONObject(k)
                    if (details != null && !details.optBoolean("isInternal", false) && !details.optBoolean("disabled", false) && details.has("displayName")) {
                        allKeys.add(k)
                    }
                }
                allKeys
            }

            val resultList = mutableListOf<AiModel>()
            for (key in keysToProcess) {
                val details = rawModels.optJSONObject(key) ?: continue
                if (details.optBoolean("disabled", false)) continue
                if (details.optBoolean("isInternal", false)) continue
                if (details.optBoolean("hideInModelPicker", false)) continue

                val displayName = details.optString("displayName", key)
                val modelEnum = details.optString("model", key)
                val supportsThinking = details.optBoolean("supportsThinking", false)

                var baseName = displayName
                var tier: String? = null
                val tierMatch = Regex("^(.*?)\\s*\\((High|Medium|Low|Med|Thinking)\\)$", RegexOption.IGNORE_CASE).find(baseName)
                if (tierMatch != null) {
                    baseName = tierMatch.groupValues[1].trim()
                    tier = tierMatch.groupValues[2].replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }

                val family = when {
                    displayName.contains("claude", ignoreCase = true) || key.contains("claude", ignoreCase = true) -> ModelFamily.CLAUDE
                    else -> ModelFamily.GEMINI
                }

                resultList.add(
                    AiModel(
                        id = modelEnum,
                        displayName = displayName,
                        family = family,
                        supportsThinking = supportsThinking,
                        description = details.optString("description", ""),
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
     * Fetches user quota summary (5-hour and weekly buckets) from RetrieveUserQuotaSummary
     */
    suspend fun retrieveUserQuotaSummary(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<QuotaSummaryResponse> = withContext(Dispatchers.IO) {
        try {
            val res = grpcClient.callUnary("RetrieveUserQuotaSummary", "{}", hubUrl)
            res.map { body ->
                val root = JSONObject(body)
                val resp = root.optJSONObject("response") ?: root
                val groupsArr = resp.optJSONArray("groups") ?: JSONArray()
                val groupsList = mutableListOf<ModelQuotaGroup>()

                for (i in 0 until groupsArr.length()) {
                    val grp = groupsArr.getJSONObject(i)
                    val dispName = grp.optString("displayName", "")
                    val desc = grp.optString("description", "")
                    val buckets = grp.optJSONArray("buckets") ?: JSONArray()

                    var fiveHourInfo: QuotaWindowInfo? = null
                    var weeklyInfo: QuotaWindowInfo? = null

                    for (j in 0 until buckets.length()) {
                        val b = buckets.getJSONObject(j)
                        val window = b.optString("window", "")
                        val remFraction = b.optDouble("remainingFraction", 1.0).toFloat()
                        val remPct = String.format(Locale.US, "%.1f%%", remFraction * 100f)
                        val usedPct = String.format(Locale.US, "%.1f%%", (1.0f - remFraction) * 100f)
                        val resetTime = b.optString("resetTime", "").takeIf { it.isNotBlank() }
                        val bDesc = b.optString("description", "")
                        val countdown = formatQuotaResetCountdown(resetTime)

                        val windowInfo = QuotaWindowInfo(
                            window = window,
                            displayName = b.optString("displayName", window),
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

                QuotaSummaryResponse(
                    groups = groupsList,
                    lastUpdated = ISO_FORMAT.format(Date())
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "retrieveUserQuotaSummary failed: ${e.message}")
            Result.failure(e)
        }
    }
}

