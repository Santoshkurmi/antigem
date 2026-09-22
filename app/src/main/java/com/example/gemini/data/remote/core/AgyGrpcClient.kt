package com.example.gemini.data.remote.core

import android.util.Log
import com.example.gemini.data.preferences.AuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Universal transport client for AGY Hub language server service.
 * Supports standard application/json unary RPCs, gRPC-Web framed unary RPCs, and gRPC-Web streaming.
 */
class AgyGrpcClient(
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    val csrfManager: AgyCsrfManager = AgyCsrfManager.instance
) {
    companion object {
        private const val TAG = "AgyGrpcClient"
        const val SERVICE_LANGUAGE_SERVER = "exa.language_server_pb.LanguageServerService"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val GRPC_WEB_MEDIA_TYPE = "application/grpc-web+json".toMediaType()

        val instance by lazy { AgyGrpcClient() }
    }

    /**
     * Executes a unary Connect-RPC call using application/json
     */
    suspend fun callUnary(
        endpoint: String,
        jsonBody: String = "{}",
        hubUrl: String = AuthPreferences.currentHubUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = csrfManager.getCsrfToken(hubUrl)
            val base = hubUrl.trimEnd('/')
            val url = "$base/$SERVICE_LANGUAGE_SERVER/$endpoint"
            val req = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("User-Agent", "antiGem-Android-Native")
                .apply {
                    if (token.isNotBlank()) {
                        header("x-codeium-csrf-token", token)
                    }
                }
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val isCsrfError = resp.code == 401 || resp.code == 403 || body.contains("csrf", ignoreCase = true)
                    if (isCsrfError) {
                        Log.w(TAG, "CSRF error detected ($endpoint on $hubUrl). Refetching fallback CSRF token...")
                        csrfManager.notifyCsrfExpired(hubUrl, endpoint)
                        val newToken = csrfManager.getCsrfToken(hubUrl, forceRefresh = true)
                        val retryReq = req.newBuilder()
                            .header("x-codeium-csrf-token", newToken)
                            .build()
                        okHttpClient.newCall(retryReq).execute().use { retryResp ->
                            val retryBody = retryResp.body?.string() ?: ""
                            return@withContext if (retryResp.isSuccessful) {
                                Result.success(retryBody)
                            } else {
                                Result.failure(Exception("HTTP ${retryResp.code}: $retryBody"))
                            }
                        }
                    }
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $body"))
                }
                Result.success(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "callUnary $endpoint failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Executes a unary gRPC-Web call with full status and trailer verification.
     */
    suspend fun executeGrpcWebCall(
        endpoint: String,
        payloadJson: String,
        hubUrl: String = AuthPreferences.currentHubUrl,
        extraHeaders: Map<String, String> = emptyMap()
    ): Result<GrpcWebFrameCodec.GrpcResult> = withContext(Dispatchers.IO) {
        val token = csrfManager.getCsrfToken(hubUrl)
        val base = hubUrl.trimEnd('/')
        val url = "$base/$SERVICE_LANGUAGE_SERVER/$endpoint"
        val frameBytes = GrpcWebFrameCodec.encodeDataFrame(payloadJson)

        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .post(frameBytes.toRequestBody(GRPC_WEB_MEDIA_TYPE))
                .header("Content-Type", "application/grpc-web+json")
                .header("X-Grpc-Web", "1")
                .header("User-Agent", "antiGem-Android-Native")
                .apply {
                    if (token.isNotBlank()) {
                        header("x-codeium-csrf-token", token)
                    }
                }
            extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
            val req = reqBuilder.build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: "HTTP ${resp.code}"
                    val isCsrfError = resp.code == 401 || resp.code == 403 || err.contains("csrf", ignoreCase = true)
                    if (isCsrfError) {
                        csrfManager.notifyCsrfExpired(hubUrl, endpoint)
                        val newToken = csrfManager.getCsrfToken(hubUrl, forceRefresh = true)
                        val retryReq = req.newBuilder()
                            .header("x-codeium-csrf-token", newToken)
                            .build()
                        okHttpClient.newCall(retryReq).execute().use { retryResp ->
                            if (!retryResp.isSuccessful) {
                                val retryErr = retryResp.body?.string() ?: "HTTP ${retryResp.code}"
                                return@withContext Result.failure(Exception("$endpoint failed: $retryErr"))
                            }
                            val retryBodyBytes = retryResp.body?.bytes() ?: ByteArray(0)
                            val retryRes = GrpcWebFrameCodec.parseGrpcWebBody(retryBodyBytes)
                            if (retryRes.status != 0) {
                                val errMsg = retryRes.message ?: "gRPC error status ${retryRes.status}"
                                return@withContext Result.failure(Exception("$endpoint error (${retryRes.status}): $errMsg"))
                            }
                            return@withContext Result.success(retryRes)
                        }
                    }
                    return@withContext Result.failure(Exception("$endpoint failed: $err"))
                }
                val headerStatus = resp.header("grpc-status")?.toIntOrNull()
                if (headerStatus != null && headerStatus != 0) {
                    val headerMsg = resp.header("grpc-message") ?: "gRPC status $headerStatus"
                    return@withContext Result.failure(Exception("$endpoint error ($headerStatus): $headerMsg"))
                }
                val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
                val res = GrpcWebFrameCodec.parseGrpcWebBody(bodyBytes)
                if (res.status != 0) {
                    val errMsg = res.message ?: "gRPC error status ${res.status}"
                    return@withContext Result.failure(Exception("$endpoint error (${res.status}): $errMsg"))
                }
                Result.success(res)
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeGrpcWebCall $endpoint failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Executes a streaming gRPC-Web call and yields parsed JSON frames.
     */
    fun callStream(
        endpoint: String,
        jsonPayload: String = "{}",
        hubUrl: String = AuthPreferences.currentHubUrl,
        headers: Map<String, String> = emptyMap()
    ): Flow<String> = flow {
        val token = csrfManager.getCsrfToken(hubUrl)
        val base = hubUrl.trimEnd('/')
        val url = "$base/$SERVICE_LANGUAGE_SERVER/$endpoint"
        val frameBytes = GrpcWebFrameCodec.encodeDataFrame(jsonPayload)

        val reqBuilder = Request.Builder()
            .url(url)
            .post(frameBytes.toRequestBody(GRPC_WEB_MEDIA_TYPE))
            .header("Content-Type", "application/grpc-web+json")
            .header("X-Grpc-Web", "1")
            .header("User-Agent", "antiGem-Android-Native")
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (token.isNotBlank()) {
                    header("x-codeium-csrf-token", token)
                }
            }
        val req = reqBuilder.build()

        val call = okHttpClient.newCall(req)
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            call.cancel()
        }
        val resp = call.execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: "HTTP ${resp.code}"
            resp.close()
            val isCsrfError = resp.code == 401 || resp.code == 403 || err.contains("csrf", ignoreCase = true)
            if (isCsrfError) {
                csrfManager.notifyCsrfExpired(hubUrl, endpoint)
            }
            Log.e(TAG, "callStream $endpoint failed: HTTP ${resp.code}")
            return@flow
        }

        val headerStatus = resp.header("grpc-status")?.toIntOrNull()
        if (headerStatus != null && headerStatus != 0) {
            val msg = resp.header("grpc-message") ?: "gRPC status $headerStatus"
            resp.close()
            Log.e(TAG, "callStream $endpoint header error ($headerStatus): $msg")
            return@flow
        }

        val bodyStream = resp.body?.byteStream()
        if (bodyStream == null) {
            resp.close()
            return@flow
        }

        try {
            GrpcWebFrameCodec.readStreamFrames(bodyStream) { frameJson ->
                emit(frameJson)
            }
        } finally {
            resp.close()
        }
    }.flowOn(Dispatchers.IO)
}

