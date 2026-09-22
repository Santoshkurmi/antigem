package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient.AgyAuthInfo
import com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus
import com.example.gemini.data.remote.core.AgyGrpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Dedicated RPC & HTTP service for authentication, OAuth status, and detailed user profile queries.
 */
class AgyAuthService(
    private val grpcClient: AgyGrpcClient = AgyGrpcClient.instance
) {
    companion object {
        private const val TAG = "AgyAuthService"
        val instance by lazy { AgyAuthService() }
    }

    suspend fun login(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> =
        grpcClient.callUnary("Login", JSONObject().apply { put("isGcpTos", false) }.toString(), hubUrl).map { }

    suspend fun authLogout(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> =
        grpcClient.callUnary("AuthLogout", "{}", hubUrl).map { }

    suspend fun fetchLoginUrl(bridgeHttpUrl: String = AuthPreferences.currentBridgeHttpUrl): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${bridgeHttpUrl.trimEnd('/')}/api/auth/login-url")
                .get()
                .build()
            grpcClient.okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val url = json.optString("loginUrl", "")
                    if (url.isNotBlank()) url else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun startBridgeLogin(bridgeHttpUrl: String = AuthPreferences.currentBridgeHttpUrl): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${bridgeHttpUrl.trimEnd('/')}/api/auth/start-login")
                .post("{}".toRequestBody(AgyGrpcClient.JSON_MEDIA_TYPE))
                .build()
            grpcClient.okHttpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getAuthStatus(hubUrl: String = AuthPreferences.currentHubUrl): Result<Boolean> = withContext(Dispatchers.IO) {
        grpcClient.callUnary("GetAuthStatus", "{}", hubUrl).map { body ->
            try {
                val json = JSONObject(body)
                val authResult = json.optJSONObject("authResult") ?: json
                authResult.optBoolean("hasValidAuth", false)
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun getLocalUserInfo(hubUrl: String = AuthPreferences.currentHubUrl): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        grpcClient.callUnary("GetLocalUserInfo", "{}", hubUrl).map { body ->
            val json = JSONObject(body)
            val username = json.optString("username", "")
            val homeDir = json.optString("homeDirUri", "")
            username to homeDir
        }
    }

    suspend fun fetchDetailedAuthInfo(
        hubUrl: String = AuthPreferences.currentHubUrl,
        bridgeHttpUrl: String = AuthPreferences.currentBridgeHttpUrl
    ): Result<AgyAuthInfo> = withContext(Dispatchers.IO) {
        try {
            val authResultCall = grpcClient.callUnary("GetAuthStatus", "{\"metadata\":{}}", hubUrl)
            if (authResultCall.isFailure) {
                return@withContext Result.failure(authResultCall.exceptionOrNull() ?: Exception("Failed to query GetAuthStatus"))
            }
            val authBody = authResultCall.getOrThrow()
            val authJson = JSONObject(authBody)
            val authResult = authJson.optJSONObject("authResult")
            val hasValidAuth = authResult?.optBoolean("hasValidAuth", false) ?: false

            if (!hasValidAuth) {
                return@withContext Result.success(
                    AgyAuthInfo(
                        status = AgyAuthStatus.UNAUTHENTICATED,
                        isLoggedIn = false
                    )
                )
            }

            val scopesList = mutableListOf<String>()
            val scopesArr = authResult?.optJSONArray("grantedScopes")
            if (scopesArr != null) {
                for (i in 0 until scopesArr.length()) {
                    scopesList.add(scopesArr.getString(i))
                }
            }

            var fullName = ""
            var email = ""
            var userTier = ""
            var userTierId = ""
            var userTierDesc = ""
            var planName = ""
            var teamsTier = ""
            var availablePromptCredits: Long? = null
            var availableFlowCredits: Long? = null
            var monthlyPromptCredits: Long? = null
            var monthlyFlowCredits: Long? = null
            var upgradeUri = ""
            var upgradeText = ""
            var profilePic: String? = null

            // Directly query AGY LanguageServerService/GetUserStatus with {"metadata":{}}
            try {
                val statusBody = grpcClient.callUnary("GetUserStatus", "{\"metadata\":{}}", hubUrl).getOrNull() ?: "{}"
                val statusJson = JSONObject(statusBody)
                val userStatus = statusJson.optJSONObject("userStatus")
                if (userStatus != null) {
                    val nameFromStatus = userStatus.optString("name", "").takeIf { it.isNotBlank() && it != "null" }
                    val emailFromStatus = userStatus.optString("email", "").takeIf { it.isNotBlank() && it != "null" }
                    val picFromStatus = userStatus.optString("profilePictureUrl", "").takeIf { it.isNotBlank() && it != "null" }
                    if (!nameFromStatus.isNullOrBlank()) fullName = nameFromStatus
                    if (!emailFromStatus.isNullOrBlank()) email = emailFromStatus
                    if (!picFromStatus.isNullOrBlank()) profilePic = picFromStatus

                    val tierObj = userStatus.optJSONObject("userTier")
                    if (tierObj != null) {
                        userTier = tierObj.optString("name", tierObj.optString("description", ""))
                        userTierId = tierObj.optString("id", "")
                        userTierDesc = tierObj.optString("description", "")
                        upgradeUri = tierObj.optString("upgradeSubscriptionUri", "")
                        upgradeText = tierObj.optString("upgradeSubscriptionText", "")
                    }

                    val planStatus = userStatus.optJSONObject("planStatus")
                    if (planStatus != null) {
                        if (planStatus.has("availablePromptCredits")) {
                            availablePromptCredits = planStatus.optLong("availablePromptCredits")
                        }
                        if (planStatus.has("availableFlowCredits")) {
                            availableFlowCredits = planStatus.optLong("availableFlowCredits")
                        }

                        val planInfo = planStatus.optJSONObject("planInfo")
                        if (planInfo != null) {
                            planName = planInfo.optString("planName", "")
                            teamsTier = planInfo.optString("teamsTier", "")
                            if (planInfo.has("monthlyPromptCredits")) {
                                monthlyPromptCredits = planInfo.optLong("monthlyPromptCredits")
                            }
                            if (planInfo.has("monthlyFlowCredits")) {
                                monthlyFlowCredits = planInfo.optLong("monthlyFlowCredits")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GetUserStatus parse error: ${e.message}")
            }

            // Local user info fallback for username and homeDir
            var localUsername = ""
            var localHomeDir = ""
            try {
                val localInfo = getLocalUserInfo(hubUrl).getOrNull()
                if (localInfo != null) {
                    localUsername = localInfo.first
                    localHomeDir = localInfo.second
                }
            } catch (_: Exception) {}

            Result.success(
                AgyAuthInfo(
                    status = AgyAuthStatus.AUTHENTICATED,
                    isLoggedIn = true,
                    fullName = fullName,
                    email = email,
                    username = localUsername,
                    homeDir = localHomeDir,
                    userTier = userTier,
                    userTierId = userTierId,
                    userTierDescription = userTierDesc,
                    planName = planName,
                    teamsTier = teamsTier,
                    availablePromptCredits = availablePromptCredits,
                    availableFlowCredits = availableFlowCredits,
                    monthlyPromptCredits = monthlyPromptCredits,
                    monthlyFlowCredits = monthlyFlowCredits,
                    upgradeSubscriptionUri = upgradeUri,
                    upgradeSubscriptionText = upgradeText,
                    profilePictureUrl = profilePic,
                    grantedScopes = scopesList,
                    isOffline = false
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchDetailedAuthInfo error: ${e.message}")
            Result.failure(e)
        }
    }
}

