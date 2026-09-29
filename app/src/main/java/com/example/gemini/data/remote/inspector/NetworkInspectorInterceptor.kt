package com.example.gemini.data.remote.inspector

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.UUID

/**
 * Ultra-lightweight OkHttp interceptor for capturing network routing and timing metrics.
 * Records only route URLs, HTTP methods, status codes, timing, and headers.
 * Does NOT buffer or retain request or response body payloads for maximum speed and zero memory overhead.
 */
class NetworkInspectorInterceptor(
    private val defaultServiceType: String? = null
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // FAST PATH: Zero allocation / instant pass-through when disabled
        if (!NetworkInspectorManager.isEnabled) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val logId = UUID.randomUUID().toString()
        val url = request.url.toString()
        val path = request.url.encodedPath

        val isGrpc = request.header("X-Grpc-Web") != null ||
                request.header("content-type")?.contains("grpc", ignoreCase = true) == true ||
                path.contains("language_server_pb", ignoreCase = true)

        val serviceType = defaultServiceType ?: if (isGrpc) "AGY Daemon (gRPC)" else "IDE Bridge (HTTP)"
        val reqHeaders = headersToMap(request.headers)

        val startEntry = NetworkLogEntry(
            id = logId,
            timestamp = startTime,
            url = url,
            path = path,
            method = if (isGrpc) "gRPC" else request.method,
            protocol = if (isGrpc) "HTTP/2" else "HTTP/1.1",
            serviceType = serviceType,
            startTimeMs = startTime,
            durationMs = -1L,
            statusCode = 0,
            isStreaming = false,
            requestHeaders = reqHeaders
        )
        try {
            NetworkInspectorManager.recordStart(startEntry)
        } catch (_: Exception) {}

        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            try {
                NetworkInspectorManager.recordError(logId, e.message ?: e.toString(), duration)
            } catch (_: Exception) {}
            throw e
        }

        val resHeaders = headersToMap(response.headers)
        val grpcStatus = response.header("grpc-status")
            ?: try { response.trailers().get("grpc-status") } catch (_: Exception) { null }

        val body = response.body
        if (body != null) {
            val originalSource = body.source()
            val forwardingSource = object : ForwardingSource(originalSource) {
                private var hasFinished = false

                override fun read(sink: Buffer, byteCount: Long): Long {
                    try {
                        val bytesRead = super.read(sink, byteCount)
                        if (bytesRead == -1L && !hasFinished) {
                            hasFinished = true
                            val streamDuration = System.currentTimeMillis() - startTime
                            val finalGrpcStatus = response.header("grpc-status")
                                ?: try { response.trailers().get("grpc-status") } catch (_: Exception) { null }
                            try {
                                NetworkInspectorManager.recordComplete(
                                    id = logId,
                                    statusCode = response.code,
                                    grpcStatus = finalGrpcStatus,
                                    durationMs = streamDuration
                                )
                            } catch (_: Exception) {}
                        }
                        return bytesRead
                    } catch (e: Exception) {
                        if (!hasFinished) {
                            hasFinished = true
                            val streamDuration = System.currentTimeMillis() - startTime
                            try {
                                NetworkInspectorManager.recordError(logId, e.message ?: e.toString(), streamDuration)
                            } catch (_: Exception) {}
                        }
                        throw e
                    }
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        if (!hasFinished) {
                            hasFinished = true
                            val streamDuration = System.currentTimeMillis() - startTime
                            val finalGrpcStatus = response.header("grpc-status")
                                ?: try { response.trailers().get("grpc-status") } catch (_: Exception) { null }
                            try {
                                NetworkInspectorManager.recordComplete(
                                    id = logId,
                                    statusCode = response.code,
                                    grpcStatus = finalGrpcStatus,
                                    durationMs = streamDuration
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            val wrappedBody = object : okhttp3.ResponseBody() {
                override fun contentType(): okhttp3.MediaType? = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                override fun source(): okio.BufferedSource = forwardingSource.buffer()
            }

            return response.newBuilder()
                .body(wrappedBody)
                .build()
        }

        // Response without body
        val duration = System.currentTimeMillis() - startTime
        try {
            NetworkInspectorManager.recordComplete(
                id = logId,
                statusCode = response.code,
                grpcStatus = grpcStatus,
                durationMs = duration
            )
        } catch (_: Exception) {}

        return response
    }

    private fun headersToMap(headers: Headers): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            map[name] = value
        }
        return map
    }
}
