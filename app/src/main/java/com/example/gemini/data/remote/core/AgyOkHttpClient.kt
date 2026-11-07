package com.example.gemini.data.remote.core

import com.example.gemini.data.preferences.AuthPreferences
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared singleton OkHttpClient provider for gRPC-Web and Wire transport.
 * Configured with high multiplexing dispatcher, connection pooling, timeouts, and dynamic CSRF token evaluation.
 */
object AgyOkHttpClient {
    // how long a request waits for the hub's CSRF token when the hub is starting / just came up
    private const val CSRF_WAIT_MS = 10_000L

    val client: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 256
            maxRequestsPerHost = 256
        }
        val connectionPool = ConnectionPool(32, 5, TimeUnit.MINUTES)

        // A hub on this phone (or an http:// address) speaks HTTP/2 without TLS ("prior knowledge"). A hub behind
        // nginx/Apache on https:// negotiates HTTP/2 through TLS (ALPN) like any web server, so it needs the
        // normal protocol list. OkHttp does not allow mixing the two.
        val hubUrl0 = AuthPreferences.currentHubUrl
        val hubIsTls = hubUrl0.startsWith("https://", ignoreCase = true)
        val hubIsRemote = !AuthPreferences.isLoopbackHost(AuthPreferences.hostOf(hubUrl0))

        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .protocols(
                if (hubIsTls) listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1)
                else listOf(okhttp3.Protocol.H2_PRIOR_KNOWLEDGE)
            )
            // keeps the long gRPC streams alive across NATs, proxies and mobile networks
            .pingInterval(if (hubIsRemote) 25 else 0, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for streaming
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (com.example.gemini.data.remote.inspector.NetworkInspectorManager.isEnabled) {
            builder.addInterceptor(com.example.gemini.data.remote.inspector.NetworkInspectorInterceptor("AGY Daemon (gRPC)"))
        }

        builder.addInterceptor { chain ->
                val hubUrl = AuthPreferences.currentHubUrl
                var token = AgyCsrfManager.instance.token
                if (token.isBlank()) {
                    try {
                        val status = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.gemini.data.remote.AgyBridgeService.instance.fetchServerStatus(AuthPreferences.currentBridgeHttpUrl)
                        }
                        if (!status?.csrfToken.isNullOrBlank()) {
                            token = status.csrfToken
                        }
                    } catch (_: Exception) {}
                }
                if (token.isBlank()) {
                    // App launch: the hub is (nearly) up but its token has not arrived yet. Sending without it is
                    // always rejected (the CSRF errors shown at launch), so wait a moment for it instead. Not when
                    // the hub is down or failed: then the request fails right away as before.
                    val hubState = com.example.gemini.data.remote.AgyBridgeService.instance.hubStatus.value.status
                    if (hubState == "starting" || hubState == "online" || hubState == "idle") {
                        token = try {
                            kotlinx.coroutines.runBlocking { AgyCsrfManager.instance.awaitToken(CSRF_WAIT_MS) }
                        } catch (_: Exception) { "" }
                    }
                }

                val requestBuilder = chain.request().newBuilder()
                    .header("User-Agent", "antiGem-Android-Native")
                    .header("X-Grpc-Web", "1")
                    .header(AuthPreferences.currentFramedHeader, token.ifBlank { "true" })
                AuthPreferences.edgeHeader()?.let { (n, v) -> requestBuilder.header(n, v) }
                val request = requestBuilder.build()

                val response = chain.proceed(request)

                // Check for CSRF expiry (HTTP 401/403 or gRPC status 16 UNAUTHENTICATED)
                val isCsrfError = response.code == 401 || response.code == 403 || response.header("grpc-status") == "16"
                if (isCsrfError) {
                    AgyCsrfManager.instance.notifyCsrfExpired(hubUrl, chain.request().url.encodedPath, usedToken = token)
                }

                response
            }
            .build()
    }
}
