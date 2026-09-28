package com.example.gemini.data.remote.core

import com.example.gemini.data.preferences.AuthPreferences
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared singleton OkHttpClient provider for gRPC-Web and Wire transport.
 * Configured with timeouts, indefinite stream reading, and dynamic CSRF token evaluation.
 */
object AgyOkHttpClient {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite read timeout for streaming
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val hubUrl = AuthPreferences.currentHubUrl
                val token = AgyCsrfManager.instance.getCsrfTokenSync(hubUrl)
                val request = chain.request().newBuilder()
                    .header("User-Agent", "antiGem-Android-Native")
                    .header("X-Grpc-Web", "1")
                    .apply {
                        if (token.isNotBlank()) {
                            header("x-codeium-csrf-token", token)
                        }
                    }
                    .build()

                val response = chain.proceed(request)

                // Check for CSRF expiry (HTTP 401/403 or gRPC status 16 UNAUTHENTICATED)
                val isCsrfError = response.code == 401 || response.code == 403 || response.header("grpc-status") == "16"
                if (isCsrfError) {
                    response.close()
                    AgyCsrfManager.instance.notifyCsrfExpired(hubUrl, chain.request().url.encodedPath)
                    val freshToken = AgyCsrfManager.instance.getCsrfTokenSync(hubUrl, forceRefresh = true)
                    val retryRequest = request.newBuilder()
                        .header("x-codeium-csrf-token", freshToken)
                        .build()
                    return@addInterceptor chain.proceed(retryRequest)
                }

                response
            }
            .build()
    }
}
