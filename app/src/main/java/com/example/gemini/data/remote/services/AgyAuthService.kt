package com.example.gemini.data.remote.services

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient.AgyAuthInfo
import com.example.gemini.data.remote.AgyHubClient.AgyAuthStatus
import exa.language_server_pb.AuthLogoutRequest
import exa.language_server_pb.GetAuthStatusRequest
import exa.language_server_pb.GetLocalUserInfoRequest
import exa.language_server_pb.GetUserStatusRequest
import exa.language_server_pb.LoginRequest
import exa.language_server_pb.Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated RPC service for authentication, OAuth status, and detailed user profile queries.
 * Uses typed Square Wire AgyLanguageService gRPC client.
 */
class AgyAuthService {
    companion object {
        private const val TAG = "AgyAuthService"
        val instance by lazy { AgyAuthService() }
    }

    suspend fun login(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> = withContext(Dispatchers.IO) {
        val req = LoginRequest(is_gcp_tos = false)
        AgyLanguageService.Login().executeSafely(req).map { }
    }

    suspend fun authLogout(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> = withContext(Dispatchers.IO) {
        val req = AuthLogoutRequest()
        AgyLanguageService.AuthLogout().executeSafely(req).map { }
    }

    suspend fun getAuthStatus(hubUrl: String = AuthPreferences.currentHubUrl): Result<Boolean> = withContext(Dispatchers.IO) {
        val req = GetAuthStatusRequest()
        AgyLanguageService.GetAuthStatus().executeSafely(req).map { res ->
            res.auth_result?.has_valid_auth == true
        }
    }

    suspend fun getLocalUserInfo(hubUrl: String = AuthPreferences.currentHubUrl): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val req = GetLocalUserInfoRequest()
        AgyLanguageService.GetLocalUserInfo().executeSafely(req).map { res ->
            res.username to res.home_dir_uri
        }
    }

    suspend fun fetchDetailedAuthInfo(
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<AgyAuthInfo> = withContext(Dispatchers.IO) {
        try {
            val authStatusRes = AgyLanguageService.GetAuthStatus().executeSafely(GetAuthStatusRequest())
            if (authStatusRes.isFailure) {
                return@withContext Result.failure(authStatusRes.exceptionOrNull() ?: Exception("Failed to query GetAuthStatus"))
            }

            val authResult = authStatusRes.getOrThrow().auth_result
            val hasValidAuth = authResult?.has_valid_auth == true

            if (!hasValidAuth) {
                return@withContext Result.success(
                    AgyAuthInfo(
                        status = AgyAuthStatus.UNAUTHENTICATED,
                        isLoggedIn = false
                    )
                )
            }

            val scopesList = authResult.granted_scopes

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

            try {
                val userStatusRes = AgyLanguageService.GetUserStatus().executeSafely(GetUserStatusRequest(metadata = Metadata()))
                val userStatus = userStatusRes.getOrNull()?.user_status
                if (userStatus != null) {
                    val nameFromStatus = userStatus.name.takeIf { it.isNotBlank() && it != "null" }
                    val emailFromStatus = userStatus.email.takeIf { it.isNotBlank() && it != "null" }
                    val picFromStatus = userStatus.profile_picture_url.takeIf { it.isNotBlank() && it != "null" }
                    if (!nameFromStatus.isNullOrBlank()) fullName = nameFromStatus
                    if (!emailFromStatus.isNullOrBlank()) email = emailFromStatus
                    if (!picFromStatus.isNullOrBlank()) profilePic = picFromStatus

                    val tierObj = userStatus.user_tier
                    if (tierObj != null) {
                        userTier = tierObj.name.ifBlank { tierObj.description }
                        userTierId = tierObj.id
                        userTierDesc = tierObj.description
                        upgradeUri = tierObj.upgrade_subscription_uri
                        upgradeText = tierObj.upgrade_subscription_text
                    }

                    val planStatus = userStatus.plan_status
                    if (planStatus != null) {
                        availablePromptCredits = planStatus.available_prompt_credits.toLong()
                        availableFlowCredits = planStatus.available_flow_credits.toLong()

                        val planInfo = planStatus.plan_info ?: userStatus.plan_info
                        if (planInfo != null) {
                            planName = planInfo.plan_name
                            teamsTier = planInfo.teams_tier.name
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "GetUserStatus error: ${e.message}")
            }

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
