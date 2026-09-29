package com.example.gemini.data.remote.services

import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.core.AgyCsrfManager
import com.example.gemini.data.remote.core.AgyOkHttpClient
import com.squareup.wire.GrpcClient
import exa.language_server_pb.GrpcLanguageServerServiceClient
import exa.language_server_pb.LanguageServerServiceClient

import com.squareup.wire.GrpcCall
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import com.squareup.wire.GrpcStreamingCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/**
 * Single shared typed entrypoint for Antigravity LanguageServerService gRPC APIs.
 * Connects to the active Hub URL using the shared OkHttpClient connection pool.
 */
val AgyLanguageService: LanguageServerServiceClient by lazy {
    val hubUrl = AuthPreferences.currentHubUrl.ifBlank { AuthPreferences.DEFAULT_HUB_URL }
    val normalizedUrl = if (!hubUrl.startsWith("http://") && !hubUrl.startsWith("https://")) {
        "http://$hubUrl"
    } else {
        hubUrl
    }.trimEnd('/')

    GrpcLanguageServerServiceClient(
        GrpcClient.Builder()
            .client(AgyOkHttpClient.client)
            .baseUrl(normalizedUrl)
            .build()
    )
}

/**
 * Domain error model for AGY Language Server gRPC calls.
 */
sealed class AgyRpcError(override val message: String, override val cause: Throwable? = null) : Exception(message, cause) {
    data class DaemonOffline(
        override val message: String = "AGY Hub is offline or unreachable.",
        override val cause: Throwable? = null
    ) : AgyRpcError(message, cause)

    data class Unauthenticated(
        override val message: String = "Session unauthenticated or CSRF validation failed."
    ) : AgyRpcError(message)

    data class ServerError(
        val status: GrpcStatus,
        override val message: String
    ) : AgyRpcError(message)

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : AgyRpcError(message, cause)
}

/**
 * Executes a unary gRPC call safely, mapping network/gRPC failures into AgyRpcError.
 * Automatically handles CSRF expiry by invalidating cached token and retrying once.
 */
suspend fun <Req : Any, Resp : Any> GrpcCall<Req, Resp>.executeSafely(
    request: Req
): Result<Resp> = try {
    val reqStr = request.toString().take(120)
    val res = execute(request)
    Result.success(res)
} catch (e: CancellationException) {
    android.util.Log.w("AGY_RPC", "⚠️ [AgyLanguageService.executeSafely] RPC Cancelled: ${e.message}")
    throw e
} catch (e: GrpcException) {
    android.util.Log.e("AGY_RPC", "❌ [AgyLanguageService.executeSafely] GrpcException: status=${e.grpcStatus}, msg=${e.grpcMessage}", e)
    val isCsrfError = e.grpcStatus == GrpcStatus.UNAUTHENTICATED || e.grpcMessage?.contains("CSRF", ignoreCase = true) == true
    if (isCsrfError) {
        val hubUrl = AuthPreferences.currentHubUrl
        android.util.Log.w("AGY_RPC", "🔄 [AgyLanguageService.executeSafely] CSRF error detected on RPC. Invalidating cache and retrying...")
        AgyCsrfManager.instance.notifyCsrfExpired(hubUrl, "executeSafely")
        try {
            AgyCsrfManager.instance.getCsrfToken(hubUrl, forceRefresh = true)
            val retryRes = execute(request)
            android.util.Log.d("AGY_RPC", "✅ [AgyLanguageService.executeSafely] Retry succeeded after CSRF refresh: ${retryRes::class.simpleName}")
            Result.success(retryRes)
        } catch (retryEx: CancellationException) {
            throw retryEx
        } catch (retryEx: GrpcException) {
            android.util.Log.e("AGY_RPC", "❌ [AgyLanguageService.executeSafely] Retry failed: status=${retryEx.grpcStatus}, msg=${retryEx.grpcMessage}", retryEx)
            val err = when (retryEx.grpcStatus) {
                GrpcStatus.UNAUTHENTICATED -> AgyRpcError.Unauthenticated(retryEx.grpcMessage ?: "Unauthenticated")
                GrpcStatus.UNAVAILABLE -> AgyRpcError.DaemonOffline("AGY Hub is offline or unreachable.", retryEx)
                else -> AgyRpcError.ServerError(retryEx.grpcStatus, retryEx.grpcMessage ?: retryEx.message ?: "gRPC error")
            }
            Result.failure(err)
        } catch (retryEx: Throwable) {
            android.util.Log.e("AGY_RPC", "❌ [AgyLanguageService.executeSafely] Retry failed with throwable: ${retryEx.message}", retryEx)
            Result.failure(AgyRpcError.Unknown(retryEx.message ?: "Unexpected error during retry", retryEx))
        }
    } else {
        val err = when (e.grpcStatus) {
            GrpcStatus.UNAVAILABLE -> AgyRpcError.DaemonOffline("AGY Hub is offline or unreachable.", e)
            else -> AgyRpcError.ServerError(e.grpcStatus, e.grpcMessage ?: e.message ?: "gRPC error")
        }
        Result.failure(err)
    }
} catch (e: IOException) {
    android.util.Log.e("AGY_RPC", "❌ [AgyLanguageService.executeSafely] IOException: ${e.message}", e)
    Result.failure(AgyRpcError.DaemonOffline("Failed to connect to AGY Hub: ${e.message}", e))
} catch (e: Throwable) {
    android.util.Log.e("AGY_RPC", "❌ [AgyLanguageService.executeSafely] Throwable: ${e.message}", e)
    Result.failure(AgyRpcError.Unknown(e.message ?: "Unexpected error", e))
}

/**
 * Executes a server-streaming gRPC call as a Kotlin Flow with automatic error mapping.
 * On CSRF / UNAUTHENTICATED error, invalidates CSRF token cache so immediate stream reconnects succeed.
 */
fun <Req : Any, Resp : Any> GrpcStreamingCall<Req, Resp>.asFlowSafely(
    request: Req
): Flow<Resp> = callbackFlow {
    val reqStr = request.toString().take(120)
    val (requestChannel, responseChannel) = executeIn(this)
    try {
        requestChannel.send(request)
        requestChannel.close()

        for (message in responseChannel) {
            trySend(message)
        }
        channel.close()
    } catch (e: CancellationException) {
        android.util.Log.d("CHAT_OPEN_DEBUG", "⚠️ [AgyLanguageService.asFlowSafely] Stream cancelled: ${e.message}")
        throw e
    } catch (e: GrpcException) {
        android.util.Log.e("CHAT_OPEN_DEBUG", "❌ [AgyLanguageService.asFlowSafely] GrpcException: status=${e.grpcStatus}, msg=${e.grpcMessage}", e)
        val isCsrfError = e.grpcStatus == GrpcStatus.UNAUTHENTICATED || e.grpcMessage?.contains("CSRF", ignoreCase = true) == true
        if (isCsrfError) {
            val hubUrl = AuthPreferences.currentHubUrl
            android.util.Log.w("CHAT_OPEN_DEBUG", "🔄 [AgyLanguageService.asFlowSafely] CSRF error on stream. Invalidating CSRF cache for $hubUrl...")
            AgyCsrfManager.instance.notifyCsrfExpired(hubUrl, "asFlowSafely")
        }
        val err = when (e.grpcStatus) {
            GrpcStatus.UNAUTHENTICATED -> AgyRpcError.Unauthenticated(e.grpcMessage ?: "Unauthenticated")
            GrpcStatus.UNAVAILABLE -> AgyRpcError.DaemonOffline("AGY Hub is offline or unreachable.", e)
            else -> AgyRpcError.ServerError(e.grpcStatus, e.grpcMessage ?: e.message ?: "Stream error")
        }
        channel.close(err)
    } catch (e: IOException) {
        android.util.Log.e("CHAT_OPEN_DEBUG", "❌ [AgyLanguageService.asFlowSafely] IOException: ${e.message}", e)
        channel.close(AgyRpcError.DaemonOffline("Stream connection broken: ${e.message}", e))
    } catch (e: Throwable) {
        android.util.Log.e("CHAT_OPEN_DEBUG", "❌ [AgyLanguageService.asFlowSafely] Throwable: ${e.message}", e)
        channel.close(AgyRpcError.Unknown(e.message ?: "Stream error", e))
    } finally {
        responseChannel.cancel()
        requestChannel.close()
    }

    awaitClose {
        android.util.Log.d("CHAT_OPEN_DEBUG", "🔒 [AgyLanguageService.asFlowSafely] awaitClose invoked, cancelling channel.")
        responseChannel.cancel()
        requestChannel.close()
        cancel()
    }
}.flowOn(Dispatchers.IO)


