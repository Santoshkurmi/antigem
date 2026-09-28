package com.example.gemini.data.remote.services

import com.example.gemini.data.preferences.AuthPreferences
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
 */
suspend fun <Req : Any, Resp : Any> GrpcCall<Req, Resp>.executeSafely(
    request: Req
): Result<Resp> = try {
    Result.success(execute(request))
} catch (e: CancellationException) {
    throw e
} catch (e: GrpcException) {
    val err = when (e.grpcStatus) {
        GrpcStatus.UNAUTHENTICATED -> AgyRpcError.Unauthenticated(e.grpcMessage ?: "Unauthenticated")
        GrpcStatus.UNAVAILABLE -> AgyRpcError.DaemonOffline("AGY Hub is offline or unreachable.", e)
        else -> AgyRpcError.ServerError(e.grpcStatus, e.grpcMessage ?: e.message ?: "gRPC error")
    }
    Result.failure(err)
} catch (e: IOException) {
    Result.failure(AgyRpcError.DaemonOffline("Failed to connect to AGY Hub: ${e.message}", e))
} catch (e: Throwable) {
    Result.failure(AgyRpcError.Unknown(e.message ?: "Unexpected error", e))
}

/**
 * Executes a server-streaming gRPC call as a Kotlin Flow with automatic error mapping.
 */
fun <Req : Any, Resp : Any> GrpcStreamingCall<Req, Resp>.asFlowSafely(
    request: Req
): Flow<Resp> = callbackFlow {
    val reqStr = request.toString().take(120)
    android.util.Log.d("CHAT_OPEN_DEBUG", "🌐 [AgyLanguageService.asFlowSafely] Stream initiated. Request: $reqStr")
    val (requestChannel, responseChannel) = executeIn(this)
    try {
        requestChannel.send(request)
        requestChannel.close()
        android.util.Log.d("CHAT_OPEN_DEBUG", "🌐 [AgyLanguageService.asFlowSafely] Request sent to channel. Awaiting response frames...")

        for (message in responseChannel) {
            trySend(message)
        }
        channel.close()
    } catch (e: CancellationException) {
        android.util.Log.d("CHAT_OPEN_DEBUG", "⚠️ [AgyLanguageService.asFlowSafely] Stream cancelled: ${e.message}")
        throw e
    } catch (e: GrpcException) {
        android.util.Log.e("CHAT_OPEN_DEBUG", "❌ [AgyLanguageService.asFlowSafely] GrpcException: status=${e.grpcStatus}, msg=${e.grpcMessage}", e)
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


